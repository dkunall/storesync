package com.storesync.sales.adapter;

import com.storesync.sales.application.SalesRepository;
import com.storesync.sales.domain.InvoiceBlocks;
import com.storesync.sales.domain.PaymentMethod;
import com.storesync.sales.domain.SaleStatus;
import com.storesync.sales.domain.ShiftState;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

@Repository
class JdbcSalesRepository implements SalesRepository {

    private static final String SALE = """
            select id, store_code, terminal_id, shift_id, local_seq, channel, invoice_no, status, cashier,
                   place_of_supply, customer_gstin, gross, discount, taxable, cgst, sgst, igst, total, change_due,
                   refunded, request_hash, sold_at, synced_at from sale\s""";
    private static final String SHIFT = """
            select id, store_code, terminal_id, cashier, status, opening_float, opened_at, closed_at, declared_sales,
                   counted_cash, expected_cash, variance from shift\s""";

    private static final RowMapper<Sale> SALE_ROW = (rs, i) -> new Sale(rs.getObject(1, UUID.class), rs.getString(2),
            rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), (Long) rs.getObject(5),
            Channel.valueOf(rs.getString(6)), rs.getString(7), SaleStatus.valueOf(rs.getString(8)), rs.getString(9),
            rs.getString(10), rs.getString(11), rs.getBigDecimal(12), rs.getBigDecimal(13), rs.getBigDecimal(14),
            rs.getBigDecimal(15), rs.getBigDecimal(16), rs.getBigDecimal(17), rs.getBigDecimal(18),
            rs.getBigDecimal(19), rs.getBigDecimal(20), rs.getString(21), instant(rs, 22), instant(rs, 23));
    private static final RowMapper<Shift> SHIFT_ROW = (rs, i) -> new Shift(rs.getObject(1, UUID.class),
            rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4), ShiftState.valueOf(rs.getString(5)),
            rs.getBigDecimal(6), instant(rs, 7), instant(rs, 8), (Integer) rs.getObject(9), rs.getBigDecimal(10),
            rs.getBigDecimal(11), rs.getBigDecimal(12));

    private final JdbcClient db;

    JdbcSalesRepository(JdbcClient db) {
        this.db = db;
    }

    private static Instant instant(ResultSet rs, int col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    @Override
    public void lockSaleId(UUID id) {
        db.sql("select pg_advisory_xact_lock(?)").param(id.getMostSignificantBits() ^ id.getLeastSignificantBits())
                .query((rs, i) -> 1).list();
    }

    @Override
    public Optional<Sale> sale(UUID id, boolean lock) {
        return db.sql(SALE + "where id = ?" + (lock ? " for update" : "")).param(id).query(SALE_ROW).optional();
    }

    @Override
    public Optional<SaleView> view(UUID id) {
        return sale(id, false).map(s -> new SaleView(s,
                db.sql("""
                                select line_no, sku, qty, unit_price, tax_rate, promo_discount, manual_discount, taxable,
                                       cgst, sgst, igst, total, returned_qty, refunded, refunded_taxable, refunded_cgst,
                                       refunded_sgst, refunded_igst from sale_line where sale_id = ? order by line_no""")
                        .param(id).query((rs, i) -> new Line(rs.getInt(1), rs.getString(2), rs.getInt(3),
                                rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7),
                                rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getBigDecimal(11),
                                rs.getBigDecimal(12), rs.getInt(13), rs.getBigDecimal(14), rs.getBigDecimal(15),
                                rs.getBigDecimal(16), rs.getBigDecimal(17), rs.getBigDecimal(18))).list(),
                db.sql("select method, amount, reference from payment where sale_id = ? order by idx").param(id)
                        .query((rs, i) -> new Payment(PaymentMethod.valueOf(rs.getString(1)), rs.getBigDecimal(2),
                                rs.getString(3))).list(),
                db.sql("select promotion_code, times, saving from sale_promotion where sale_id = ? order by promotion_code")
                        .param(id).query((rs, i) -> new AppliedPromotion(rs.getString(1), rs.getInt(2),
                                rs.getBigDecimal(3))).list()));
    }

    @Override
    public boolean invoiceUsed(String invoiceNo) {
        return db.sql("select count(*) from sale where invoice_no = ?").param(invoiceNo).query(Long.class).single() > 0;
    }

    @Override
    public void insert(Sale s, List<Line> lines, List<Payment> payments, List<AppliedPromotion> promotions) {
        db.sql("""
                        insert into sale (id, store_code, terminal_id, shift_id, local_seq, channel, invoice_no, status,
                                          cashier, place_of_supply, customer_gstin, gross, discount, taxable, cgst, sgst,
                                          igst, total, change_due, refunded, request_hash, sold_at, synced_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(s.id(), s.store(), s.terminalId(), s.shiftId(), s.localSeq(), s.channel().name(), s.invoiceNo(),
                        s.status().name(), s.cashier(), s.placeOfSupply(), s.customerGstin(), s.gross(), s.discount(),
                        s.taxable(), s.cgst(), s.sgst(), s.igst(), s.total(), s.changeDue(), s.refunded(),
                        s.requestHash(), ts(s.soldAt()), ts(s.syncedAt())).update();
        for (Line l : lines) {
            db.sql("""
                            insert into sale_line (sale_id, line_no, sku, qty, unit_price, tax_rate, promo_discount,
                                                   manual_discount, taxable, cgst, sgst, igst, total)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(s.id(), l.lineNo(), l.sku(), l.qty(), l.unitPrice(), l.taxRate(), l.promoDiscount(),
                            l.manualDiscount(), l.taxable(), l.cgst(), l.sgst(), l.igst(), l.total()).update();
        }
        int idx = 0;
        for (Payment p : payments) {
            db.sql("insert into payment (sale_id, idx, method, amount, reference) values (?, ?, ?, ?, ?)")
                    .params(s.id(), idx++, p.method().name(), p.amount(), p.reference()).update();
        }
        for (AppliedPromotion a : promotions) {
            db.sql("insert into sale_promotion (sale_id, promotion_code, times, saving) values (?, ?, ?, ?)")
                    .params(s.id(), a.code(), a.times(), a.saving()).update();
        }
    }

    @Override
    public void updateAfterReturn(UUID saleId, SaleStatus status, BigDecimal refunded, List<Line> changedLines) {
        db.sql("update sale set status = ?, refunded = ? where id = ?").params(status.name(), refunded, saleId).update();
        for (Line l : changedLines) {
            db.sql("""
                            update sale_line set returned_qty = ?, refunded = ?, refunded_taxable = ?, refunded_cgst = ?,
                                                 refunded_sgst = ?, refunded_igst = ?
                            where sale_id = ? and line_no = ?""")
                    .params(l.returnedQty(), l.refunded(), l.refundedTaxable(), l.refundedCgst(), l.refundedSgst(),
                            l.refundedIgst(), saleId, l.lineNo()).update();
        }
    }

    @Override
    public int lockSeries(String series, String store, UUID terminalId, int fy) {
        db.sql("insert into invoice_series (series, store_code, terminal_id, fy, next_no) values (?, ?, ?, ?, 1) on conflict do nothing")
                .params(series, store, terminalId, fy).update();
        return db.sql("select next_no from invoice_series where series = ? for update").param(series)
                .query(Integer.class).single();
    }

    @Override
    public void setNext(String series, int next) {
        db.sql("update invoice_series set next_no = ? where series = ?").params(next, series).update();
    }

    @Override
    public void insertBlock(String series, InvoiceBlocks.Block b, UUID terminalId, Instant at) {
        db.sql("insert into invoice_block (series, first_no, last_no, terminal_id, allocated_at) values (?, ?, ?, ?, ?)")
                .params(series, b.first(), b.last(), terminalId, ts(at)).update();
    }

    @Override
    public List<InvoiceBlocks.Block> blocks(String series) {
        return db.sql("select first_no, last_no from invoice_block where series = ? order by first_no").param(series)
                .query((rs, i) -> new InvoiceBlocks.Block(rs.getInt(1), rs.getInt(2))).list();
    }

    @Override
    public Optional<Shift> shift(UUID id, boolean lock) {
        return db.sql(SHIFT + "where id = ?" + (lock ? " for update" : "")).param(id).query(SHIFT_ROW).optional();
    }

    @Override
    public void insertShift(Shift s) {
        db.sql("""
                        insert into shift (id, store_code, terminal_id, cashier, status, opening_float, opened_at)
                        values (?, ?, ?, ?, ?, ?, ?)""")
                .params(s.id(), s.store(), s.terminalId(), s.cashier(), s.status().name(), s.openingFloat(),
                        ts(s.openedAt())).update();
    }

    @Override
    public void updateShift(Shift s) {
        db.sql("""
                        update shift set status = ?, closed_at = ?, declared_sales = ?, counted_cash = ?,
                                         expected_cash = ?, variance = ? where id = ?""")
                .params(s.status().name(), ts(s.closedAt()), s.declaredSales(), s.countedCash(), s.expectedCash(),
                        s.variance(), s.id()).update();
    }

    @Override
    public ShiftTotals totals(UUID shiftId) {
        var sales = db.sql("""
                        select count(*) filter (where s.status <> 'VOIDED'),
                               coalesce(sum(s.change_due), 0),
                               coalesce(sum((select sum(p.amount) from payment p where p.sale_id = s.id and p.method = 'CASH')), 0)
                        from sale s where s.shift_id = ?""")
                .param(shiftId).query((rs, i) -> new Object[]{rs.getLong(1), rs.getBigDecimal(2), rs.getBigDecimal(3)})
                .single();
        BigDecimal refunds = db.sql("select coalesce(sum(amount), 0) from sale_return where shift_id = ? and refund_method = 'CASH'")
                .param(shiftId).query(BigDecimal.class).single();
        return new ShiftTotals((Long) sales[0], (BigDecimal) sales[2], (BigDecimal) sales[1], refunds);
    }

    @Override
    public List<Shift> shifts(String store, Instant from, Instant to) {
        return db.sql(SHIFT + "where store_code = ? and opened_at >= ? and opened_at < ? order by opened_at")
                .params(store, ts(from), ts(to)).query(SHIFT_ROW).list();
    }

    @Override
    public Optional<Return> findReturn(UUID id) {
        return db.sql("""
                        select id, sale_id, refund_method, amount, credit_note_no, restock, shift_id, request_hash,
                               by_subject, at from sale_return where id = ?""")
                .param(id).query((rs, i) -> new Return(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        PaymentMethod.valueOf(rs.getString(3)), rs.getBigDecimal(4), rs.getString(5),
                        rs.getBoolean(6), rs.getObject(7, UUID.class), rs.getString(8), rs.getString(9),
                        instant(rs, 10), returnQty(id))).optional();
    }

    private Map<Integer, Integer> returnQty(UUID id) {
        var out = new TreeMap<Integer, Integer>();
        db.sql("select line_no, qty from sale_return_line where return_id = ?").param(id)
                .query((rs, i) -> out.put(rs.getInt(1), rs.getInt(2))).list();
        return out;
    }

    @Override
    public void insertReturn(Return r, Map<Integer, BigDecimal> amounts) {
        db.sql("""
                        insert into sale_return (id, sale_id, refund_method, amount, credit_note_no, restock, shift_id,
                                                 request_hash, by_subject, at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(r.id(), r.saleId(), r.refundMethod().name(), r.amount(), r.creditNoteNo(), r.restock(),
                        r.shiftId(), r.requestHash(), r.by(), ts(r.at())).update();
        r.qty().forEach((line, qty) -> db.sql("insert into sale_return_line (return_id, line_no, qty, amount) values (?, ?, ?, ?)")
                .params(r.id(), line, qty, amounts.get(line)).update());
    }

    @Override
    public Optional<CreditNote> creditNote(String no, boolean lock) {
        return db.sql("select no, store_code, sale_id, amount, used, created_at from credit_note where no = ?"
                        + (lock ? " for update" : "")).param(no)
                .query((rs, i) -> new CreditNote(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), instant(rs, 6))).optional();
    }

    @Override
    public void insertCreditNote(CreditNote c) {
        db.sql("insert into credit_note (no, store_code, sale_id, amount, used, created_at) values (?, ?, ?, ?, ?, ?)")
                .params(c.no(), c.store(), c.saleId(), c.amount(), c.used(), ts(c.createdAt())).update();
    }

    @Override
    public void useCreditNote(String no, BigDecimal amount) {
        db.sql("update credit_note set used = used + ? where no = ?").params(amount, no).update();
    }

    @Override
    public DayFigures dayFigures(String store, Instant from, Instant to) {
        var head = db.sql("""
                        select count(*) filter (where status <> 'VOIDED'), count(*) filter (where status = 'VOIDED'),
                               coalesce(sum(total), 0), coalesce(sum(change_due), 0)
                        from sale where store_code = ? and sold_at >= ? and sold_at < ?""")
                .params(store, ts(from), ts(to))
                .query((rs, i) -> new Object[]{rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3), rs.getBigDecimal(4)})
                .single();
        var tendered = new LinkedHashMap<String, BigDecimal>();
        db.sql("""
                        select p.method, sum(p.amount) from payment p join sale s on s.id = p.sale_id
                        where s.store_code = ? and s.sold_at >= ? and s.sold_at < ? group by p.method order by p.method""")
                .params(store, ts(from), ts(to)).query((rs, i) -> tendered.put(rs.getString(1), rs.getBigDecimal(2))).list();
        var refunds = new LinkedHashMap<String, BigDecimal>();
        long[] returns = {0};
        db.sql("""
                        select r.refund_method, sum(r.amount), count(*) from sale_return r join sale s on s.id = r.sale_id
                        where s.store_code = ? and r.at >= ? and r.at < ? group by r.refund_method order by r.refund_method""")
                .params(store, ts(from), ts(to)).query((rs, i) -> {
                    returns[0] += rs.getLong(3);
                    return refunds.put(rs.getString(1), rs.getBigDecimal(2));
                }).list();
        return new DayFigures((Long) head[0], (Long) head[1], (BigDecimal) head[2], tendered, (BigDecimal) head[3],
                refunds, returns[0]);
    }
}
