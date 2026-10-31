package com.storesync.sales.domain;

import com.storesync.shared.domain.DomainRuleException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SaleCalculatorTest {

    static SaleCalculator.LineInput line(int no, int qty, String price, String rate, String promo, String manual) {
        return new SaleCalculator.LineInput(no, "SKU" + no, qty, new BigDecimal(price), new BigDecimal(rate),
                new BigDecimal(promo), new BigDecimal(manual));
    }

    @Test
    void pricesABasketWithDiscountsAndTax() {
        var sale = SaleCalculator.price(List.of(
                line(1, 2, "59.00", "18", "11.80", "0"),
                line(2, 1, "105.00", "5", "0", "5.25")), TaxCalculator.INTRA_STATE);
        assertThat(sale.gross()).isEqualByComparingTo("223.00");
        assertThat(sale.discount()).isEqualByComparingTo("17.05");
        assertThat(sale.total()).isEqualByComparingTo("205.95");
        assertThat(sale.taxable().add(sale.tax())).isEqualByComparingTo(sale.total());
        // line 1: 106.20 incl. 18% -> taxable 90.00
        assertThat(sale.lines().getFirst().tax().taxable()).isEqualByComparingTo("90.00");
    }

    @Test
    void refusesDiscountBeyondPriceAndEmptySales() {
        assertThatThrownBy(() -> SaleCalculator.price(List.of(line(1, 1, "10.00", "5", "6.00", "5.00")),
                TaxCalculator.INTRA_STATE)).isInstanceOf(DomainRuleException.class);
        assertThatThrownBy(() -> SaleCalculator.price(List.of(), TaxCalculator.INTRA_STATE))
                .isInstanceOf(DomainRuleException.class);
    }

    @Test
    void partialReturnsRefundExactlyWhatWasCharged() {
        var rnd = new Random(3);
        for (int i = 0; i < 2000; i++) {
            int sold = 1 + rnd.nextInt(12);
            BigDecimal amount = BigDecimal.valueOf(rnd.nextInt(500_000), 2);
            int returned = 0;
            BigDecimal refunded = BigDecimal.ZERO.setScale(2);
            while (returned < sold) {
                int k = 1 + rnd.nextInt(sold - returned);
                BigDecimal r = Refunds.prorate(amount, sold, returned, refunded, k);
                assertThat(r.signum()).isGreaterThanOrEqualTo(0);
                refunded = refunded.add(r);
                returned += k;
            }
            assertThat(refunded).isEqualByComparingTo(amount);
        }
    }

    @Test
    void cannotReturnMoreThanSold() {
        assertThatThrownBy(() -> Refunds.prorate(new BigDecimal("10.00"), 2, 1, new BigDecimal("5.00"), 2))
                .isInstanceOf(DomainRuleException.class).hasMessageContaining("Only 1");
    }
}
