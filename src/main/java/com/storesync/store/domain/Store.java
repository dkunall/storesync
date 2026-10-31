package com.storesync.store.domain;

import java.util.regex.Pattern;

/** A shop. {@code stateCode} is the two-digit GST state code, which decides CGST+SGST versus IGST. */
public record Store(String code, String name, String stateCode, String gstin) {

    private static final Pattern CODE = Pattern.compile("[A-Z0-9]{2,4}");
    private static final Pattern STATE = Pattern.compile("\\d{2}");
    /** Shape only: two-digit state, PAN-like ten characters, entity number, Z, check character. */
    public static final Pattern GSTIN = Pattern.compile("\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]");

    public Store {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("store code must be 2-4 upper-case letters or digits");
        }
        if (stateCode == null || !STATE.matcher(stateCode).matches()) {
            throw new IllegalArgumentException("state code must be two digits");
        }
        if (gstin == null || !GSTIN.matcher(gstin).matches() || !gstin.startsWith(stateCode)) {
            throw new IllegalArgumentException("GSTIN must be well formed and start with the store's state code");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
    }

    /** Place of supply for a B2B customer is the state in their GSTIN; for walk-in customers it is the store. */
    public static String placeOfSupply(String storeState, String customerGstin) {
        if (customerGstin == null || customerGstin.isBlank()) {
            return storeState;
        }
        if (!GSTIN.matcher(customerGstin).matches()) {
            throw new IllegalArgumentException("customer GSTIN is not well formed");
        }
        return customerGstin.substring(0, 2);
    }
}
