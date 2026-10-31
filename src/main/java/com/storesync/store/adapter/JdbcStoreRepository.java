package com.storesync.store.adapter;

import com.storesync.shared.security.Principal;
import com.storesync.shared.security.Principals;
import com.storesync.shared.security.Role;
import com.storesync.store.application.StoreRepository;
import com.storesync.store.domain.Store;
import com.storesync.store.domain.Terminal;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcStoreRepository implements StoreRepository, Principals {

    private static final String TERMINAL = "select id, store_code, number, subject, epoch, last_seq, last_push_at, enrolled_at from terminal ";
    private static final RowMapper<Terminal> TERMINAL_ROW = (rs, i) -> new Terminal(rs.getObject(1, UUID.class),
            rs.getString(2), rs.getInt(3), rs.getString(4), rs.getInt(5), rs.getLong(6), instant(rs.getTimestamp(7)),
            instant(rs.getTimestamp(8)));
    private static final RowMapper<Store> STORE_ROW = (rs, i) -> new Store(rs.getString(1), rs.getString(2),
            rs.getString(3), rs.getString(4));

    private final JdbcClient db;

    JdbcStoreRepository(JdbcClient db) {
        this.db = db;
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    @Override
    public boolean insertStore(Store s) {
        return db.sql("insert into store (code, name, state_code, gstin) values (?, ?, ?, ?) on conflict do nothing")
                .params(s.code(), s.name(), s.stateCode(), s.gstin()).update() == 1;
    }

    @Override
    public Optional<Store> store(String code) {
        return db.sql("select code, name, state_code, gstin from store where code = ?").param(code).query(STORE_ROW).optional();
    }

    @Override
    public List<Store> stores() {
        return db.sql("select code, name, state_code, gstin from store order by code").query(STORE_ROW).list();
    }

    @Override
    public void upsertStaff(StaffMember m) {
        db.sql("""
                        insert into staff (subject, store_code, role, name) values (?, ?, ?, ?)
                        on conflict (subject) do update set store_code = excluded.store_code, role = excluded.role,
                        name = excluded.name""")
                .params(m.subject(), m.storeCode(), m.role().name(), m.name()).update();
    }

    @Override
    public Optional<StaffMember> staff(String subject) {
        return db.sql("select subject, store_code, role, name from staff where subject = ?").param(subject)
                .query((rs, i) -> new StaffMember(rs.getString(1), rs.getString(2), Role.valueOf(rs.getString(3)),
                        rs.getString(4))).optional();
    }

    @Override
    public boolean subjectTaken(String subject) {
        return db.sql("select exists(select 1 from staff where subject = :s) or exists(select 1 from terminal where subject = :s)")
                .param("s", subject).query(Boolean.class).single();
    }

    @Override
    public void insertTerminal(Terminal t) {
        db.sql("insert into terminal (id, store_code, number, subject, epoch, last_seq, enrolled_at) values (?, ?, ?, ?, ?, ?, ?)")
                .params(t.id(), t.storeCode(), t.number(), t.subject(), t.epoch(), t.lastSeq(), Timestamp.from(t.enrolledAt()))
                .update();
    }

    @Override
    public Optional<Terminal> terminal(UUID id) {
        return db.sql(TERMINAL + "where id = ?").param(id).query(TERMINAL_ROW).optional();
    }

    @Override
    public Optional<Terminal> lockTerminal(UUID id) {
        return db.sql(TERMINAL + "where id = ? for update").param(id).query(TERMINAL_ROW).optional();
    }

    @Override
    public List<Terminal> terminals(String store) {
        return db.sql(TERMINAL + "where store_code = ? order by number").param(store).query(TERMINAL_ROW).list();
    }

    @Override
    public void reEnrol(UUID id, int epoch, Instant at) {
        db.sql("update terminal set epoch = ?, last_seq = 0, enrolled_at = ? where id = ?")
                .params(epoch, Timestamp.from(at), id).update();
    }

    @Override
    public void advance(UUID id, long lastSeq, Instant at) {
        db.sql("update terminal set last_seq = greatest(last_seq, ?), last_push_at = ? where id = ?")
                .params(lastSeq, Timestamp.from(at), id).update();
    }

    @Override
    public Optional<Principal> lookup(String subject) {
        var staff = staff(subject);
        if (staff.isPresent()) {
            return Optional.of(new Principal(staff.get().role(), staff.get().storeCode(), null));
        }
        return db.sql("select id, store_code from terminal where subject = ?").param(subject)
                .query((rs, i) -> new Principal(Role.TERMINAL, rs.getString(2), rs.getObject(1, UUID.class))).optional();
    }
}
