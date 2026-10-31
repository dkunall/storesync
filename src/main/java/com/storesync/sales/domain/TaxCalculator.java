package com.storesync.sales.domain;

import com.storesync.shared.domain.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Splits a tax-inclusive (MRP-style) amount into taxable value and GST. Shelf prices include tax, so the customer
 * pays exactly the shelf price minus discounts and the tax is backed out of it:
 * {@code taxable = amount * 100 / (100 + rate)}, rounded half-up to the paisa; tax is the remainder.
 * The rates and rounding here are illustrative, not tax advice.
 */
public sealed interface TaxCalculator permits TaxCalculator.IntraState, TaxCalculator.InterState {

    LineTax split(BigDecimal inclusiveAmount, BigDecimal ratePercent);

    /** Same-state supply: CGST and SGST each carry half the tax; an odd paisa goes to CGST. */
    TaxCalculator INTRA_STATE = new IntraState();

    /** Supply to another state: all of it is IGST. */
    TaxCalculator INTER_STATE = new InterState();

    /**
     * @param storeState     two-digit GST state code of the selling store
     * @param placeOfSupply  the buyer's state code (first two digits of their GSTIN), or null for walk-in customers
     */
    static TaxCalculator forSupply(String storeState, String placeOfSupply) {
        return placeOfSupply == null || placeOfSupply.equals(storeState) ? INTRA_STATE : INTER_STATE;
    }

    private static BigDecimal taxable(BigDecimal amount, BigDecimal rate) {
        if (amount.signum() < 0 || rate.signum() < 0) {
            throw new IllegalArgumentException("amount and rate must not be negative");
        }
        return amount.multiply(Money.HUNDRED).divide(Money.HUNDRED.add(rate), 2, RoundingMode.HALF_UP);
    }

    final class IntraState implements TaxCalculator {
        private IntraState() {
        }

        @Override
        public LineTax split(BigDecimal amount, BigDecimal rate) {
            BigDecimal taxable = taxable(amount, rate);
            BigDecimal tax = Money.round(amount).subtract(taxable);
            BigDecimal cgst = tax.divide(BigDecimal.TWO, 2, RoundingMode.UP);
            return new LineTax(taxable, cgst, tax.subtract(cgst), Money.ZERO);
        }
    }

    final class InterState implements TaxCalculator {
        private InterState() {
        }

        @Override
        public LineTax split(BigDecimal amount, BigDecimal rate) {
            BigDecimal taxable = taxable(amount, rate);
            return new LineTax(taxable, Money.ZERO, Money.ZERO, Money.round(amount).subtract(taxable));
        }
    }
}
