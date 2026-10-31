package com.storesync.catalog.domain;

import com.storesync.shared.domain.Money;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A sellable item. {@code price} is the shelf price per unit, GST included, as printed on the label; tax is backed
 * out of it at sale time. {@code taxRate} is the total GST percentage (split into CGST+SGST or charged as IGST).
 */
public record Product(String sku, String barcode, String name, String hsn, BigDecimal taxRate, BigDecimal price,
                      BigDecimal cost, UUID supplierId, int lowStockLevel, boolean active) {

    /** GST slabs the catalog accepts. */
    public static final Set<BigDecimal> RATES = Set.of(new BigDecimal("0.00"), new BigDecimal("0.25"),
            new BigDecimal("3.00"), new BigDecimal("5.00"), new BigDecimal("12.00"), new BigDecimal("18.00"),
            new BigDecimal("28.00"), new BigDecimal("40.00"));
    private static final Pattern SKU = Pattern.compile("[A-Z0-9][A-Z0-9-]{1,31}");
    private static final Pattern HSN = Pattern.compile("\\d{4}(\\d{2}){0,2}");

    public Product {
        if (sku == null || !SKU.matcher(sku).matches()) {
            throw new IllegalArgumentException("sku must be 2-32 upper-case letters, digits or dashes");
        }
        if (!validBarcode(barcode)) {
            throw new IllegalArgumentException("barcode must be an EAN-8, UPC-A, EAN-13 or GTIN-14 with a valid check digit");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (hsn == null || !HSN.matcher(hsn).matches()) {
            throw new IllegalArgumentException("HSN must be 4, 6 or 8 digits");
        }
        taxRate = Money.exact(taxRate, "tax rate");
        if (!RATES.contains(taxRate)) {
            throw new IllegalArgumentException("tax rate must be one of the GST slabs " + RATES);
        }
        price = Money.exact(price, "price");
        if (price.signum() <= 0) {
            throw new IllegalArgumentException("price must be positive");
        }
        if (cost != null) {
            cost = Money.exact(cost, "cost");
            if (cost.signum() < 0) {
                throw new IllegalArgumentException("cost cannot be negative");
            }
        }
        if (lowStockLevel < 0) {
            throw new IllegalArgumentException("low stock level cannot be negative");
        }
    }

    /** GS1 mod-10 check digit. */
    public static boolean validBarcode(String code) {
        if (code == null || !code.matches("\\d{8}|\\d{12,14}")) {
            return false;
        }
        int sum = 0;
        for (int i = code.length() - 2, w = 3; i >= 0; i--, w = 4 - w) {
            sum += (code.charAt(i) - '0') * w;
        }
        return (10 - sum % 10) % 10 == code.charAt(code.length() - 1) - '0';
    }
}
