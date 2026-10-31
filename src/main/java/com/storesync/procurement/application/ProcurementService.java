package com.storesync.procurement.application;

import com.storesync.analytics.application.AnalyticsService;
import com.storesync.catalog.application.CatalogService;
import com.storesync.catalog.domain.Product;
import com.storesync.inventory.application.InventoryService;
import com.storesync.inventory.domain.StockPolicy;
import com.storesync.procurement.application.ProcurementRepository.PoLine;
import com.storesync.procurement.application.ProcurementRepository.PurchaseOrder;
import com.storesync.procurement.application.ProcurementRepository.Receipt;
import com.storesync.procurement.application.ProcurementRepository.Supplier;
import com.storesync.procurement.domain.PoStatus;
import com.storesync.procurement.domain.ReplenishmentPlanner;
import com.storesync.shared.config.BusinessZone;
import com.storesync.shared.config.StoreSyncProperties;
import com.storesync.shared.domain.DomainRuleException;
import com.storesync.shared.domain.Money;
import com.storesync.shared.error.ApiException;
import com.storesync.shared.security.Caller;
import com.storesync.shared.security.Role;
import com.storesync.store.application.StoreService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class ProcurementService {

    public record NewLine(String sku, int qty, BigDecimal unitCost) {
    }

    public record NewOrder(UUID supplierId, List<NewLine> lines) {
    }

    public record Suggestion(String sku, String name, UUID supplierId, int leadTimeDays, long onHand, long onOrder,
                             ReplenishmentPlanner.Plan plan) {
    }

    public record Replenishment(String store, LocalDate asOf, int historyDays, List<Suggestion> suggestions,
                                List<PurchaseOrder> raised) {
    }

    private final ProcurementRepository repo;
    private final StoreService stores;
    private final CatalogService catalog;
    private final InventoryService inventory;
    private final AnalyticsService analytics;
    private final StoreSyncProperties props;
    private final Clock clock;
    private final MeterRegistry meters;

    ProcurementService(ProcurementRepository repo, StoreService stores, CatalogService catalog,
                       InventoryService inventory, AnalyticsService analytics, StoreSyncProperties props, Clock clock,
                       MeterRegistry meters) {
        this.repo = repo;
        this.stores = stores;
        this.catalog = catalog;
        this.inventory = inventory;
        this.analytics = analytics;
        this.props = props;
        this.clock = clock;
        this.meters = meters;
    }

    @Transactional
    public Supplier createSupplier(Caller caller, Supplier s) {
        caller.require(Role.ADMIN);
        if (s.id() == null || s.name() == null || s.name().isBlank() || s.leadTimeDays() <= 0) {
            throw ApiException.invalid("id, name and a positive leadTimeDays are required");
        }
        if (!repo.insertSupplier(s)) {
            var existing = repo.supplier(s.id()).orElseThrow();
            if (!existing.equals(s)) {
                throw ApiException.conflict("supplier-exists", "A different supplier already has this id");
            }
            return existing;
        }
        return s;
    }

    @Transactional(readOnly = true)
    public List<Supplier> suppliers(Caller caller) {
        caller.require(Role.ADMIN, Role.MANAGER);
        return repo.suppliers();
    }

    /**
     * Idempotent on the client's key: a retry with the same body returns the order made the first time, the same
     * key with a different body is refused. Two concurrent requests with one key meet on the unique index, so the
     * loser waits for the winner's commit and then reads its order.
     */
    @Transactional
    public PurchaseOrder order(Caller caller, String store, String idempotencyKey, NewOrder cmd) {
        caller.requireStore(store, Role.MANAGER);
        return place(caller.subject(), store, idempotencyKey, cmd);
    }

    private PurchaseOrder place(String by, String store, String key, NewOrder cmd) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw ApiException.invalid("idempotency-key-required", "Send an Idempotency-Key header of 1-100 characters");
        }
        if (cmd.supplierId() == null || cmd.lines() == null || cmd.lines().isEmpty()) {
            throw ApiException.invalid("supplierId and at least one line are required");
        }
        stores.store(store);
        var supplier = repo.supplier(cmd.supplierId())
                .orElseThrow(() -> new DomainRuleException("unknown-supplier", "No supplier " + cmd.supplierId()));
        var lines = new TreeMap<String, PoLine>();
        for (NewLine l : cmd.lines()) {
            if (l.sku() == null || l.qty() <= 0) {
                throw ApiException.invalid("Each line needs a sku and a positive qty");
            }
            BigDecimal cost = Money.exact(l.unitCost(), "unitCost");
            if (cost.signum() < 0) {
                throw ApiException.invalid("unitCost cannot be negative");
            }
            if (lines.put(l.sku(), new PoLine(l.sku(), l.qty(), cost, 0)) != null) {
                throw new DomainRuleException("duplicate-line", l.sku() + " appears twice");
            }
        }
        var products = catalog.find(lines.keySet());
        for (String sku : lines.keySet()) {
            Product p = products.get(sku);
            if (p == null) {
                throw new DomainRuleException("unknown-sku", "No product " + sku);
            }
            if (!Objects.equals(p.supplierId(), supplier.id())) {
                throw new DomainRuleException("wrong-supplier", sku + " is not supplied by " + supplier.name());
            }
        }
        var po = new PurchaseOrder(UUID.randomUUID(), store, supplier.id(), PoStatus.ISSUED, key, by, clock.instant(),
                List.copyOf(lines.values()));
        if (repo.insertOrder(po)) {
            meters.counter("storesync.po.created").increment();
            return repo.order(po.id(), false).orElseThrow();
        }
        var existing = repo.orderByKey(key).orElseThrow();
        boolean same = existing.store().equals(store) && existing.supplierId().equals(supplier.id())
                && existing.lines().stream().map(l -> new PoLine(l.sku(), l.qty(), l.unitCost(), 0)).toList()
                .equals(po.lines());
        if (!same) {
            throw ApiException.unprocessable("idempotency-key-reused", "That Idempotency-Key was used for a different order");
        }
        return existing;
    }

    @Transactional(readOnly = true)
    public PurchaseOrder get(Caller caller, UUID id) {
        var po = repo.order(id, false).orElseThrow(() -> ApiException.notFound("purchase order"));
        caller.requireStore(po.store(), Role.MANAGER);
        return po;
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrder> orders(Caller caller, String store) {
        caller.requireStore(store, Role.MANAGER);
        return repo.orders(store);
    }

    /**
     * Goods receipt against an order. The order row is locked first, so two receipts for one order apply one
     * after the other and can never take it past what was ordered; (order, ref) makes a resubmitted delivery note
     * a no-op.
     */
    @Transactional
    public Receipt receive(Caller caller, UUID poId, String ref, Map<String, Integer> received) {
        if (ref == null || ref.isBlank() || ref.length() > 64 || received == null || received.isEmpty()) {
            throw ApiException.invalid("ref (1-64 characters) and received quantities are required");
        }
        var po = repo.order(poId, true).orElseThrow(() -> ApiException.notFound("purchase order"));
        caller.requireStore(po.store(), Role.MANAGER);
        var wanted = new TreeMap<>(received);
        var existing = repo.receipt(poId, ref);
        if (existing.isPresent()) {
            if (!existing.get().lines().equals(wanted)) {
                throw ApiException.unprocessable("grn-ref-reused", "Receipt " + ref + " was recorded with other quantities");
            }
            return existing.get();
        }
        var state = new LinkedHashMap<String, PoStatus.LineState>();
        po.lines().forEach(l -> state.put(l.sku(), new PoStatus.LineState(l.qty(), l.received())));
        PoStatus next = PoStatus.afterReceipt(po.status(), state, wanted);
        var r = new Receipt(UUID.randomUUID(), poId, ref, caller.subject(), clock.instant(), wanted);
        repo.insertReceipt(r);
        repo.updateReceived(poId, next, wanted);
        var moves = wanted.entrySet().stream().filter(e -> e.getValue() > 0)
                .map(e -> new InventoryService.Move(e.getKey(), e.getValue())).toList();
        if (!moves.isEmpty()) {
            inventory.apply(po.store(), InventoryService.HQ, moves, StockPolicy.ALLOW_NEGATIVE, "GRN", r.id().toString());
        }
        meters.counter("storesync.grn").increment();
        return r;
    }

    /**
     * Reorder-point suggestions for every active product with a supplier. Demand comes from the analytics rollup,
     * so sales from the last few seconds may not be in it yet. With {@code raiseKey} set, one order per supplier
     * is raised for the suggested quantities (the key makes a repeated call return the same orders).
     */
    @Transactional
    public Replenishment replenishment(Caller caller, String store, String raiseKey) {
        caller.requireStore(store, Role.MANAGER);
        stores.store(store);
        var cfg = props.replenishment();
        var planner = new ReplenishmentPlanner(cfg.alpha(), cfg.serviceZ(), cfg.reviewDays());
        LocalDate today = BusinessZone.today(clock);
        var products = catalog.all().stream().filter(p -> p.active() && p.supplierId() != null)
                .sorted(Comparator.comparing(Product::sku)).toList();
        var suppliers = new HashMap<UUID, Supplier>();
        repo.suppliers().forEach(s -> suppliers.put(s.id(), s));
        var onHand = inventory.onHand(store, products.stream().map(Product::sku).toList());
        var onOrder = repo.onOrder(store);
        var out = new ArrayList<Suggestion>();
        for (Product p : products) {
            Supplier s = suppliers.get(p.supplierId());
            if (s == null) {
                continue;
            }
            long have = onHand.getOrDefault(p.sku(), 0L);
            long coming = onOrder.getOrDefault(p.sku(), 0L);
            var demand = analytics.dailyDemand(store, p.sku(), today, cfg.historyDays());
            out.add(new Suggestion(p.sku(), p.name(), s.id(), s.leadTimeDays(), have, coming,
                    planner.plan(demand, s.leadTimeDays(), have, coming)));
        }
        var raised = new ArrayList<PurchaseOrder>();
        if (raiseKey != null) {
            var bySupplier = new TreeMap<UUID, List<NewLine>>();
            var cost = new HashMap<String, BigDecimal>();
            products.forEach(p -> cost.put(p.sku(), p.cost() == null ? Money.ZERO : p.cost()));
            for (Suggestion g : out) {
                if (g.plan().suggestedQty() > 0) {
                    int qty = Math.toIntExact(Math.min(g.plan().suggestedQty(), Integer.MAX_VALUE));
                    bySupplier.computeIfAbsent(g.supplierId(), k -> new ArrayList<>())
                            .add(new NewLine(g.sku(), qty, cost.get(g.sku())));
                }
            }
            bySupplier.forEach((sid, lines) ->
                    raised.add(place(caller.subject(), store, raiseKey + ":" + sid, new NewOrder(sid, lines))));
        }
        return new Replenishment(store, today, cfg.historyDays(), out, raised);
    }
}
