package com.storesync.procurement.domain;

import java.util.List;

/**
 * Reorder-point replenishment for one SKU at one store.
 * <ul>
 *   <li>forecast daily demand d: exponentially weighted moving average of daily units sold, oldest day first</li>
 *   <li>sigma: sample standard deviation of the same daily series</li>
 *   <li>safety stock = z * sigma * sqrt(L), with L the supplier lead time in days</li>
 *   <li>reorder point = d * L + safety stock</li>
 *   <li>when on hand + on order is at or below the reorder point, order up to reorder point + d * review days</li>
 * </ul>
 * The normal approximation behind z is a simplification; slow movers with lumpy demand are not well served by it.
 */
public final class ReplenishmentPlanner {

    public record Plan(double dailyForecast, double sigma, long safetyStock, long reorderPoint, long orderUpTo,
                       long position, long suggestedQty) {
    }

    private final double alpha;
    private final double z;
    private final int reviewDays;

    public ReplenishmentPlanner(double alpha, double z, int reviewDays) {
        if (alpha <= 0 || alpha > 1 || z < 0 || reviewDays < 0) {
            throw new IllegalArgumentException("alpha in (0,1], z >= 0, reviewDays >= 0");
        }
        this.alpha = alpha;
        this.z = z;
        this.reviewDays = reviewDays;
    }

    public static double ewma(List<Long> daily, double alpha) {
        if (daily.isEmpty()) {
            return 0;
        }
        double f = daily.getFirst();
        for (int i = 1; i < daily.size(); i++) {
            f = alpha * daily.get(i) + (1 - alpha) * f;
        }
        return f;
    }

    public static double sampleStdDev(List<Long> daily) {
        int n = daily.size();
        if (n < 2) {
            return 0;
        }
        double mean = daily.stream().mapToLong(Long::longValue).average().orElse(0);
        double ss = daily.stream().mapToDouble(d -> (d - mean) * (d - mean)).sum();
        return Math.sqrt(ss / (n - 1));
    }

    public Plan plan(List<Long> dailyDemand, int leadTimeDays, long onHand, long onOrder) {
        if (leadTimeDays <= 0) {
            throw new IllegalArgumentException("lead time must be positive");
        }
        double d = ewma(dailyDemand, alpha);
        double sigma = sampleStdDev(dailyDemand);
        long safety = (long) Math.ceil(z * sigma * Math.sqrt(leadTimeDays) - 1e-9);
        long rop = (long) Math.ceil(d * leadTimeDays - 1e-9) + safety;
        long upTo = rop + (long) Math.ceil(d * reviewDays - 1e-9);
        long position = onHand + onOrder;
        long qty = d > 0 && position <= rop ? Math.max(0, upTo - position) : 0;
        return new Plan(d, sigma, safety, rop, upTo, position, qty);
    }
}
