package com.storesync.analytics.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storesync.analytics.application.AnalyticsService;
import com.storesync.outbox.application.EventEnvelope;
import com.storesync.outbox.application.Outbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "storesync.jobs.enabled", havingValue = "true", matchIfMissing = true)
class AnalyticsListeners {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsListeners.class);

    private final AnalyticsService analytics;
    private final ObjectMapper json;

    AnalyticsListeners(AnalyticsService analytics, ObjectMapper json) {
        this.analytics = analytics;
        this.json = json;
    }

    @KafkaListener(topics = Outbox.SALES, groupId = AnalyticsService.ROLLUP)
    void rollup(String message) {
        EventEnvelope e = parse(message);
        if (e != null) {
            analytics.rollup(e);
        }
    }

    @KafkaListener(topics = {Outbox.SALES, Outbox.STOCK}, groupId = AnalyticsService.ALERTS)
    void alerts(String message) {
        EventEnvelope e = parse(message);
        if (e != null) {
            analytics.alert(e);
        }
    }

    /** A message that is not an envelope can never succeed, so it is logged and skipped instead of retried. */
    private EventEnvelope parse(String message) {
        try {
            EventEnvelope e = json.readValue(message, EventEnvelope.class);
            if (e.eventId() == null || e.type() == null || e.data() == null) {
                throw new IllegalArgumentException("incomplete envelope");
            }
            return e;
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            log.warn("skipping unreadable event: {}", ex.getMessage());
            return null;
        }
    }
}
