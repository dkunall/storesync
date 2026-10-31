package com.storesync.shared.security;

import com.storesync.shared.error.ApiException;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

/**
 * A JWT caller, passed explicitly into application services so authorisation is visible in the code.
 *
 * @param storeCode  the one store a MANAGER, CASHIER or TERMINAL belongs to; null for ADMIN
 * @param terminalId set only for TERMINAL callers
 */
public record Caller(String subject, Set<Role> roles, String storeCode, UUID terminalId) {

    public Caller {
        roles = Set.copyOf(roles);
    }

    public static Caller system(String job) {
        return new Caller("system:" + job, Set.of(Role.ADMIN), null, null);
    }

    public boolean has(Role r) {
        return roles.contains(r);
    }

    public boolean hasAny(Role... rs) {
        return Arrays.stream(rs).anyMatch(roles::contains);
    }

    public void require(Role... rs) {
        if (!hasAny(rs)) {
            throw ApiException.forbidden("forbidden", "This needs one of the roles " + Arrays.toString(rs));
        }
    }

    /** ADMIN may act on any store; everyone else only on their own and only with one of {@code rs}. */
    public void requireStore(String store, Role... rs) {
        if (has(Role.ADMIN)) {
            return;
        }
        require(rs);
        if (!store.equals(storeCode)) {
            throw ApiException.forbidden("other-store", "You can only act on store " + storeCode);
        }
    }
}
