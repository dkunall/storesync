package com.storesync.sales.domain;

import com.storesync.shared.domain.DomainRuleException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenderTest {

    static Tender.Payment pay(PaymentMethod m, String amount) {
        return new Tender.Payment(m, new BigDecimal(amount), m.needsReference() ? "REF-1" : null);
    }

    @Test
    void splitPaymentWithChangeFromCash() {
        var change = Tender.settle(new BigDecimal("730.00"),
                List.of(pay(PaymentMethod.UPI, "500.00"), pay(PaymentMethod.CASH, "300.00")));
        assertThat(change).isEqualByComparingTo("70.00");
    }

    @Test
    void exactNonCashPaymentHasNoChange() {
        assertThat(Tender.settle(new BigDecimal("99.50"), List.of(pay(PaymentMethod.CARD, "99.50"))))
                .isEqualByComparingTo("0");
    }

    @Test
    void refusesUnderpaymentAndOverpaidCard() {
        assertThatThrownBy(() -> Tender.settle(new BigDecimal("100.00"), List.of(pay(PaymentMethod.CASH, "99.99"))))
                .isInstanceOf(DomainRuleException.class).hasMessageContaining("do not cover");
        assertThatThrownBy(() -> Tender.settle(new BigDecimal("100.00"),
                List.of(pay(PaymentMethod.CARD, "150.00"))))
                .isInstanceOf(DomainRuleException.class).hasMessageContaining("cannot exceed");
    }

    @Test
    void cardAndUpiNeedReferencesAndAmountsAreExact() {
        assertThatThrownBy(() -> new Tender.Payment(PaymentMethod.UPI, new BigDecimal("10"), " "))
                .hasMessageContaining("needs a reference");
        assertThatThrownBy(() -> new Tender.Payment(PaymentMethod.CASH, new BigDecimal("10.001"), null))
                .hasMessageContaining("two decimals");
        assertThatThrownBy(() -> new Tender.Payment(PaymentMethod.CASH, new BigDecimal("0"), null))
                .hasMessageContaining("positive");
    }
}
