package com.storesync.sync.adapter;

import com.storesync.sync.application.SyncRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcSyncRepository implements SyncRepository {

    private final JdbcClient db;

    JdbcSyncRepository(JdbcClient db) {
        this.db = db;
    }

    @Override
    public Optional<LoggedOp> find(UUID terminalId, int epoch, long seq) {
        return db.sql("""
                        select seq, op_type, op_version, payload_hash, status, reason from op_log
                        where terminal_id = ? and epoch = ? and seq = ?""")
                .params(terminalId, epoch, seq)
                .query((rs, i) -> new LoggedOp(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                        rs.getString(5), rs.getString(6))).optional();
    }

    @Override
    public void log(UUID terminalId, int epoch, long seq, String type, int version, String payloadJson,
                    String payloadHash, String status, String reason, Instant at) {
        db.sql("""
                        insert into op_log (terminal_id, epoch, seq, op_type, op_version, payload, payload_hash, status,
                                            reason, received_at)
                        values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)""")
                .params(terminalId, epoch, seq, type, version, payloadJson, payloadHash, status, reason,
                        Timestamp.from(at)).update();
    }

    @Override
    public long snapshotFloor() {
        return db.sql("select pg_snapshot_xmin(pg_current_snapshot())::text::bigint").query(Long.class).single();
    }
}
