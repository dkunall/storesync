package com.storesync.shared.config;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** All stores trade in India, so business days and financial years are counted in IST. */
public final class BusinessZone {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private BusinessZone() {
    }

    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }

    public static LocalDate dayOf(Instant at) {
        return LocalDate.ofInstant(at, ZONE);
    }
}
