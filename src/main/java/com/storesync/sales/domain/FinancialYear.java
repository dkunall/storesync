package com.storesync.sales.domain;

import java.time.LocalDate;

/** Indian financial year, April to March. FY 2026-27 has code "26". Invoice series restart every year. */
public record FinancialYear(int startYear) {

    public static FinancialYear of(LocalDate day) {
        return new FinancialYear(day.getMonthValue() >= 4 ? day.getYear() : day.getYear() - 1);
    }

    public String code() {
        return String.format("%02d", startYear % 100);
    }

    public LocalDate firstDay() {
        return LocalDate.of(startYear, 4, 1);
    }

    public LocalDate lastDay() {
        return LocalDate.of(startYear + 1, 3, 31);
    }
}
