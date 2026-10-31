package com.storesync.sales.domain;

import java.math.BigDecimal;

/** Expected cash in a drawer at close, and how far the count is off. Positive variance means extra cash. */
public record CashDrawer(BigDecimal openingFloat, BigDecimal cashTendered, BigDecimal changeGiven,
                         BigDecimal cashRefunds) {

    public BigDecimal expected() {
        return openingFloat.add(cashTendered).subtract(changeGiven).subtract(cashRefunds);
    }

    public BigDecimal variance(BigDecimal counted) {
        return counted.subtract(expected());
    }
}
