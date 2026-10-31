package com.storesync.sync.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SyncRepository {

    record LoggedOp(long seq, String type, int version, String payloadHash, String status, String reason) {
    }

    Optional<LoggedOp> find(UUID terminalId, int epoch, long seq);

    void log(UUID terminalId, int epoch, long seq, String type, int version, String payloadJson, String payloadHash,
             String status, String reason, Instant at);

    /**
     * Oldest transaction id still running. Everything below it has committed or never will, so a client that
     * pulls again from this value cannot miss a change that commits late.
     */
    long snapshotFloor();
}
