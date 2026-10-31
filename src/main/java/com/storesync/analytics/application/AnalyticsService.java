package com.storesync.analytics.application;

import com.storesync.outbox.application.EventEnvelope;
import com.storesync.outbox.application.ProcessedEvents;
import com.storesync.shared.config.StoreSyncProperties;
import com.storesync.shared.error.ApiException;
import com.storesync.shared.security.Caller;
import com.storesync.shared.security.Role;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read side fed from the outbox through Kafka. Each consumer records the event id in the same transaction as its
 * effect, so a redelivered event changes nothing. Figures here lag the till by the relay and consumer delay; the
 * shift reconciliation reads the sales tables directly and is the one to use for cash.
 */
@Service
public class AnalyticsService {

    public static final String ROLLUP = "storesync-analytics";
    public static final String ALERTS = "storesync-alerts";

    public record SalesReport(String store, LocalDate from, LocalDate to, List<AnalyticsRepository.StoreDay> days,
                              List<AnalyticsRepository.SkuTotal> skus) {
    }

    private final AnalyticsRepository repo;
    private final ProcessedEvents processed;
    private final StoreSyncProperties props;
    private final Clock clock;
    private final MeterRegistry meters;

    AnalyticsService(AnalyticsRepository repo, ProcessedEvents processed, StoreSyncProperties props, Clock clock,
                     MeterRegistry meters) {
        this.repo = repo;
        this.processed = processed;
        this.props = props;
        this.clock = clock;
        this.meters = meters;
    }

    @Transactional
    public void rollup(EventEnvelope e) {
        if (!processed.markProcessed(ROLLUP, e.eventId())) {
            meters.counter("storesync.events.duplicate", "consumer", ROLLUP).increment();
            return;
        }
        var d = e.data();
        switch (e.type()) {
            case "SaleCompleted" -> {
                String store = str(d, "store");
                LocalDate day = LocalDate.parse(str(d, "day"));
                for (Map<String, Object> l : lines(d)) {
                    repo.addSkuDay(store, day, str(l, "sku"), num(l, "qty").longValue(), num(l, "net"), num(l, "tax"));
                }
                repo.addStoreDay(store, day, 1, 0, num(d, "total"));
            }
            case "SaleReturned" -> {
                String store = str(d, "store");
                LocalDate day = LocalDate.parse(str(d, "day"));
                for (Map<String, Object> l : lines(d)) {
                    repo.addSkuDay(store, day, str(l, "sku"), -num(l, "qty").longValue(), num(l, "net").negate(),
                            num(l, "tax").negate());
                }
                repo.addStoreDay(store, day, 0, 1, num(d, "amount").negate());
            }
            default -> {
                return;
            }
        }
        meters.counter("storesync.events.applied", "consumer", ROLLUP, "type", e.type()).increment();
    }

    @Transactional
    public void alert(EventEnvelope e) {
        if (!processed.markProcessed(ALERTS, e.eventId())) {
            meters.counter("storesync.events.duplicate", "consumer", ALERTS).increment();
            return;
        }
        var d = e.data();
        String store = str(d, "store");
        switch (e.type()) {
            case "StockChanged" -> {
                String sku = str(d, "sku");
                long before = num(d, "before").longValue();
                long after = num(d, "after").longValue();
                long low = num(d, "lowStockLevel").longValue();
                if (before >= 0 && after < 0) {
                    raise(e, store, sku, "NEGATIVE_STOCK", "On hand went from " + before + " to " + after
                            + " (" + d.get("cause") + " " + d.get("ref") + ")");
                } else if (after >= 0 && after <= low && before > low) {
                    raise(e, store, sku, "LOW_STOCK", "On hand " + after + " is at or below " + low);
                }
                long delta = num(d, "delta").longValue();
                if ("COUNT".equals(d.get("cause")) && -delta >= props.alerts().shrinkageUnits()) {
                    raise(e, store, sku, "SHRINKAGE", "Count found " + (-delta) + " fewer than the book");
                }
            }
            case "TransferShortage" -> raise(e, store, str(d, "sku"), "SHRINKAGE",
                    d.get("short") + " short on transfer " + d.get("transfer"));
            case "ShiftClosed" -> {
                BigDecimal variance = num(d, "variance");
                if (variance.abs().compareTo(props.alerts().cashVariance()) >= 0) {
                    raise(e, store, null, "CASH_VARIANCE", "Shift " + d.get("shiftId") + " by " + d.get("cashier")
                            + ": expected " + d.get("expectedCash") + ", counted " + d.get("countedCash")
                            + ", variance " + variance.toPlainString());
                }
            }
            case "OpRejected" -> raise(e, store, null, "SYNC_REJECTED", "Terminal " + d.get("terminalId") + " seq "
                    + d.get("seq") + " " + d.get("type") + ": " + d.get("code") + " - " + d.get("message"));
            default -> {
            }
        }
    }

    private void raise(EventEnvelope e, String store, String sku, String type, String detail) {
        if (repo.insertAlert(store, sku, type, detail, e.eventId(), clock.instant())) {
            meters.counter("storesync.alerts", "type", type).increment();
        }
    }

    @Transactional(readOnly = true)
    public SalesReport sales(Caller caller, String store, LocalDate from, LocalDate to) {
        caller.requireStore(store, Role.MANAGER);
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 366) {
            throw ApiException.invalid("from <= to, at most 366 days apart");
        }
        return new SalesReport(store, from, to, repo.days(store, from, to), repo.skus(store, from, to));
    }

    @Transactional(readOnly = true)
    public List<AnalyticsRepository.Alert> alerts(Caller caller, String store, int limit) {
        caller.requireStore(store, Role.MANAGER);
        return repo.alerts(store, Math.max(1, Math.min(limit, 500)));
    }

    /** Units sold per day for the {@code days} days ending yesterday, oldest first, zero-filled. */
    @Transactional(readOnly = true)
    public List<Long> dailyDemand(String store, String sku, LocalDate today, int days) {
        LocalDate from = today.minusDays(days);
        LocalDate to = today.minusDays(1);
        var sold = repo.unitsByDay(store, sku, from, to);
        var out = new ArrayList<Long>(days);
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            out.add(Math.max(0, sold.getOrDefault(d, 0L)));
        }
        return out;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) {
            throw new IllegalArgumentException("event field " + k + " missing");
        }
        return v.toString();
    }

    private static BigDecimal num(Map<String, Object> m, String k) {
        return new BigDecimal(str(m, k));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lines(Map<String, Object> d) {
        Object v = d.get("lines");
        return v instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }
}
