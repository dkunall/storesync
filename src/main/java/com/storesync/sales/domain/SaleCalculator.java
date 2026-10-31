package com.storesync.sales.domain;

import com.storesync.shared.domain.DomainRuleException;
import com.storesync.shared.domain.Money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Prices a basket the same way on the server and (in principle) on the terminal: gross at shelf price, minus
 * promotion and manual discounts, with GST backed out of what is left. The server uses it both to price online
 * sales and to check the arithmetic of sales that terminals priced offline.
 */
public final class SaleCalculator {

    public record LineInput(int lineNo, String sku, int qty, BigDecimal unitPrice, BigDecimal taxRate,
                            BigDecimal promoDiscount, BigDecimal manualDiscount) {
        public LineInput {
            if (qty <= 0) {
                throw new DomainRuleException("bad-quantity", "Line " + lineNo + " quantity must be positive");
            }
            unitPrice = Money.exact(unitPrice, "unit price");
            promoDiscount = promoDiscount == null ? Money.ZERO : Money.exact(promoDiscount, "promotion discount");
            manualDiscount = manualDiscount == null ? Money.ZERO : Money.exact(manualDiscount, "manual discount");
            if (promoDiscount.signum() < 0 || manualDiscount.signum() < 0) {
                throw new DomainRuleException("bad-discount", "Discounts cannot be negative");
            }
        }

        public BigDecimal gross() {
            return unitPrice.multiply(BigDecimal.valueOf(qty));
        }
    }

    public record PricedLine(LineInput input, BigDecimal gross, LineTax tax, BigDecimal total) {
    }

    public record PricedSale(List<PricedLine> lines, BigDecimal gross, BigDecimal discount, BigDecimal taxable,
                             BigDecimal cgst, BigDecimal sgst, BigDecimal igst, BigDecimal total) {
        public BigDecimal tax() {
            return cgst.add(sgst).add(igst);
        }
    }

    private SaleCalculator() {
    }

    public static PricedSale price(List<LineInput> lines, TaxCalculator tax) {
        if (lines.isEmpty()) {
            throw new DomainRuleException("empty-sale", "A sale needs at least one line");
        }
        var priced = new ArrayList<PricedLine>();
        BigDecimal gross = Money.ZERO, discount = Money.ZERO, taxable = Money.ZERO, cgst = Money.ZERO,
                sgst = Money.ZERO, igst = Money.ZERO, total = Money.ZERO;
        for (LineInput l : lines) {
            BigDecimal g = l.gross();
            BigDecimal d = l.promoDiscount().add(l.manualDiscount());
            if (d.compareTo(g) > 0) {
                throw new DomainRuleException("discount-exceeds-price", "Line " + l.lineNo() + " is discounted below zero");
            }
            BigDecimal net = g.subtract(d);
            LineTax t = tax.split(net, l.taxRate());
            priced.add(new PricedLine(l, g, t, net));
            gross = gross.add(g);
            discount = discount.add(d);
            taxable = taxable.add(t.taxable());
            cgst = cgst.add(t.cgst());
            sgst = sgst.add(t.sgst());
            igst = igst.add(t.igst());
            total = total.add(net);
        }
        return new PricedSale(List.copyOf(priced), gross, discount, taxable, cgst, sgst, igst, total);
    }
}
