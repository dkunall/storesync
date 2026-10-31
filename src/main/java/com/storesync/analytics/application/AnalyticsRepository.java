package com.storesync.analytics.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AnalyticsRepository {

    record Alert(long id, String store, String sku, String type, String detail, UUID eventId, Instant createdAt) {
    }

    record StoreDay(LocalDate day, long invoices, long returns, BigDecimal total) {
    }

    record SkuTotal(String sku, long qty, BigDecimal net, BigDecimal tax) {
    }

    void addSkuDay(String store, LocalDate day, String sku, long qty, BigDecimal net, BigDecimal tax);

    void addStoreDay(String store, LocalDate day, long invoices, long returns, BigDecimal total);

    /** @return false when this event already raised an alert of this type */
    boolean insertAlert(String store, String sku, String type, String detail, UUID eventId, Instant at);

    List<Alert> alerts(String store, int limit);

    List<StoreDay> days(String store, LocalDate from, LocalDate to);

    List<SkuTotal> skus(String store, LocalDate from, LocalDate to);

    /** Units sold per day, net of returns; days with no sales are absent. */
    Map<LocalDate, Long> unitsByDay(String store, String sku, LocalDate from, LocalDate to);
}
