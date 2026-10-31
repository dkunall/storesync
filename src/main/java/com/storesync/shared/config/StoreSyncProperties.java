package com.storesync.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

@ConfigurationProperties("storesync")
public record StoreSyncProperties(Security security, Sales sales, Sync sync, Invoices invoices,
                                  Replenishment replenishment, Alerts alerts) {

    /** Head-office subjects. Store staff and terminals live in the database instead. */
    public record Security(List<String> adminSubjects) {
    }

    /** Manual line discounts: a cashier may give up to the first limit, a manager up to the second. */
    public record Sales(BigDecimal cashierMaxDiscountPercent, BigDecimal managerMaxDiscountPercent) {
    }

    /** @param opVersions payload versions the server still accepts (current and previous) */
    public record Sync(int batchMax, List<Integer> opVersions) {
    }

    /** Invoice numbers are handed to terminals in blocks of this size. */
    public record Invoices(int blockSize) {
    }

    /**
     * @param alpha       EWMA smoothing for daily demand
     * @param historyDays days of sales history fed to the forecast
     * @param serviceZ    safety factor (1.65 is roughly a 95% cycle service level under a normal approximation)
     * @param reviewDays  days of demand an order should cover on top of the reorder point
     */
    public record Replenishment(double alpha, int historyDays, double serviceZ, int reviewDays) {
    }

    /** @param shrinkageUnits a count this far below the book raises a SHRINKAGE alert */
    public record Alerts(long shrinkageUnits, BigDecimal cashVariance) {
    }
}
