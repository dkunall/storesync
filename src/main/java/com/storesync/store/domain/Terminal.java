package com.storesync.store.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A till. {@code epoch} goes up each time it is re-enrolled (new device, wiped storage); ops from an older epoch
 * are refused. {@code lastSeq} is the highest op sequence the server has consumed in the current epoch.
 */
public record Terminal(UUID id, String storeCode, int number, String subject, int epoch, long lastSeq,
                       Instant lastPushAt, Instant enrolledAt) {

    public String node() {
        return "t-" + id;
    }
}
