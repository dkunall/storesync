package com.storesync.sales.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class TaxCalculatorTest {

    @Test
    void backsGstOutOfAnInclusivePrice() {
        LineTax t = TaxCalculator.INTRA_STATE.split(new BigDecimal("118.00"), new BigDecimal("18"));
        assertThat(t.taxable()).isEqualByComparingTo("100.00");
        assertThat(t.cgst()).isEqualByComparingTo("9.00");
        assertThat(t.sgst()).isEqualByComparingTo("9.00");
        assertThat(t.igst()).isEqualByComparingTo("0");
    }

    @Test
    void oddPaisaGoesToCgst() {
        LineTax t = TaxCalculator.INTRA_STATE.split(new BigDecimal("10.00"), new BigDecimal("5"));
        // 10 * 100 / 105 = 9.5238 -> 9.52, tax 0.48
        assertThat(t.taxable()).isEqualByComparingTo("9.52");
        assertThat(t.cgst()).isEqualByComparingTo("0.24");
        assertThat(t.sgst()).isEqualByComparingTo("0.24");
        LineTax odd = TaxCalculator.INTRA_STATE.split(new BigDecimal("99.00"), new BigDecimal("12"));
        // 99 * 100 / 112 = 88.392857 -> 88.39, tax 10.61
        assertThat(odd.cgst()).isEqualByComparingTo("5.31");
        assertThat(odd.sgst()).isEqualByComparingTo("5.30");
    }

    @Test
    void interStateSupplyIsAllIgst() {
        LineTax t = TaxCalculator.forSupply("29", "27").split(new BigDecimal("1280.00"), new BigDecimal("28"));
        assertThat(t.taxable()).isEqualByComparingTo("1000.00");
        assertThat(t.igst()).isEqualByComparingTo("280.00");
        assertThat(t.cgst().add(t.sgst())).isEqualByComparingTo("0");
    }

    @Test
    void walkInAndSameStateAreIntraState() {
        assertThat(TaxCalculator.forSupply("29", null)).isSameAs(TaxCalculator.INTRA_STATE);
        assertThat(TaxCalculator.forSupply("29", "29")).isSameAs(TaxCalculator.INTRA_STATE);
    }

    @Test
    void partsAlwaysAddBackToTheAmountCharged() {
        var rnd = new Random(11);
        String[] rates = {"0", "5", "12", "18", "28"};
        for (int i = 0; i < 5000; i++) {
            BigDecimal amount = BigDecimal.valueOf(rnd.nextInt(10_000_000), 2);
            BigDecimal rate = new BigDecimal(rates[rnd.nextInt(rates.length)]);
            for (TaxCalculator c : new TaxCalculator[]{TaxCalculator.INTRA_STATE, TaxCalculator.INTER_STATE}) {
                LineTax t = c.split(amount, rate);
                assertThat(t.gross()).isEqualByComparingTo(amount);
                assertThat(t.cgst().subtract(t.sgst()).abs()).isLessThanOrEqualTo(new BigDecimal("0.01"));
                assertThat(t.tax().signum()).isGreaterThanOrEqualTo(0);
            }
        }
    }
}
