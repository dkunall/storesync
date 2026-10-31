package com.storesync.shared.security;

import java.util.UUID;

/** What the store module knows about a subject: a staff member of one store, or an enrolled terminal. */
public record Principal(Role role, String storeCode, UUID terminalId) {
}
