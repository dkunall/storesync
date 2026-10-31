package com.storesync.sales.domain;

import java.math.BigDecimal;

/** GST split of one line. {@code taxable + cgst + sgst + igst} is exactly the tax-inclusive amount charged. */
public record LineTax(BigDecimal taxable, BigDecimal cgst, BigDecimal sgst, BigDecimal igst) {

    public BigDecimal tax() {
        return cgst.add(sgst).add(igst);
    }

    public BigDecimal gross() {
        return taxable.add(tax());
    }
}
