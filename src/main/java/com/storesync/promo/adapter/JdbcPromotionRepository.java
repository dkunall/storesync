package com.storesync.promo.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storesync.promo.application.PromotionRepository;
import com.storesync.promo.domain.Promotion;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcPromotionRepository implements PromotionRepository {

    private static final String SELECT = """
            select id, code, type, rules::text, priority, stackable, starts_at, ends_at, active from promotion """;

    private final JdbcClient db;
    private final ObjectMapper json;
    private final RowMapper<Promotion> row;

    JdbcPromotionRepository(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
        this.row = (rs, i) -> new Promotion(rs.getObject(1, UUID.class), rs.getString(2),
                Promotion.Type.valueOf(rs.getString(3)), read(rs.getString(4)), rs.getInt(5), rs.getBoolean(6),
                instant(rs.getTimestamp(7)), instant(rs.getTimestamp(8)), rs.getBoolean(9));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private Promotion.Rules read(String s) {
        try {
            return json.readValue(s, Promotion.Rules.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean insert(Promotion p) {
        try {
            return db.sql("""
                            insert into promotion (id, code, type, rules, priority, stackable, starts_at, ends_at, active)
                            values (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?) on conflict do nothing""")
                    .params(p.id(), p.code(), p.type().name(), json.writeValueAsString(p.rules()), p.priority(),
                            p.stackable(), ts(p.startsAt()), ts(p.endsAt()), p.active()).update() == 1;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Optional<Promotion> find(String code) {
        return db.sql(SELECT + "where code = ?").param(code).query(row).optional();
    }

    @Override
    public void setActive(String code, boolean active) {
        db.sql("update promotion set active = ?, change_txid = pg_current_xact_id() where code = ?").params(active, code).update();
    }

    @Override
    public List<Promotion> all() {
        return db.sql(SELECT + "order by priority desc, code").query(row).list();
    }

    @Override
    public List<Promotion> changedSince(long cursor) {
        return db.sql(SELECT + "where change_txid >= ?::text::xid8 order by code").param(Long.toString(cursor)).query(row).list();
    }
}
