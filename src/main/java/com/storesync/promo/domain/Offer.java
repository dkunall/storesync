package com.storesync.promo.domain;

import com.storesync.shared.domain.Money;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * A promotion the engine can apply. Item offers consume units, and a unit can be used by at most one item offer.
 * At most one basket offer applies, after item offers, on what the basket costs by then. A non-stackable offer
 * never combines with any other offer.
 */
public sealed interface Offer permits Offer.PercentOff, Offer.BuyXGetY, Offer.BundlePrice, Offer.BasketOff {

    String code();

    int priority();

    boolean stackable();

    default boolean basketLevel() {
        return false;
    }

    /** {@code percent} off each unit of the listed SKUs. One application is one unit. */
    record PercentOff(String code, int priority, boolean stackable, Set<String> skus, BigDecimal percent) implements Offer {
        public PercentOff {
            skus = Set.copyOf(skus);
            if (percent.signum() <= 0 || percent.compareTo(Money.HUNDRED) > 0 || skus.isEmpty()) {
                throw new IllegalArgumentException(code + ": percent must be in (0, 100] and skus non-empty");
            }
        }
    }

    /** Buy {@code buy}, get {@code free} free, across the listed SKUs; the cheapest units of each group are free. */
    record BuyXGetY(String code, int priority, boolean stackable, Set<String> skus, int buy, int free) implements Offer {
        public BuyXGetY {
            skus = Set.copyOf(skus);
            if (buy < 1 || free < 1 || skus.isEmpty()) {
                throw new IllegalArgumentException(code + ": buy and free must be at least 1");
            }
        }
    }

    /** One unit of each listed SKU together for {@code price}. */
    record BundlePrice(String code, int priority, boolean stackable, List<String> skus, BigDecimal price) implements Offer {
        public BundlePrice {
            skus = List.copyOf(skus);
            if (Set.copyOf(skus).size() != skus.size() || skus.size() < 2 || price.signum() <= 0) {
                throw new IllegalArgumentException(code + ": a bundle needs two or more distinct skus and a positive price");
            }
        }
    }

    /** Off the whole basket once it reaches {@code minSpend}: either {@code percent} (capped) or a {@code flat} amount. */
    record BasketOff(String code, int priority, boolean stackable, BigDecimal minSpend, BigDecimal percent,
                     BigDecimal flat, BigDecimal cap) implements Offer {
        public BasketOff {
            if ((percent == null) == (flat == null)) {
                throw new IllegalArgumentException(code + ": exactly one of percent and flat");
            }
            if (percent != null && (percent.signum() <= 0 || percent.compareTo(Money.HUNDRED) > 0)) {
                throw new IllegalArgumentException(code + ": percent must be in (0, 100]");
            }
            if (flat != null && flat.signum() <= 0) {
                throw new IllegalArgumentException(code + ": flat must be positive");
            }
        }

        @Override
        public boolean basketLevel() {
            return true;
        }
    }
}
