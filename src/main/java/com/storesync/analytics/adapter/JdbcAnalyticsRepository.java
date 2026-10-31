package com.storesync.analytics.adapter;

import com.storesync.analytics.application.AnalyticsRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
class JdbcAnalyticsRepository implements AnalyticsRepository {

    private final JdbcClient db;

    JdbcAnalyticsRepository(JdbcClient db) {
        this.db = db;
    }

    @Override
    public void addSkuDay(String store, LocalDate day, String sku, long qty, BigDecimal net, BigDecimal tax) {
        db.sql("""
                        insert into sales_daily (store_code, day, sku, qty, net, tax) values (?, ?, ?, ?, ?, ?)
                        on conflict (store_code, day, sku) do update set qty = sales_daily.qty + excluded.qty,
                            net = sales_daily.net + excluded.net, tax = sales_daily.tax + excluded.tax""")
                .params(store, Date.valueOf(day), sku, qty, net, tax).update();
    }

    @Override
    public void addStoreDay(String store, LocalDate day, long invoices, long returns, BigDecimal total) {
        db.sql("""
                        insert into sales_daily_store (store_code, day, invoices, returns, total) values (?, ?, ?, ?, ?)
                        on conflict (store_code, day) do update set invoices = sales_daily_store.invoices + excluded.invoices,
                            returns = sales_daily_store.returns + excluded.returns,
                            total = sales_daily_store.total + excluded.total""")
                .params(store, Date.valueOf(day), invoices, returns, total).update();
    }

    @Override
    public boolean insertAlert(String store, String sku, String type, String detail, UUID eventId, Instant at) {
        return db.sql("""
                        insert into alert (store_code, sku, type, detail, event_id, created_at) values (?, ?, ?, ?, ?, ?)
                        on conflict (event_id, type) do nothing""")
                .params(store, sku, type, detail, eventId, Timestamp.from(at)).update() == 1;
    }

    @Override
    public List<Alert> alerts(String store, int limit) {
        return db.sql("""
                        select id, store_code, sku, type, detail, event_id, created_at from alert
                        where store_code = ? order by id desc limit ?""")
                .params(store, limit)
                .query((rs, i) -> new Alert(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getObject(6, UUID.class), rs.getTimestamp(7).toInstant())).list();
    }

    @Override
    public List<StoreDay> days(String store, LocalDate from, LocalDate to) {
        return db.sql("""
                        select day, invoices, returns, total from sales_daily_store
                        where store_code = ? and day between ? and ? order by day""")
                .params(store, Date.valueOf(from), Date.valueOf(to))
                .query((rs, i) -> new StoreDay(rs.getDate(1).toLocalDate(), rs.getLong(2), rs.getLong(3),
                        rs.getBigDecimal(4))).list();
    }

    @Override
    public List<SkuTotal> skus(String store, LocalDate from, LocalDate to) {
        return db.sql("""
                        select sku, sum(qty), sum(net), sum(tax) from sales_daily
                        where store_code = ? and day between ? and ? group by sku order by sum(net) desc, sku""")
                .params(store, Date.valueOf(from), Date.valueOf(to))
                .query((rs, i) -> new SkuTotal(rs.getString(1), rs.getLong(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4))).list();
    }

    @Override
    public Map<LocalDate, Long> unitsByDay(String store, String sku, LocalDate from, LocalDate to) {
        var out = new LinkedHashMap<LocalDate, Long>();
        db.sql("""
                        select day, qty from sales_daily where store_code = ? and sku = ? and day between ? and ?
                        order by day""")
                .params(store, sku, Date.valueOf(from), Date.valueOf(to))
                .query(rs -> {
                    out.put(rs.getDate(1).toLocalDate(), rs.getLong(2));
                });
        return out;
    }
}
