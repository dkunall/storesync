package com.storesync.sales.domain;

import com.storesync.shared.domain.DomainConflictException;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * A cashier's shift on one terminal. ACTIVE after the first sale. CLOSING when the terminal has closed the drawer
 * but the server has not yet received every sale the terminal says the shift made; CLOSED once it has, which is when
 * the cash variance is final.
 */
public enum ShiftState {
    OPEN, ACTIVE, CLOSING, CLOSED;

    private static final Map<ShiftState, Set<ShiftState>> NEXT = Map.of(
            OPEN, EnumSet.of(ACTIVE, CLOSING, CLOSED),
            ACTIVE, EnumSet.of(CLOSING, CLOSED),
            CLOSING, EnumSet.of(CLOSED),
            CLOSED, EnumSet.noneOf(ShiftState.class));

    public boolean canMoveTo(ShiftState next) {
        return NEXT.get(this).contains(next);
    }

    public ShiftState moveTo(ShiftState next) {
        if (!canMoveTo(next)) {
            throw new DomainConflictException("bad-shift-transition", "A " + this + " shift cannot become " + next);
        }
        return next;
    }

    public boolean acceptsSales() {
        return this == OPEN || this == ACTIVE;
    }

    /** Where a close lands, given how many of the declared sales the server has. */
    public static ShiftState afterClose(long syncedSales, long declaredSales) {
        return syncedSales >= declaredSales ? CLOSED : CLOSING;
    }
}
