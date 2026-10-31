package com.storesync.catalog.adapter;

import com.storesync.catalog.application.ProductRepository;
import com.storesync.catalog.domain.Product;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcProductRepository implements ProductRepository {

    private static final String SELECT = """
            select sku, barcode, name, hsn, tax_rate, price, cost, supplier_id, low_stock_level, active from product """;
    private static final RowMapper<Product> ROW = (rs, i) -> new Product(rs.getString(1), rs.getString(2),
            rs.getString(3), rs.getString(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7),
            rs.getObject(8, UUID.class), rs.getInt(9), rs.getBoolean(10));

    private final JdbcClient db;

    JdbcProductRepository(JdbcClient db) {
        this.db = db;
    }

    @Override
    public boolean insert(Product p) {
        return db.sql("""
                        insert into product (sku, barcode, name, hsn, tax_rate, price, cost, supplier_id, low_stock_level, active)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict do nothing""")
                .params(p.sku(), p.barcode(), p.name(), p.hsn(), p.taxRate(), p.price(), p.cost(), p.supplierId(),
                        p.lowStockLevel(), p.active()).update() == 1;
    }

    @Override
    public Optional<Product> update(Product p) {
        var before = db.sql(SELECT + "where sku = ? for update").param(p.sku()).query(ROW).optional();
        if (before.isPresent()) {
            db.sql("""
                            update product set barcode = ?, name = ?, hsn = ?, tax_rate = ?, price = ?, cost = ?,
                            supplier_id = ?, low_stock_level = ?, active = ?, change_txid = pg_current_xact_id()
                            where sku = ?""")
                    .params(p.barcode(), p.name(), p.hsn(), p.taxRate(), p.price(), p.cost(), p.supplierId(),
                            p.lowStockLevel(), p.active(), p.sku()).update();
        }
        return before;
    }

    @Override
    public Optional<Product> find(String sku) {
        return db.sql(SELECT + "where sku = ?").param(sku).query(ROW).optional();
    }

    @Override
    public Optional<Product> byBarcode(String barcode) {
        return db.sql(SELECT + "where barcode = ?").param(barcode).query(ROW).optional();
    }

    @Override
    public Map<String, Product> find(Collection<String> skus) {
        var out = new LinkedHashMap<String, Product>();
        if (skus.isEmpty()) {
            return out;
        }
        db.sql(SELECT + "where sku in (:skus)").param("skus", List.copyOf(skus)).query(ROW).list()
                .forEach(p -> out.put(p.sku(), p));
        return out;
    }

    @Override
    public List<Product> changedSince(long cursor) {
        return db.sql(SELECT + "where change_txid >= ?::text::xid8 order by sku").param(Long.toString(cursor)).query(ROW).list();
    }

    @Override
    public List<Product> bySupplier(UUID supplierId) {
        return db.sql(SELECT + "where supplier_id = ? and active order by sku").param(supplierId).query(ROW).list();
    }

    @Override
    public List<Product> all() {
        return db.sql(SELECT + "order by sku").query(ROW).list();
    }
}
