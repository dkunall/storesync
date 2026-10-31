package com.storesync.sales.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceNumberTest {

    @Test
    void financialYearRunsAprilToMarch() {
        assertThat(FinancialYear.of(LocalDate.of(2026, 3, 31)).startYear()).isEqualTo(2025);
        assertThat(FinancialYear.of(LocalDate.of(2026, 4, 1)).startYear()).isEqualTo(2026);
        assertThat(FinancialYear.of(LocalDate.of(2026, 10, 31)).code()).isEqualTo("26");
        assertThat(new FinancialYear(2026).lastDay()).isEqualTo(LocalDate.of(2027, 3, 31));
    }

    @Test
    void formatsWithinSixteenCharactersAndParsesBack() {
        String series = InvoiceNumber.series("BLR1", 1, new FinancialYear(2026));
        var no = new InvoiceNumber(series, 42);
        assertThat(no.toString()).isEqualTo("BLR101-26-00042").hasSizeLessThanOrEqualTo(16);
        assertThat(InvoiceNumber.parse("BLR101-26-00042")).isEqualTo(no);
        assertThat(InvoiceNumber.parse("PUN2CN-26-00001").series()).isEqualTo("PUN2CN-26");
    }

    @Test
    void rejectsMalformedNumbers() {
        assertThatThrownBy(() -> InvoiceNumber.parse("BLR1-26-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvoiceNumber.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvoiceNumber.series("blr", 1, new FinancialYear(2026)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvoiceNumber("BLR101-26", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blocksCoverOnlyTheirOwnSeriesAndRange() {
        var blocks = new InvoiceBlocks("BLR101-26", List.of(new InvoiceBlocks.Block(1, 100), new InvoiceBlocks.Block(201, 300)));
        assertThat(blocks.covers(InvoiceNumber.parse("BLR101-26-00100"))).isTrue();
        assertThat(blocks.covers(InvoiceNumber.parse("BLR101-26-00150"))).isFalse();
        assertThat(blocks.covers(InvoiceNumber.parse("BLR102-26-00050"))).isFalse();
        assertThat(InvoiceBlocks.allocate(99_950, 100)).isEqualTo(new InvoiceBlocks.Block(99_950, 99_999));
        assertThatThrownBy(() -> InvoiceBlocks.allocate(100_000, 100))
                .hasMessageContaining("used up");
    }
}
