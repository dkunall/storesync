package com.storesync.outbox.adapter;

import com.storesync.outbox.application.EventEnvelope;
import com.storesync.outbox.application.Outbox;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

@Component
class JdbcOutbox implements Outbox {

    private final JdbcClient db;
    private final ObjectMapper json;
    private final Clock clock;

    JdbcOutbox(JdbcClient db, ObjectMapper json, Clock clock) {
        this.db = db;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String topic, String key, String type, Map<String, Object> data) {
        var env = new EventEnvelope(UUID.randomUUID(), type, clock.instant(), data);
        String payload;
        try {
            payload = json.writeValueAsString(env);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        db.sql("insert into outbox (event_id, topic, msg_key, type, payload, created_at) values (?, ?, ?, ?, ?::jsonb, ?)")
                .params(env.eventId(), topic, key, type, payload, Timestamp.from(env.at())).update();
    }
}
