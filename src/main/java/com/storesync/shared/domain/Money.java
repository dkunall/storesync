package com.storesync.shared.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Rupee amounts are BigDecimal with two decimals, rounded half-up wherever a division happens. */
public final class Money {

    public static final BigDecimal ZERO = new BigDecimal("0.00");
    public static final BigDecimal HUNDRED = new BigDecimal("100");

    private Money() {
    }

    public static BigDecimal of(String s) {
        return round(new BigDecimal(s));
    }

    public static BigDecimal round(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /** Rejects more than two decimals instead of silently rounding what a client sent. */
    public static BigDecimal exact(BigDecimal v, String field) {
        if (v == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (v.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(field + " has more than two decimals");
        }
        return v.setScale(2, RoundingMode.UNNECESSARY);
    }

    public static BigDecimal percentOf(BigDecimal amount, BigDecimal percent) {
        return round(amount.multiply(percent).divide(HUNDRED, 6, RoundingMode.HALF_UP));
    }
}
