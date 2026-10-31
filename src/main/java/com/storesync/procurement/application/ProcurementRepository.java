package com.storesync.procurement.application;

import com.storesync.procurement.domain.PoStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ProcurementRepository {

    record Supplier(UUID id, String name, int leadTimeDays) {
    }

    record PoLine(String sku, int qty, BigDecimal unitCost, int received) {
    }

    record PurchaseOrder(UUID id, String store, UUID supplierId, PoStatus status, String idempotencyKey,
                         String createdBy, Instant createdAt, List<PoLine> lines) {
    }

    record Receipt(UUID id, UUID poId, String ref, String receivedBy, Instant receivedAt, Map<String, Integer> lines) {
    }

    /** @return false if the id already exists */
    boolean insertSupplier(Supplier s);

    Optional<Supplier> supplier(UUID id);

    List<Supplier> suppliers();

    /** @return false if another order already holds this idempotency key */
    boolean insertOrder(PurchaseOrder po);

    Optional<PurchaseOrder> order(UUID id, boolean lock);

    Optional<PurchaseOrder> orderByKey(String idempotencyKey);

    /** Orders of a store whose idempotency key starts with {@code prefix}, oldest first. */
    List<PurchaseOrder> ordersByKeyPrefix(String store, String prefix);

    /** Transaction-scoped lock serialising callers that share one replenishment raise key. */
    void lockRaiseKey(String key);

    List<PurchaseOrder> orders(String store);

    Optional<Receipt> receipt(UUID poId, String ref);

    void insertReceipt(Receipt r);

    void updateReceived(UUID poId, PoStatus status, Map<String, Integer> receivedNow);

    /** Ordered but not yet received, per SKU, over the store's open orders. */
    Map<String, Long> onOrder(String store);
}
