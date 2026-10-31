package com.storesync.sales.application;

import com.storesync.outbox.application.Outbox;
import com.storesync.sales.application.SalesRepository.Shift;
import com.storesync.sales.domain.CashDrawer;
import com.storesync.sales.domain.ShiftState;
import com.storesync.shared.config.BusinessZone;
import com.storesync.shared.domain.DomainConflictException;
import com.storesync.shared.domain.DomainRuleException;
import com.storesync.shared.domain.Money;
import com.storesync.shared.error.ApiException;
import com.storesync.shared.security.Caller;
import com.storesync.shared.security.Role;
import com.storesync.store.application.StoreService;
import com.storesync.store.domain.Terminal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ShiftService {

    private final SalesRepository repo;
    private final StoreService stores;
    private final Outbox outbox;
    private final Clock clock;

    ShiftService(SalesRepository repo, StoreService stores, Outbox outbox, Clock clock) {
        this.repo = repo;
        this.stores = stores;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** A staff member opens a drawer for online selling. Retrying with the same id returns the same shift. */
    @Transactional
    public Shift open(Caller caller, String store, UUID id, BigDecimal openingFloat) {
        caller.requireStore(store, Role.CASHIER, Role.MANAGER);
        stores.store(store);
        var existing = repo.shift(id, false);
        if (existing.isPresent()) {
            var s = existing.get();
            if (s.store().equals(store) && s.cashier().equals(caller.subject()) && s.terminalId() == null) {
                return s;
            }
            throw ApiException.unprocessable("shift-id-reused", "Shift id already used");
        }
        var s = new Shift(id, store, null, caller.subject(), ShiftState.OPEN, Money.exact(openingFloat, "openingFloat"),
                clock.instant(), null, null, null, null, null);
        repo.insertShift(s);
        return s;
    }

    /** SHIFT_OPENED from a terminal's op log. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void openOffline(Terminal t, UUID id, String cashier, BigDecimal openingFloat, Instant openedAt) {
        var existing = repo.shift(id, false);
        if (existing.isPresent()) {
            if (Objects.equals(existing.get().terminalId(), t.id())) {
                return;
            }
            throw new DomainRuleException("shift-id-reused", "Shift id already used by another till");
        }
        requireStaff(t.storeCode(), cashier);
        repo.insertShift(new Shift(id, t.storeCode(), t.id(), cashier, ShiftState.OPEN,
                Money.exact(openingFloat, "openingFloat"), openedAt, null, null, null, null, null));
    }

    void requireStaff(String store, String subject) {
        var m = stores.staff(subject);
        if (m.isEmpty() || !m.get().storeCode().equals(store) || m.get().role() == Role.TERMINAL) {
            throw new DomainRuleException("not-store-staff", subject + " is not staff of store " + store);
        }
    }

    /** Lock for a sale or cash refund that needs the shift to be taking money. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Shift lockOpen(UUID id, String store) {
        Shift s = repo.shift(id, true).orElseThrow(() -> ApiException.notFound("shift"));
        if (!s.store().equals(store)) {
            throw ApiException.unprocessable("other-store-shift", "Shift belongs to another store");
        }
        if (!s.status().acceptsSales()) {
            throw new DomainConflictException("shift-not-open", "Shift is " + s.status());
        }
        return s;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Shift lock(UUID id) {
        return repo.shift(id, true).orElseThrow(() -> ApiException.notFound("shift"));
    }

    /** First sale makes the shift ACTIVE. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void touched(Shift s) {
        if (s.status() == ShiftState.OPEN) {
            repo.updateShift(with(s, s.status().moveTo(ShiftState.ACTIVE)));
        }
    }

    /** Online close: every sale went through the server, so the count is final. */
    @Transactional
    public Shift close(Caller caller, UUID id, BigDecimal countedCash) {
        Shift s = lock(id);
        caller.requireStore(s.store(), Role.CASHIER, Role.MANAGER);
        if (s.terminalId() != null) {
            throw ApiException.conflict("terminal-shift", "Terminal shifts are closed by the terminal's SHIFT_CLOSED op");
        }
        if (s.status() == ShiftState.CLOSED) {
            if (s.countedCash().compareTo(Money.exact(countedCash, "countedCash")) == 0) {
                return s;
            }
            throw new DomainConflictException("shift-closed", "Shift already closed with a different count");
        }
        var totals = repo.totals(id);
        return finish(new Shift(s.id(), s.store(), s.terminalId(), s.cashier(), s.status().moveTo(ShiftState.CLOSED),
                s.openingFloat(), s.openedAt(), clock.instant(), (int) totals.sales(),
                Money.exact(countedCash, "countedCash"), null, null), totals);
    }

    /** SHIFT_CLOSED from a terminal. Lands in CLOSING if some of the shift's sales have not been uploaded yet. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void closeOffline(Terminal t, UUID id, BigDecimal countedCash, int declaredSales, Instant closedAt) {
        Shift s = lock(id);
        if (!Objects.equals(s.terminalId(), t.id())) {
            throw new DomainRuleException("other-terminal-shift", "Shift belongs to another till");
        }
        BigDecimal counted = Money.exact(countedCash, "countedCash");
        if (s.status() == ShiftState.CLOSING || s.status() == ShiftState.CLOSED) {
            if (s.countedCash().compareTo(counted) == 0 && s.declaredSales() == declaredSales) {
                return;
            }
            throw new DomainConflictException("shift-closed", "Shift already closed with different figures");
        }
        var totals = repo.totals(id);
        var next = s.status().moveTo(ShiftState.afterClose(totals.sales(), declaredSales));
        var closing = new Shift(s.id(), s.store(), s.terminalId(), s.cashier(), next, s.openingFloat(), s.openedAt(),
                closedAt, declaredSales, counted, null, null);
        if (next == ShiftState.CLOSED) {
            finish(closing, totals);
        } else {
            repo.updateShift(closing);
        }
    }

    /** After a late sale for a CLOSING shift: close it once the server has every sale the till declared. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void maybeComplete(UUID id) {
        Shift s = lock(id);
        if (s.status() != ShiftState.CLOSING) {
            return;
        }
        var totals = repo.totals(id);
        if (ShiftState.afterClose(totals.sales(), s.declaredSales()) == ShiftState.CLOSED) {
            finish(with(s, s.status().moveTo(ShiftState.CLOSED)), totals);
        }
    }

    private Shift finish(Shift s, SalesRepository.ShiftTotals totals) {
        var drawer = new CashDrawer(s.openingFloat(), totals.cashTendered(), totals.changeGiven(), totals.cashRefunds());
        var done = new Shift(s.id(), s.store(), s.terminalId(), s.cashier(), ShiftState.CLOSED, s.openingFloat(),
                s.openedAt(), s.closedAt(), s.declaredSales(), s.countedCash(), drawer.expected(),
                drawer.variance(s.countedCash()));
        repo.updateShift(done);
        var data = new LinkedHashMap<String, Object>();
        data.put("store", done.store());
        data.put("shiftId", done.id().toString());
        data.put("terminalId", done.terminalId() == null ? null : done.terminalId().toString());
        data.put("cashier", done.cashier());
        data.put("sales", totals.sales());
        data.put("expectedCash", done.expectedCash().toPlainString());
        data.put("countedCash", done.countedCash().toPlainString());
        data.put("variance", done.variance().toPlainString());
        outbox.append(Outbox.SALES, done.store(), "ShiftClosed", data);
        return done;
    }

    private static Shift with(Shift s, ShiftState state) {
        return new Shift(s.id(), s.store(), s.terminalId(), s.cashier(), state, s.openingFloat(), s.openedAt(),
                s.closedAt(), s.declaredSales(), s.countedCash(), s.expectedCash(), s.variance());
    }

    public Shift get(Caller caller, UUID id) {
        Shift s = repo.shift(id, false).orElseThrow(() -> ApiException.notFound("shift"));
        caller.requireStore(s.store(), Role.CASHIER, Role.MANAGER, Role.TERMINAL);
        return s;
    }

    public record Reconciliation(String store, LocalDate day, long invoices, long voided, BigDecimal salesTotal,
                                 Map<String, BigDecimal> tenderedByMethod, BigDecimal changeGiven,
                                 long returns, Map<String, BigDecimal> refundsByMethod, BigDecimal netTakings,
                                 boolean tenderBalanced, List<Shift> shifts, long shiftsStillOpen,
                                 long shiftsWaitingForSync, BigDecimal cashVariance) {
    }

    /**
     * End-of-day view for one store and IST day: what was sold, how it was paid, what was refunded, and each
     * shift's drawer. {@code tenderBalanced} checks that payments minus change equal the invoiced total.
     */
    public Reconciliation reconcile(Caller caller, String store, LocalDate day) {
        caller.requireStore(store, Role.MANAGER);
        stores.store(store);
        Instant from = day.atStartOfDay(BusinessZone.ZONE).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(BusinessZone.ZONE).toInstant();
        var f = repo.dayFigures(store, from, to);
        var shifts = repo.shifts(store, from, to);
        BigDecimal tendered = f.tenderedByMethod().values().stream().reduce(Money.ZERO, BigDecimal::add);
        BigDecimal refunds = f.refundsByMethod().values().stream().reduce(Money.ZERO, BigDecimal::add);
        BigDecimal variance = shifts.stream().filter(s -> s.variance() != null).map(Shift::variance)
                .reduce(Money.ZERO, BigDecimal::add);
        return new Reconciliation(store, day, f.invoices(), f.voided(), f.salesTotal(), f.tenderedByMethod(),
                f.changeGiven(), f.returns(), f.refundsByMethod(), f.salesTotal().subtract(refunds),
                tendered.subtract(f.changeGiven()).compareTo(f.salesTotal()) == 0, shifts,
                shifts.stream().filter(s -> s.status().acceptsSales()).count(),
                shifts.stream().filter(s -> s.status() == ShiftState.CLOSING).count(), variance);
    }
}
