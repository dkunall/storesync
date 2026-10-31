package com.storesync.inventory.application;

import com.storesync.catalog.application.CatalogService;
import com.storesync.catalog.domain.Product;
import com.storesync.inventory.application.StockRepository.StockCount;
import com.storesync.inventory.application.StockRepository.StockState;
import com.storesync.inventory.application.StockRepository.Transfer;
import com.storesync.inventory.domain.InsufficientStockException;
import com.storesync.inventory.domain.StockPolicy;
import com.storesync.outbox.application.Outbox;
import com.storesync.shared.domain.DomainRuleException;
import com.storesync.shared.error.ApiException;
import com.storesync.shared.security.Caller;
import com.storesync.shared.security.Role;
import com.storesync.store.application.StoreService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Stock per store and SKU, kept as a PN-counter with one node per writer (each terminal, plus "hq" for changes made
 * on the server). Every change locks the store/SKU header row first, in SKU order, so concurrent sales of the same
 * item serialise and cannot both take the last unit, and two baskets never deadlock on each other.
 */
@Service
public class InventoryService {

    /** Node for changes the server makes itself: online sales, receipts, transfers, counts, returns. */
    public static final String HQ = "hq";

    public record Move(String sku, long delta) {
    }

    public record Change(String sku, long before, long after) {
    }

    private final StockRepository repo;
    private final CatalogService catalog;
    private final StoreService stores;
    private final Outbox outbox;
    private final Clock clock;
    private final MeterRegistry meters;

    InventoryService(StockRepository repo, CatalogService catalog, StoreService stores, Outbox outbox, Clock clock,
                     MeterRegistry meters) {
        this.repo = repo;
        this.catalog = catalog;
        this.stores = stores;
        this.outbox = outbox;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * Applies stock moves for one store atomically. With {@link StockPolicy#REFUSE_NEGATIVE} any move that would take
     * a SKU below zero fails the whole call with 409 insufficient-stock. {@link StockPolicy#ALLOW_NEGATIVE} is for
     * sales a terminal already made while offline: they happened, so they are recorded, and the event lets the alert
     * consumer flag the negative balance.
     */
    @Transactional
    public List<Change> apply(String store, String node, List<Move> moves, StockPolicy policy, String cause, String ref) {
        var bySku = new TreeMap<String, Long>();
        for (Move m : moves) {
            if (m.delta() != 0) {
                bySku.merge(m.sku(), m.delta(), Math::addExact);
            }
        }
        bySku.values().removeIf(v -> v == 0);
        if (bySku.isEmpty()) {
            return List.of();
        }
        Map<String, Product> products = catalog.find(bySku.keySet());
        for (String sku : bySku.keySet()) {
            if (!products.containsKey(sku)) {
                throw new DomainRuleException("unknown-sku", "No product " + sku);
            }
        }
        Map<String, Long> onHand = repo.lock(store, List.copyOf(bySku.keySet()));
        if (policy == StockPolicy.REFUSE_NEGATIVE) {
            for (var e : bySku.entrySet()) {
                long have = onHand.get(e.getKey());
                if (e.getValue() < 0 && have + e.getValue() < 0) {
                    meters.counter("storesync.stock.refused", "cause", cause).increment();
                    throw new InsufficientStockException(store, e.getKey(), have, -e.getValue());
                }
            }
        }
        var now = clock.instant();
        var changes = new ArrayList<Change>();
        for (var e : bySku.entrySet()) {
            String sku = e.getKey();
            long delta = e.getValue();
            long before = onHand.get(sku);
            long after = Math.addExact(before, delta);
            repo.add(store, sku, node, Math.max(delta, 0), Math.max(-delta, 0), after);
            repo.movement(store, sku, node, delta, after, cause, ref, now);
            var data = new LinkedHashMap<String, Object>();
            data.put("store", store);
            data.put("sku", sku);
            data.put("before", before);
            data.put("after", after);
            data.put("delta", delta);
            data.put("cause", cause);
            data.put("ref", ref);
            data.put("lowStockLevel", products.get(sku).lowStockLevel());
            outbox.append(Outbox.STOCK, store + ":" + sku, "StockChanged", data);
            changes.add(new Change(sku, before, after));
        }
        return changes;
    }

    public List<StockState> stock(Caller caller, String store) {
        caller.requireStore(store, Role.MANAGER, Role.CASHIER, Role.TERMINAL);
        stores.store(store);
        return repo.stock(store, List.of());
    }

    public StockState item(Caller caller, String store, String sku) {
        caller.requireStore(store, Role.MANAGER, Role.CASHIER, Role.TERMINAL);
        return repo.stock(store, List.of(sku)).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("stock of " + sku + " in " + store));
    }

    public Map<String, Long> onHand(String store, List<String> skus) {
        var out = new HashMap<String, Long>();
        repo.stock(store, skus).forEach(s -> out.put(s.sku(), s.onHand()));
        return out;
    }

    public List<StockState> changedSince(String store, long cursor) {
        return repo.changedSince(store, cursor);
    }

    /**
     * A shelf count replaces the book figure with what was counted. The difference is written as a hq move, so it
     * merges with terminal counters like any other change. Retrying the same count id is a no-op.
     */
    @Transactional
    public StockCount count(Caller caller, String store, UUID id, String sku, long counted) {
        caller.requireStore(store, Role.MANAGER);
        if (counted < 0) {
            throw ApiException.invalid("counted cannot be negative");
        }
        var existing = repo.count(id);
        if (existing.isPresent()) {
            var c = existing.get();
            if (!c.store().equals(store) || !c.sku().equals(sku) || c.counted() != counted) {
                throw ApiException.unprocessable("id-reused", "That count id was used for a different count");
            }
            return c;
        }
        stores.store(store);
        if (catalog.find(List.of(sku)).isEmpty()) {
            throw new DomainRuleException("unknown-sku", "No product " + sku);
        }
        long book = repo.lock(store, List.of(sku)).get(sku);
        apply(store, HQ, List.of(new Move(sku, counted - book)), StockPolicy.ALLOW_NEGATIVE, "COUNT", id.toString());
        var c = new StockCount(id, store, sku, book, counted, caller.subject(), clock.instant());
        repo.insertCount(c);
        return c;
    }

    @Transactional
    public Transfer dispatch(Caller caller, UUID id, String from, String to, Map<String, Integer> lines) {
        caller.requireStore(from, Role.MANAGER);
        if (from.equals(to)) {
            throw ApiException.invalid("A transfer needs two different stores");
        }
        if (lines.isEmpty() || lines.values().stream().anyMatch(q -> q == null || q <= 0)) {
            throw ApiException.invalid("Transfer lines need positive quantities");
        }
        var existing = repo.transfer(id, false);
        if (existing.isPresent()) {
            var t = existing.get();
            if (!t.fromStore().equals(from) || !t.toStore().equals(to) || !t.qty().equals(new TreeMap<>(lines))) {
                throw ApiException.unprocessable("id-reused", "That transfer id was used for a different transfer");
            }
            return t;
        }
        stores.store(from);
        stores.store(to);
        var moves = lines.entrySet().stream().map(e -> new Move(e.getKey(), -e.getValue())).toList();
        apply(from, HQ, moves, StockPolicy.REFUSE_NEGATIVE, "TRANSFER_OUT", id.toString());
        var t = new Transfer(id, from, to, "IN_TRANSIT", caller.subject(), clock.instant(), null,
                new TreeMap<>(lines), Map.of());
        repo.insertTransfer(t);
        return t;
    }

    /** Receiving is idempotent: once a transfer is RECEIVED, receiving it again returns it unchanged. */
    @Transactional
    public Transfer receive(Caller caller, UUID id, Map<String, Integer> received) {
        Transfer t = repo.transfer(id, true).orElseThrow(() -> ApiException.notFound("transfer"));
        caller.requireStore(t.toStore(), Role.MANAGER);
        if (t.status().equals("RECEIVED")) {
            return t;
        }
        var got = new TreeMap<String, Integer>();
        for (var e : t.qty().entrySet()) {
            int r = Objects.requireNonNullElse(received.get(e.getKey()), e.getValue());
            if (r < 0 || r > e.getValue()) {
                throw ApiException.unprocessable("bad-receipt", e.getKey() + ": received " + r + " of " + e.getValue() + " sent");
            }
            got.put(e.getKey(), r);
        }
        for (String sku : received.keySet()) {
            if (!t.qty().containsKey(sku)) {
                throw ApiException.unprocessable("not-on-transfer", sku + " was not on this transfer");
            }
        }
        apply(t.toStore(), HQ, got.entrySet().stream().map(e -> new Move(e.getKey(), e.getValue())).toList(),
                StockPolicy.ALLOW_NEGATIVE, "TRANSFER_IN", id.toString());
        for (var e : got.entrySet()) {
            int shortBy = t.qty().get(e.getKey()) - e.getValue();
            if (shortBy > 0) {
                outbox.append(Outbox.STOCK, t.toStore() + ":" + e.getKey(), "TransferShortage", Map.of(
                        "store", t.toStore(), "sku", e.getKey(), "transfer", id.toString(), "short", shortBy));
            }
        }
        repo.receiveTransfer(id, got, clock.instant());
        return repo.transfer(id, false).orElseThrow();
    }

    public Transfer transfer(Caller caller, UUID id) {
        Transfer t = repo.transfer(id, false).orElseThrow(() -> ApiException.notFound("transfer"));
        if (!caller.has(Role.ADMIN) && !t.fromStore().equals(caller.storeCode()) && !t.toStore().equals(caller.storeCode())) {
            throw ApiException.forbidden("other-store", "That transfer is not for your store");
        }
        return t;
    }
}
