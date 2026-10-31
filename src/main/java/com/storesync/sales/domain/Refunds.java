package com.storesync.sales.domain;

import com.storesync.shared.domain.DomainRuleException;
import com.storesync.shared.domain.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pro-rata refunds for partial returns. Each part of a line (taxable value, each tax) is refunded in proportion
 * to the units returned, and the return that brings the line back to zero units refunds exactly what is left, so
 * a line returned in several goes never refunds a paisa more or less than was charged.
 */
public final class Refunds {

    private Refunds() {
    }

    /**
     * @param lineAmount     what the line charged for this component
     * @param soldQty        units sold on the line
     * @param returnedBefore units already returned
     * @param refundedBefore amount of this component already refunded
     * @param qty            units returned now
     */
    public static BigDecimal prorate(BigDecimal lineAmount, int soldQty, int returnedBefore, BigDecimal refundedBefore,
                                     int qty) {
        if (qty <= 0) {
            throw new DomainRuleException("bad-quantity", "Return quantity must be positive");
        }
        if (returnedBefore + qty > soldQty) {
            throw new DomainRuleException("return-exceeds-sold", "Only " + (soldQty - returnedBefore)
                    + " units are left to return on this line");
        }
        if (returnedBefore + qty == soldQty) {
            return lineAmount.subtract(refundedBefore);
        }
        return lineAmount.multiply(BigDecimal.valueOf(qty)).divide(BigDecimal.valueOf(soldQty), 2, RoundingMode.HALF_UP)
                .min(lineAmount.subtract(refundedBefore)).max(Money.ZERO);
    }
}
