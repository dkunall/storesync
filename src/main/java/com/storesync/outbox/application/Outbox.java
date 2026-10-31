package com.storesync.outbox.application;

import java.util.Map;

/**
 * Port for events that must leave the process exactly when the state change commits. Implementations write to
 * the outbox table in the caller's transaction; a relay publishes them afterwards.
 */
public interface Outbox {

    /** SaleCompleted, SaleReturned, ShiftClosed, OpRejected; keyed by store so one store's events stay ordered. */
    String SALES = "storesync.sales";

    /** StockChanged, TransferShortage; keyed by store and SKU. */
    String STOCK = "storesync.stock";

    void append(String topic, String key, String type, Map<String, Object> data);
}
