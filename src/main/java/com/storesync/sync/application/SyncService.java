package com.storesync.sync.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.storesync.catalog.application.CatalogService;
import com.storesync.catalog.domain.Product;
import com.storesync.inventory.application.InventoryService;
import com.storesync.inventory.application.StockRepository.StockState;
import com.storesync.outbox.application.Outbox;
import com.storesync.promo.application.PromotionService;
import com.storesync.promo.domain.Promotion;
import com.storesync.sales.application.InvoiceService;
import com.storesync.sales.application.SalesService;
import com.storesync.sales.application.ShiftService;
import com.storesync.sales.domain.FinancialYear;
import com.storesync.sales.domain.InvoiceBlocks;
import com.storesync.shared.config.BusinessZone;
import com.storesync.shared.config.StoreSyncProperties;
import com.storesync.shared.domain.DomainRuleException;
import com.storesync.shared.domain.Hashes;
import com.storesync.shared.error.ApiException;
import com.storesync.shared.security.Caller;
import com.storesync.shared.security.Role;
import com.storesync.store.application.StoreService;
import com.storesync.store.domain.Terminal;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Terminal op-log sync. A push is applied one op at a time, each in its own transaction under the terminal's row
 * lock, so progress survives a failure half way and two concurrent uploads of the same batch queue up instead of
 * racing. (terminal, epoch, seq) is the idempotency key: a resent op with the same content is a DUPLICATE, with
 * different content a CONFLICT. An op the server cannot accept is stored as REJECTED and still advances the
 * sequence, because the till has already printed the receipt and must not be stuck behind it; the rejection is
 * published so a person can deal with it.
 */
@Service
public class SyncService {

    public enum OpType { SHIFT_OPENED, SALE, SALE_VOIDED, SHIFT_CLOSED }

    public enum Status { APPLIED, REJECTED, DUPLICATE, CONFLICT, GAP }

    public record Op(long seq, String type, int v, JsonNode payload) {
    }

    public record Push(UUID terminalId, int epoch, List<Op> ops) {
    }

    public record OpResult(long seq, Status status, String code, String message) {
    }

    public record PushResult(UUID terminalId, int epoch, long lastSeq, long expectedSeq, List<OpResult> results,
                             Map<Status, Integer> counts) {
    }

    public record ShiftOpened(UUID shiftId, String cashier, BigDecimal openingFloat, Instant openedAt) {
    }

    public record ShiftClosed(UUID shiftId, BigDecimal countedCash, Integer declaredSales, Instant closedAt) {
    }

    public record StockView(String sku, long onHand, Map<String, Long> increments, Map<String, Long> decrements) {
    }

    public record PullResult(long version, boolean full, UUID terminalId, int epoch, long lastSeq,
                             List<Product> products, List<Promotion> promotions, List<StockView> stock,
                             InvoiceBlocks invoiceBlocks) {
    }

    private final StoreService stores;
    private final SalesService sales;
    private final ShiftService shifts;
    private final InvoiceService invoices;
    private final CatalogService catalog;
    private final PromotionService promos;
    private final InventoryService inventory;
    private final SyncRepository repo;
    private final Outbox outbox;
    private final StoreSyncProperties props;
    private final Clock clock;
    private final MeterRegistry meters;
    private final ObjectMapper json;
    private final ObjectMapper canonical;
    private final TransactionTemplate perOp;
    private final TransactionTemplate savepoint;
    private final TransactionTemplate readOnly;
    private final Timer pushTimer;

    SyncService(StoreService stores, SalesService sales, ShiftService shifts, InvoiceService invoices,
                CatalogService catalog, PromotionService promos, InventoryService inventory, SyncRepository repo,
                Outbox outbox, StoreSyncProperties props, Clock clock, MeterRegistry meters, ObjectMapper json,
                PlatformTransactionManager tx) {
        this.stores = stores;
        this.sales = sales;
        this.shifts = shifts;
        this.invoices = invoices;
        this.catalog = catalog;
        this.promos = promos;
        this.inventory = inventory;
        this.repo = repo;
        this.outbox = outbox;
        this.props = props;
        this.clock = clock;
        this.meters = meters;
        this.json = json;
        this.canonical = json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.perOp = new TransactionTemplate(tx);
        this.savepoint = new TransactionTemplate(tx);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.readOnly = new TransactionTemplate(tx);
        this.readOnly.setReadOnly(true);
        this.pushTimer = Timer.builder("storesync.sync.push").publishPercentileHistogram().register(meters);
    }

    public PushResult push(Caller caller, Push push) {
        return pushTimer.record(() -> doPush(caller, push));
    }

    private PushResult doPush(Caller caller, Push push) {
        if (push.terminalId() == null || push.ops() == null) {
            throw ApiException.invalid("terminalId and ops are required");
        }
        if (!caller.has(Role.TERMINAL) || !push.terminalId().equals(caller.terminalId())) {
            throw ApiException.forbidden("not-this-terminal", "Only the terminal itself can push its op log");
        }
        if (push.ops().size() > props.sync().batchMax()) {
            throw ApiException.invalid("batch-too-large", "At most " + props.sync().batchMax() + " ops per push");
        }
        Terminal t = stores.terminal(push.terminalId());
        if (t.epoch() != push.epoch()) {
            throw staleEpoch(t);
        }
        var results = new ArrayList<OpResult>();
        for (Op op : push.ops()) {
            OpResult r = perOp.execute(s -> one(push, op));
            results.add(r);
            meters.counter("storesync.sync.ops", "status", r.status().name()).increment();
            if (r.status() == Status.GAP || r.status() == Status.CONFLICT) {
                break;
            }
        }
        Terminal after = stores.terminal(push.terminalId());
        var counts = new EnumMap<Status, Integer>(Status.class);
        results.forEach(r -> counts.merge(r.status(), 1, Integer::sum));
        return new PushResult(after.id(), after.epoch(), after.lastSeq(), after.lastSeq() + 1, results, counts);
    }

    private static ApiException staleEpoch(Terminal t) {
        return new ApiException(409, "stale-epoch", "Terminal was re-enrolled; current epoch is " + t.epoch(),
                Map.of("epoch", t.epoch()));
    }

    private OpResult one(Push push, Op op) {
        Terminal t = stores.lockTerminal(push.terminalId());
        if (t.epoch() != push.epoch()) {
            throw staleEpoch(t);
        }
        String payloadJson = canonicalJson(op.payload());
        String hash = Hashes.sha256(op.type() + "|" + op.v() + "|" + payloadJson);
        if (op.seq() <= t.lastSeq()) {
            var logged = repo.find(t.id(), t.epoch(), op.seq());
            if (logged.isPresent() && logged.get().payloadHash().equals(hash)) {
                return new OpResult(op.seq(), Status.DUPLICATE, logged.get().status(), logged.get().reason());
            }
            return new OpResult(op.seq(), Status.CONFLICT, "seq-reused",
                    "Seq " + op.seq() + " was already received with different content");
        }
        if (op.seq() != t.lastSeq() + 1) {
            return new OpResult(op.seq(), Status.GAP, "expected-" + (t.lastSeq() + 1),
                    "Ops must arrive in order; send seq " + (t.lastSeq() + 1) + " next");
        }
        String rejectCode = null;
        String rejectMessage = null;
        try {
            savepoint.executeWithoutResult(s -> apply(t, op, hash));
        } catch (DomainRuleException e) {
            rejectCode = e.code();
            rejectMessage = e.getMessage();
        } catch (ApiException e) {
            if (e.status() >= 500) {
                throw e;
            }
            rejectCode = e.code();
            rejectMessage = e.getMessage();
        } catch (IllegalArgumentException e) {
            rejectCode = "bad-payload";
            rejectMessage = e.getMessage();
        }
        Instant now = clock.instant();
        String status = rejectCode == null ? "APPLIED" : "REJECTED";
        repo.log(t.id(), t.epoch(), op.seq(), op.type(), op.v(), payloadJson, hash, status, rejectCode, now);
        stores.advance(t.id(), op.seq());
        if (rejectCode != null) {
            var data = new LinkedHashMap<String, Object>();
            data.put("store", t.storeCode());
            data.put("terminalId", t.id().toString());
            data.put("epoch", t.epoch());
            data.put("seq", op.seq());
            data.put("type", op.type());
            data.put("code", rejectCode);
            data.put("message", rejectMessage);
            outbox.append(Outbox.SALES, t.storeCode(), "OpRejected", data);
            return new OpResult(op.seq(), Status.REJECTED, rejectCode, rejectMessage);
        }
        return new OpResult(op.seq(), Status.APPLIED, null, null);
    }

    private void apply(Terminal t, Op op, String hash) {
        if (!props.sync().opVersions().contains(op.v())) {
            throw new DomainRuleException("unsupported-version", "Op version " + op.v() + " is not accepted; supported "
                    + props.sync().opVersions());
        }
        OpType type;
        try {
            type = OpType.valueOf(op.type());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new DomainRuleException("unknown-op", "Unknown op type " + op.type());
        }
        if (op.payload() == null || !op.payload().isObject()) {
            throw new DomainRuleException("bad-payload", "Payload must be an object");
        }
        JsonNode payload = upgrade(type, op.v(), (ObjectNode) op.payload().deepCopy());
        switch (type) {
            case SHIFT_OPENED -> {
                var p = read(payload, ShiftOpened.class);
                if (p.shiftId() == null || p.cashier() == null || p.openedAt() == null) {
                    throw new DomainRuleException("bad-payload", "shiftId, cashier and openedAt are required");
                }
                shifts.openOffline(t, p.shiftId(), p.cashier(), p.openingFloat(), p.openedAt());
            }
            case SALE -> sales.recordOffline(t, op.seq(), read(payload, SalesService.OfflineSale.class), hash);
            case SALE_VOIDED -> sales.recordVoid(t, op.seq(), read(payload, SalesService.VoidedInvoice.class), hash);
            case SHIFT_CLOSED -> {
                var p = read(payload, ShiftClosed.class);
                if (p.shiftId() == null || p.declaredSales() == null || p.closedAt() == null) {
                    throw new DomainRuleException("bad-payload", "shiftId, declaredSales and closedAt are required");
                }
                shifts.closeOffline(t, p.shiftId(), p.countedCash(), p.declaredSales(), p.closedAt());
            }
        }
    }

    /**
     * Version 2 of SALE added {@code customerGstin} (B2B invoices) and {@code promotions}. A version 1 op is read
     * as a walk-in sale with no promotion record; anything v1 tills send in those fields is ignored.
     */
    static JsonNode upgrade(OpType type, int v, ObjectNode payload) {
        if (type == OpType.SALE && v == 1) {
            payload.remove("customerGstin");
            payload.remove("promotions");
        }
        return payload;
    }

    private <T> T read(JsonNode payload, Class<T> type) {
        try {
            return json.treeToValue(payload, type);
        } catch (JsonProcessingException e) {
            throw new DomainRuleException("bad-payload", e.getOriginalMessage());
        }
    }

    private String canonicalJson(JsonNode payload) {
        try {
            Object tree = payload == null ? null : canonical.treeToValue(payload, Object.class);
            return canonical.writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            throw ApiException.invalid("bad-payload", "Payload is not valid JSON");
        }
    }

    /**
     * Everything that changed since {@code since}; 0 gives a full snapshot. The returned version is a
     * transaction-id floor, so the next pull may repeat a few rows but never skips one.
     */
    public PullResult pull(Caller caller, long since) {
        if (!caller.has(Role.TERMINAL) || caller.terminalId() == null) {
            throw ApiException.forbidden("terminal-only", "Only terminals pull");
        }
        if (since < 0) {
            throw ApiException.invalid("since must not be negative");
        }
        return readOnly.execute(s -> {
            long version = repo.snapshotFloor();
            Terminal t = stores.terminal(caller.terminalId());
            var stock = inventory.changedSince(t.storeCode(), since).stream().map(SyncService::view).toList();
            var fy = FinancialYear.of(BusinessZone.today(clock));
            meters.counter("storesync.sync.pulls", "full", Boolean.toString(since == 0)).increment();
            return new PullResult(version, since == 0, t.id(), t.epoch(), t.lastSeq(), catalog.changedSince(since),
                    promos.changedSince(since), stock, invoices.blocks(t, fy));
        });
    }

    private static StockView view(StockState s) {
        return new StockView(s.sku(), s.onHand(), s.counter().increments(), s.counter().decrements());
    }
}
