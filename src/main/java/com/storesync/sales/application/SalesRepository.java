package com.storesync.sales.application;

import com.storesync.sales.domain.InvoiceBlocks;
import com.storesync.sales.domain.PaymentMethod;
import com.storesync.sales.domain.SaleStatus;
import com.storesync.sales.domain.ShiftState;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface SalesRepository {

    enum Channel { ONLINE, OFFLINE }

    record Sale(UUID id, String store, UUID terminalId, UUID shiftId, Long localSeq, Channel channel, String invoiceNo,
                SaleStatus status, String cashier, String placeOfSupply, String customerGstin, BigDecimal gross,
                BigDecimal discount, BigDecimal taxable, BigDecimal cgst, BigDecimal sgst, BigDecimal igst,
                BigDecimal total, BigDecimal changeDue, BigDecimal refunded, String requestHash, Instant soldAt,
                Instant syncedAt) {
    }

    record Line(int lineNo, String sku, int qty, BigDecimal unitPrice, BigDecimal taxRate, BigDecimal promoDiscount,
                BigDecimal manualDiscount, BigDecimal taxable, BigDecimal cgst, BigDecimal sgst, BigDecimal igst,
                BigDecimal total, int returnedQty, BigDecimal refunded, BigDecimal refundedTaxable,
                BigDecimal refundedCgst, BigDecimal refundedSgst, BigDecimal refundedIgst) {
    }

    record Payment(PaymentMethod method, BigDecimal amount, String reference) {
    }

    record AppliedPromotion(String code, int times, BigDecimal saving) {
    }

    record SaleView(Sale sale, List<Line> lines, List<Payment> payments, List<AppliedPromotion> promotions) {
    }

    record Shift(UUID id, String store, UUID terminalId, String cashier, ShiftState status, BigDecimal openingFloat,
                 Instant openedAt, Instant closedAt, Integer declaredSales, BigDecimal countedCash,
                 BigDecimal expectedCash, BigDecimal variance) {
    }

    /** What has reached the server for a shift so far. */
    record ShiftTotals(long sales, BigDecimal cashTendered, BigDecimal changeGiven, BigDecimal cashRefunds) {
    }

    record Return(UUID id, UUID saleId, PaymentMethod refundMethod, BigDecimal amount, String creditNoteNo,
                  boolean restock, UUID shiftId, String requestHash, String by, Instant at, Map<Integer, Integer> qty) {
    }

    record CreditNote(String no, String store, UUID saleId, BigDecimal amount, BigDecimal used, Instant createdAt) {
        public BigDecimal balance() {
            return amount.subtract(used);
        }
    }

    // sales
    /** Transaction-scoped lock on a device-generated sale id, so two uploads of the same sale queue up. */
    void lockSaleId(UUID id);

    Optional<Sale> sale(UUID id, boolean lock);

    Optional<SaleView> view(UUID id);

    boolean invoiceUsed(String invoiceNo);

    void insert(Sale s, List<Line> lines, List<Payment> payments, List<AppliedPromotion> promotions);

    void updateAfterReturn(UUID saleId, SaleStatus status, BigDecimal refunded, List<Line> changedLines);

    // invoice numbering
    /** Creates the series if new, then locks it. @return the next unissued number */
    int lockSeries(String series, String store, UUID terminalId, int fy);

    void setNext(String series, int next);

    void insertBlock(String series, InvoiceBlocks.Block b, UUID terminalId, Instant at);

    List<InvoiceBlocks.Block> blocks(String series);

    // shifts
    Optional<Shift> shift(UUID id, boolean lock);

    void insertShift(Shift s);

    void updateShift(Shift s);

    ShiftTotals totals(UUID shiftId);

    List<Shift> shifts(String store, Instant from, Instant to);

    // returns and credit notes
    Optional<Return> findReturn(UUID id);

    void insertReturn(Return r, Map<Integer, BigDecimal> amounts);

    Optional<CreditNote> creditNote(String no, boolean lock);

    void insertCreditNote(CreditNote c);

    void useCreditNote(String no, BigDecimal amount);

    // reconciliation
    record DayFigures(long invoices, long voided, BigDecimal salesTotal, Map<String, BigDecimal> tenderedByMethod,
                      BigDecimal changeGiven, Map<String, BigDecimal> refundsByMethod, long returns) {
    }

    DayFigures dayFigures(String store, Instant from, Instant to);
}
