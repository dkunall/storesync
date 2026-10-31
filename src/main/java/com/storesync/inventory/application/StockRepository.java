package com.storesync.inventory.application;

import com.storesync.inventory.domain.PnCounter;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface StockRepository {

    record StockState(String sku, long onHand, PnCounter counter) {
    }

    record Transfer(UUID id, String fromStore, String toStore, String status, String createdBy, Instant createdAt,
                    Instant receivedAt, Map<String, Integer> qty, Map<String, Integer> received) {
    }

    record StockCount(UUID id, String store, String sku, long book, long counted, String by, Instant at) {
    }

    /** Creates missing stock rows at zero, then locks all of them in SKU order. @return on-hand by SKU */
    Map<String, Long> lock(String store, List<String> sortedSkus);

    void add(String store, String sku, String node, long inc, long dec, long newOnHand);

    void movement(String store, String sku, String node, long delta, long onHand, String cause, String ref, Instant at);

    List<StockState> stock(String store, Collection<String> onlySkus);

    List<StockState> changedSince(String store, long cursor);

    Optional<Transfer> transfer(UUID id, boolean lock);

    void insertTransfer(Transfer t);

    void receiveTransfer(UUID id, Map<String, Integer> received, Instant at);

    Optional<StockCount> count(UUID id);

    void insertCount(StockCount c);

    /** Transaction-scoped lock so retries of one transfer id wait for the first instead of colliding on insert. */
    void lockTransferId(UUID id);
}
