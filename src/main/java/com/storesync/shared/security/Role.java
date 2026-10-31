package com.storesync.shared.security;

/**
 * ADMIN is head office (catalog, promotions, stores, purchasing for any store); its subjects come from
 * configuration. MANAGER and CASHIER are store staff, recorded per store. TERMINAL is an enrolled POS device's
 * service account and can only sync for itself. None of these come from token claims.
 */
public enum Role { ADMIN, MANAGER, CASHIER, TERMINAL }
