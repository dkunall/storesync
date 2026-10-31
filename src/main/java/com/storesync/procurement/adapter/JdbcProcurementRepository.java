package com.storesync.procurement.adapter;

import com.storesync.procurement.application.ProcurementRepository;
import com.storesync.procurement.domain.PoStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

@Repository
class JdbcProcurementRepository implements ProcurementRepository {

    private final JdbcClient db;

    JdbcProcurementRepository(JdbcClient db) {
        this.db = db;
    }

    @Override
    public boolean insertSupplier(Supplier s) {
        return db.sql("insert into supplier (id, name, lead_time_days) values (?, ?, ?) on conflict do nothing")
                .params(s.id(), s.name(), s.leadTimeDays()).update() == 1;
    }

    @Override
    public Optional<Supplier> supplier(UUID id) {
        return db.sql("select id, name, lead_time_days from supplier where id = ?").param(id)
                .query((rs, i) -> new Supplier(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3))).optional();
    }

    @Override
    public List<Supplier> suppliers() {
        return db.sql("select id, name, lead_time_days from supplier order by name, id")
                .query((rs, i) -> new Supplier(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3))).list();
    }

    @Override
    public boolean insertOrder(PurchaseOrder po) {
        int n = db.sql("""
                        insert into purchase_order (id, store_code, supplier_id, status, idem_key, created_by, created_at)
                        values (?, ?, ?, ?, ?, ?, ?) on conflict (idem_key) do nothing""")
                .params(po.id(), po.store(), po.supplierId(), po.status().name(), po.idempotencyKey(), po.createdBy(),
                        Timestamp.from(po.createdAt())).update();
        if (n == 0) {
            return false;
        }
        for (PoLine l : po.lines()) {
            db.sql("insert into po_line (po_id, sku, qty, unit_cost, received) values (?, ?, ?, ?, 0)")
                    .params(po.id(), l.sku(), l.qty(), l.unitCost()).update();
        }
        return true;
    }

    private static final String ORDER = """
            select id, store_code, supplier_id, status, idem_key, created_by, created_at from purchase_order""";

    private PurchaseOrder withLines(ResultSet rs) throws SQLException {
        UUID id = rs.getObject(1, UUID.class);
        var lines = db.sql("select sku, qty, unit_cost, received from po_line where po_id = ? order by sku").param(id)
                .query((r, i) -> new PoLine(r.getString(1), r.getInt(2), r.getBigDecimal(3), r.getInt(4))).list();
        return new PurchaseOrder(id, rs.getString(2), rs.getObject(3, UUID.class), PoStatus.valueOf(rs.getString(4)),
                rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant(), lines);
    }

    @Override
    public Optional<PurchaseOrder> order(UUID id, boolean lock) {
        return db.sql(ORDER + " where id = ?" + (lock ? " for update" : "")).param(id)
                .query((rs, i) -> withLines(rs)).optional();
    }

    @Override
    public Optional<PurchaseOrder> orderByKey(String key) {
        return db.sql(ORDER + " where idem_key = ?").param(key).query((rs, i) -> withLines(rs)).optional();
    }

    @Override
    public List<PurchaseOrder> orders(String store) {
        return db.sql(ORDER + " where store_code = ? order by created_at desc, id limit 200").param(store)
                .query((rs, i) -> withLines(rs)).list();
    }

    @Override
    public Optional<Receipt> receipt(UUID poId, String ref) {
        return db.sql("select id, po_id, ref, received_by, received_at from grn where po_id = ? and ref = ?")
                .params(poId, ref)
                .query((rs, i) -> {
                    UUID id = rs.getObject(1, UUID.class);
                    var lines = new TreeMap<String, Integer>();
                    db.sql("select sku, qty from grn_line where grn_id = ?").param(id)
                            .query(r -> {
                                lines.put(r.getString(1), r.getInt(2));
                            });
                    return new Receipt(id, rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                            rs.getTimestamp(5).toInstant(), lines);
                }).optional();
    }

    @Override
    public void insertReceipt(Receipt r) {
        db.sql("insert into grn (id, po_id, ref, received_by, received_at) values (?, ?, ?, ?, ?)")
                .params(r.id(), r.poId(), r.ref(), r.receivedBy(), Timestamp.from(r.receivedAt())).update();
        r.lines().forEach((sku, qty) -> db.sql("insert into grn_line (grn_id, sku, qty) values (?, ?, ?)")
                .params(r.id(), sku, qty).update());
    }

    @Override
    public void updateReceived(UUID poId, PoStatus status, Map<String, Integer> receivedNow) {
        db.sql("update purchase_order set status = ? where id = ?").params(status.name(), poId).update();
        receivedNow.forEach((sku, qty) -> db.sql("update po_line set received = received + ? where po_id = ? and sku = ?")
                .params(qty, poId, sku).update());
    }

    @Override
    public Map<String, Long> onOrder(String store) {
        var out = new HashMap<String, Long>();
        db.sql("""
                        select l.sku, sum(l.qty - l.received) from po_line l join purchase_order p on p.id = l.po_id
                        where p.store_code = ? and p.status in ('ISSUED', 'PARTIALLY_RECEIVED') group by l.sku""")
                .param(store).query(rs -> {
                    out.put(rs.getString(1), rs.getLong(2));
                });
        return out;
    }
}
