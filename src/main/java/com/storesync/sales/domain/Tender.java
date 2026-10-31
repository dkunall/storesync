package com.storesync.sales.domain;

import com.storesync.shared.domain.DomainRuleException;
import com.storesync.shared.domain.Money;

import java.math.BigDecimal;
import java.util.List;

/** Split payment rules: only cash can be over-tendered, and the change handed back comes out of the cash. */
public final class Tender {

    public record Payment(PaymentMethod method, BigDecimal amount, String reference) {
        public Payment {
            amount = Money.exact(amount, "payment amount");
            if (amount.signum() <= 0) {
                throw new DomainRuleException("bad-payment", "Payment amounts must be positive");
            }
            if (method.needsReference() && (reference == null || reference.isBlank())) {
                throw new DomainRuleException("payment-reference-required", method + " needs a reference");
            }
        }
    }

    private Tender() {
    }

    /** @return the change due to the customer */
    public static BigDecimal settle(BigDecimal total, List<Payment> payments) {
        BigDecimal nonCash = Money.ZERO;
        BigDecimal cash = Money.ZERO;
        for (Payment p : payments) {
            if (p.method() == PaymentMethod.CASH) {
                cash = cash.add(p.amount());
            } else {
                nonCash = nonCash.add(p.amount());
            }
        }
        if (nonCash.compareTo(total) > 0) {
            throw new DomainRuleException("non-cash-overpaid", "Card, UPI and credit notes cannot exceed the total "
                    + total + "; got " + nonCash);
        }
        BigDecimal change = nonCash.add(cash).subtract(total);
        if (change.signum() < 0) {
            throw new DomainRuleException("underpaid", "Payments " + nonCash.add(cash) + " do not cover " + total);
        }
        return change;
    }
}
