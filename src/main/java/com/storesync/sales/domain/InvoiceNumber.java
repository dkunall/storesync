package com.storesync.sales.domain;

import java.util.regex.Pattern;

/**
 * {@code <store><terminal 2 digits>-<FY>-<5 digits>}, e.g. {@code BLR101-26-00042}: at most 15 characters, inside
 * the 16 that GST allows for an invoice number, and unique per series per financial year. Terminal 00 is the
 * server's own series for online sales; {@code CN} instead of a terminal number is the store's credit-note series.
 */
public record InvoiceNumber(String series, int number) {

    public static final int MAX = 99_999;
    private static final Pattern STORE = Pattern.compile("[A-Z0-9]{2,4}");
    private static final Pattern FORMAT = Pattern.compile("([A-Z0-9]{2,4}(?:\\d{2}|CN)-\\d{2})-(\\d{5})");

    public InvoiceNumber {
        if (number < 1 || number > MAX) {
            throw new IllegalArgumentException("invoice number out of range: " + number);
        }
    }

    public static String series(String store, int terminalNo, FinancialYear fy) {
        if (!STORE.matcher(store).matches() || terminalNo < 0 || terminalNo > 99) {
            throw new IllegalArgumentException("bad store code or terminal number");
        }
        return store + String.format("%02d", terminalNo) + "-" + fy.code();
    }

    public static String creditNoteSeries(String store, FinancialYear fy) {
        if (!STORE.matcher(store).matches()) {
            throw new IllegalArgumentException("bad store code");
        }
        return store + "CN-" + fy.code();
    }

    public static InvoiceNumber parse(String s) {
        var m = s == null ? null : FORMAT.matcher(s);
        if (m == null || !m.matches()) {
            throw new IllegalArgumentException("not an invoice number: " + s);
        }
        return new InvoiceNumber(m.group(1), Integer.parseInt(m.group(2)));
    }

    @Override
    public String toString() {
        return series + "-" + String.format("%05d", number);
    }
}
