package com.storesync.inventory.adapter;

import com.storesync.inventory.application.StockRepository;
import com.storesync.inventory.domain.PnCounter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

@Repository
class JdbcStockRepository implements StockRepository {

    private final JdbcClient db;

    JdbcStockRepository(JdbcClient db) {
        this.db = db;
    }

    @Override
    public Map<String, Long> lock(String store, List<String> sortedSkus) {
        for (String sku : sortedSkus) {
            db.sql("insert into stock_item (store_code, sku, on_hand) values (?, ?, 0) on conflict do nothing")
                    .params(store, sku).update();
        }
        var out = new HashMap<String, Long>();
        db.sql("select sku, on_hand from stock_item where store_code = :s and sku in (:skus) order by sku for update")
                .param("s", store).param("skus", sortedSkus)
                .query((rs, i) -> out.put(rs.getString(1), rs.getLong(2))).list();
        return out;
    }

    @Override
    public void add(String store, String sku, String node, long inc, long dec, long newOnHand) {
        db.sql("""
                        insert into stock_counter (store_code, sku, node, inc, dec) values (?, ?, ?, ?, ?)
                        on conflict (store_code, sku, node) do update
                        set inc = stock_counter.inc + excluded.inc, dec = stock_counter.dec + excluded.dec""")
                .params(store, sku, node, inc, dec).update();
        db.sql("update stock_item set on_hand = ?, change_txid = pg_current_xact_id() where store_code = ? and sku = ?")
                .params(newOnHand, store, sku).update();
    }

    @Override
    public void movement(String store, String sku, String node, long delta, long onHand, String cause, String ref, Instant at) {
        db.sql("""
                        insert into stock_movement (store_code, sku, node, delta, on_hand, cause, ref, at)
                        values (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(store, sku, node, delta, onHand, cause, ref, Timestamp.from(at)).update();
    }

    @Override
    public List<StockState> stock(String store, Collection<String> onlySkus) {
        String filter = onlySkus.isEmpty() ? "" : " and i.sku in (:skus)";
        var q = db.sql("""
                select i.sku, i.on_hand, c.node, c.inc, c.dec from stock_item i
                left join stock_counter c on c.store_code = i.store_code and c.sku = i.sku
                where i.store_code = :s""" + filter + " order by i.sku, c.node").param("s", store);
        if (!onlySkus.isEmpty()) {
            q = q.param("skus", List.copyOf(onlySkus));
        }
        return collect(q);
    }

    @Override
    public List<StockState> changedSince(String store, long cursor) {
        return collect(db.sql("""
                        select i.sku, i.on_hand, c.node, c.inc, c.dec from stock_item i
                        left join stock_counter c on c.store_code = i.store_code and c.sku = i.sku
                        where i.store_code = :s and i.change_txid >= :c::text::xid8 order by i.sku, c.node""")
                .param("s", store).param("c", Long.toString(cursor)));
    }

    private record Row(String sku, long onHand, String node, long inc, long dec) {
    }

    private static List<StockState> collect(JdbcClient.StatementSpec q) {
        var rows = q.query((rs, i) -> new Row(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getLong(5))).list();
        var bySku = new LinkedHashMap<String, List<Row>>();
        rows.forEach(r -> bySku.computeIfAbsent(r.sku(), k -> new ArrayList<>()).add(r));
        var out = new ArrayList<StockState>();
        bySku.forEach((sku, rs) -> {
            var inc = new TreeMap<String, Long>();
            var dec = new TreeMap<String, Long>();
            for (Row r : rs) {
                if (r.node() != null) {
                    inc.put(r.node(), r.inc());
                    dec.put(r.node(), r.dec());
                }
            }
            out.add(new StockState(sku, rs.getFirst().onHand(), PnCounter.of(inc, dec)));
        });
        return out;
    }

    @Override
    public Optional<Transfer> transfer(UUID id, boolean lock) {
        var head = db.sql("select id, from_store, to_store, status, created_by, created_at, received_at from transfer where id = ?"
                        + (lock ? " for update" : "")).param(id)
                .query((rs, i) -> new Object[]{rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getTimestamp(6).toInstant(), rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant()})
                .optional();
        if (head.isEmpty()) {
            return Optional.empty();
        }
        var qty = new TreeMap<String, Integer>();
        var received = new TreeMap<String, Integer>();
        db.sql("select sku, qty, received from transfer_line where transfer_id = ? order by sku").param(id)
                .query((rs, i) -> {
                    qty.put(rs.getString(1), rs.getInt(2));
                    int r = rs.getInt(3);
                    if (!rs.wasNull()) {
                        received.put(rs.getString(1), r);
                    }
                    return null;
                }).list();
        Object[] h = head.get();
        return Optional.of(new Transfer(id, (String) h[0], (String) h[1], (String) h[2], (String) h[3], (Instant) h[4],
                (Instant) h[5], qty, received));
    }

    @Override
    public void insertTransfer(Transfer t) {
        db.sql("insert into transfer (id, from_store, to_store, status, created_by, created_at) values (?, ?, ?, ?, ?, ?)")
                .params(t.id(), t.fromStore(), t.toStore(), t.status(), t.createdBy(), Timestamp.from(t.createdAt())).update();
        t.qty().forEach((sku, q) -> db.sql("insert into transfer_line (transfer_id, sku, qty) values (?, ?, ?)")
                .params(t.id(), sku, q).update());
    }

    @Override
    public void receiveTransfer(UUID id, Map<String, Integer> received, Instant at) {
        received.forEach((sku, q) -> db.sql("update transfer_line set received = ? where transfer_id = ? and sku = ?")
                .params(q, id, sku).update());
        db.sql("update transfer set status = 'RECEIVED', received_at = ? where id = ?").params(Timestamp.from(at), id).update();
    }

    @Override
    public Optional<StockCount> count(UUID id) {
        return db.sql("select id, store_code, sku, book, counted, by_subject, at from stock_count where id = ?").param(id)
                .query((rs, i) -> new StockCount(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5), rs.getString(6), rs.getTimestamp(7).toInstant())).optional();
    }

    @Override
    public void insertCount(StockCount c) {
        db.sql("insert into stock_count (id, store_code, sku, book, counted, by_subject, at) values (?, ?, ?, ?, ?, ?, ?)")
                .params(c.id(), c.store(), c.sku(), c.book(), c.counted(), c.by(), Timestamp.from(c.at())).update();
    }

    @Override
    public void lockTransferId(UUID id) {
        db.sql("select pg_advisory_xact_lock(hashtextextended(?, 7302))").param("transfer:" + id)
                .query((rs, i) -> 1).list();
    }
}
