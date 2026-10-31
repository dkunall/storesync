package com.storesync.outbox.adapter;

import com.storesync.outbox.application.ProcessedEvents;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Repository
class JdbcProcessedEvents implements ProcessedEvents {

    private final JdbcClient db;

    JdbcProcessedEvents(JdbcClient db) {
        this.db = db;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(String consumer, UUID eventId) {
        return db.sql("insert into processed_event (consumer, event_id, at) values (?, ?, now()) on conflict do nothing")
                .params(consumer, eventId).update() == 1;
    }
}
