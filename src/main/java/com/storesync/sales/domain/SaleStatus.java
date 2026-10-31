package com.storesync.sales.domain;

import com.storesync.shared.domain.DomainConflictException;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * A sale's life. OPEN, TENDERING and PAID happen on the terminal (or inside one online request); the server only
 * stores sales from SYNCED on, plus VOIDED invoices so that the number series has no unexplained gaps.
 */
public enum SaleStatus {
    OPEN, TENDERING, PAID, SYNCED, PARTIALLY_RETURNED, RETURNED, VOIDED;

    private static final Map<SaleStatus, Set<SaleStatus>> NEXT = Map.of(
            OPEN, EnumSet.of(TENDERING, VOIDED),
            TENDERING, EnumSet.of(PAID, OPEN),
            PAID, EnumSet.of(SYNCED),
            SYNCED, EnumSet.of(PARTIALLY_RETURNED, RETURNED),
            PARTIALLY_RETURNED, EnumSet.of(PARTIALLY_RETURNED, RETURNED),
            RETURNED, EnumSet.noneOf(SaleStatus.class),
            VOIDED, EnumSet.noneOf(SaleStatus.class));

    public boolean canMoveTo(SaleStatus next) {
        return NEXT.get(this).contains(next);
    }

    public SaleStatus moveTo(SaleStatus next) {
        if (!canMoveTo(next)) {
            throw new DomainConflictException("bad-sale-transition", "A " + this + " sale cannot become " + next);
        }
        return next;
    }

    public boolean returnable() {
        return this == SYNCED || this == PARTIALLY_RETURNED;
    }
}
