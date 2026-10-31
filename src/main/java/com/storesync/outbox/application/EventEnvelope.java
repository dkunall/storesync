package com.storesync.outbox.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** What goes on the wire. {@code eventId} is the consumers' dedup key. */
public record EventEnvelope(UUID eventId, String type, Instant at, Map<String, Object> data) {
}
