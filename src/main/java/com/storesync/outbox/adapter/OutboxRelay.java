package com.storesync.outbox.adapter;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Publishes committed outbox rows in id order. Rows are claimed with SKIP LOCKED so several instances can run
 * the relay; a row is marked published only after the broker acknowledged it, so a crash re-sends rather than
 * loses (consumers dedupe on eventId).
 */
@Component
@ConditionalOnProperty(name = "storesync.jobs.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcClient db;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry meters;

    OutboxRelay(JdbcClient db, KafkaTemplate<String, String> kafka, TransactionTemplate tx, Clock clock,
                MeterRegistry meters) {
        this.db = db;
        this.kafka = kafka;
        this.tx = tx;
        this.clock = clock;
        this.meters = meters;
    }

    private record Row(long id, String topic, String key, String payload) {
    }

    @Scheduled(fixedDelayString = "${storesync.outbox.poll:PT0.2S}")
    void relay() {
        try {
            Integer sent;
            do {
                sent = tx.execute(s -> publishBatch());
            } while (sent != null && sent == 100);
        } catch (RuntimeException e) {
            log.warn("outbox relay failed, will retry: {}", e.toString());
        }
    }

    private int publishBatch() {
        List<Row> rows = db.sql("""
                        select id, topic, msg_key, payload::text from outbox where published_at is null
                        order by id limit 100 for update skip locked""")
                .query((rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();
        if (rows.isEmpty()) {
            return 0;
        }
        try {
            var futures = rows.stream().map(r -> kafka.send(r.topic(), r.key(), r.payload())).toList();
            for (var f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            throw new IllegalStateException("broker did not acknowledge", e);
        }
        db.sql("update outbox set published_at = :at where id in (:ids)")
                .param("at", Timestamp.from(clock.instant()))
                .param("ids", rows.stream().map(Row::id).toList()).update();
        meters.counter("storesync.outbox.published").increment(rows.size());
        return rows.size();
    }
}
