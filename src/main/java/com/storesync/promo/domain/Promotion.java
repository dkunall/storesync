package com.storesync.promo.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** A stored promotion: an {@link Offer} plus when it runs. */
public record Promotion(UUID id, String code, Type type, Rules rules, int priority, boolean stackable,
                        Instant startsAt, Instant endsAt, boolean active) {

    public enum Type { PERCENT_OFF, BUY_X_GET_Y, BUNDLE_PRICE, BASKET_OFF }

    /** The union of every type's parameters; each type reads the ones it needs. */
    public record Rules(List<String> skus, BigDecimal percent, Integer buy, Integer free, BigDecimal price,
                        BigDecimal minSpend, BigDecimal flat, BigDecimal cap) {
    }

    public Promotion {
        if (code == null || !code.matches("[A-Z0-9_-]{2,32}")) {
            throw new IllegalArgumentException("promotion code must be 2-32 of A-Z, 0-9, _ or -");
        }
        if (startsAt != null && endsAt != null && !endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("endsAt must be after startsAt");
        }
        offer(code, type, rules, priority, stackable);
    }

    public boolean runsAt(Instant t) {
        return active && (startsAt == null || !t.isBefore(startsAt)) && (endsAt == null || t.isBefore(endsAt));
    }

    public Offer toOffer() {
        return offer(code, type, rules, priority, stackable);
    }

    private static Offer offer(String code, Type type, Rules rules, int priority, boolean stackable) {
        if (type == null || rules == null) {
            throw new IllegalArgumentException("type and rules are required");
        }
        List<String> skus = rules.skus() == null ? List.of() : rules.skus();
        return switch (type) {
            case PERCENT_OFF -> new Offer.PercentOff(code, priority, stackable, Set.copyOf(skus), req(rules.percent(), "percent"));
            case BUY_X_GET_Y -> new Offer.BuyXGetY(code, priority, stackable, Set.copyOf(skus),
                    req(rules.buy(), "buy"), req(rules.free(), "free"));
            case BUNDLE_PRICE -> new Offer.BundlePrice(code, priority, stackable, skus, req(rules.price(), "price"));
            case BASKET_OFF -> new Offer.BasketOff(code, priority, stackable,
                    rules.minSpend() == null ? BigDecimal.ZERO : rules.minSpend(), rules.percent(), rules.flat(), rules.cap());
        };
    }

    private static <T> T req(T v, String name) {
        if (v == null) {
            throw new IllegalArgumentException(name + " is required for this promotion type");
        }
        return v;
    }
}
