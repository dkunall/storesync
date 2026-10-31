package com.storesync.outbox.application;

import java.util.UUID;

/** Consumer-side dedup, written in the same transaction as the effect. */
public interface ProcessedEvents {

    /** @return false if {@code consumer} already handled {@code eventId} */
    boolean markProcessed(String consumer, UUID eventId);
}
