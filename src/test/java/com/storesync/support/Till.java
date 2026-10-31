package com.storesync.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.storesync.sales.domain.SaleCalculator;
import com.storesync.sales.domain.TaxCalculator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A pretend offline terminal: it holds an invoice block, numbers its own ops and prices sales the way the till app
 * would, so the server's checks see exactly what a real till sends.
 */
public final class Till {

    private final ItBase.Terminal terminal;
    private final String storeState;
    private int epoch;
    private long seq;
    private String series;
    private int next;
    private int last;

    public Till(ItBase.Terminal terminal, String storeState) {
        this.terminal = terminal;
        this.storeState = storeState;
        this.epoch = terminal.epoch();
    }

    public ItBase.Terminal terminal() {
        return terminal;
    }

    public int epoch() {
        return epoch;
    }

    public void epoch(int e) {
        epoch = e;
        seq = 0;
    }

    /** Asks the server for a block of invoice numbers and starts using the newest one. */
    public JsonNode allocate() {
        var r = terminal.api().post("/v1/terminals/" + terminal.id() + "/invoice-blocks", Map.of()).expect(201).body();
        series = r.path("series").asText();
        var b = r.path("block");
        next = b.path("first").asInt();
        last = b.path("last").asInt();
        return r;
    }

    public String nextInvoice() {
        if (next > last) {
            throw new IllegalStateException("block used up");
        }
        return invoice(next++);
    }

    public String invoice(int n) {
        return series + String.format("-%05d", n);
    }

    public Map<String, Object> op(String type, Object payload) {
        return op(++seq, type, payload);
    }

    public Map<String, Object> op(long s, String type, Object payload) {
        return Map.of("seq", s, "type", type, "v", 2, "payload", payload);
    }

    public Map<String, Object> shiftOpened(UUID shiftId, String cashier, String float_) {
        return op("SHIFT_OPENED", Map.of("shiftId", shiftId, "cashier", cashier, "openingFloat", new BigDecimal(float_),
                "openedAt", Instant.now().minusSeconds(60).toString()));
    }

    public Map<String, Object> shiftClosed(UUID shiftId, String counted, int declaredSales) {
        return op("SHIFT_CLOSED", Map.of("shiftId", shiftId, "countedCash", new BigDecimal(counted),
                "declaredSales", declaredSales, "closedAt", Instant.now().toString()));
    }

    /** A cash sale of one SKU at its shelf price, paid with exactly {@code tendered}. */
    public Map<String, Object> cashSale(UUID saleId, UUID shiftId, String invoiceNo, String cashier, String sku, int qty,
                                        String unitPrice, String taxRate, String tendered) {
        return op("SALE", salePayload(saleId, shiftId, invoiceNo, cashier, sku, qty, unitPrice, taxRate, tendered));
    }

    public Map<String, Object> salePayload(UUID saleId, UUID shiftId, String invoiceNo, String cashier, String sku,
                                           int qty, String unitPrice, String taxRate, String tendered) {
        var in = new SaleCalculator.LineInput(1, sku, qty, new BigDecimal(unitPrice), new BigDecimal(taxRate),
                BigDecimal.ZERO, BigDecimal.ZERO);
        var priced = SaleCalculator.price(List.of(in), TaxCalculator.forSupply(storeState, storeState));
        BigDecimal paid = new BigDecimal(tendered);
        Map<String, Object> line = new HashMap<>();
        line.put("lineNo", 1);
        line.put("sku", sku);
        line.put("qty", qty);
        line.put("unitPrice", new BigDecimal(unitPrice));
        line.put("taxRate", new BigDecimal(taxRate));
        line.put("promoDiscount", BigDecimal.ZERO);
        line.put("manualDiscount", BigDecimal.ZERO);
        Map<String, Object> p = new HashMap<>();
        p.put("saleId", saleId);
        p.put("shiftId", shiftId);
        p.put("invoiceNo", invoiceNo);
        p.put("cashier", cashier);
        p.put("soldAt", Instant.now().minusSeconds(5).toString());
        p.put("lines", List.of(line));
        p.put("payments", List.of(Map.of("method", "CASH", "amount", paid)));
        p.put("changeDue", paid.subtract(priced.total()));
        p.put("totals", Map.of("taxable", priced.taxable(), "cgst", priced.cgst(), "sgst", priced.sgst(),
                "igst", priced.igst(), "total", priced.total()));
        p.put("promotions", List.of());
        return p;
    }

    public Api.Response push(List<Map<String, Object>> ops) {
        return push(epoch, ops);
    }

    public Api.Response push(int e, List<Map<String, Object>> ops) {
        return terminal.api().post("/v1/sync/push", Map.of("terminalId", terminal.id(), "epoch", e,
                "ops", new ArrayList<>(ops)));
    }

    public JsonNode pull(long since) {
        return terminal.api().get("/v1/sync/pull?since=" + since).expect(200).body();
    }
}
