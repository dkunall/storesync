package com.storesync.sales.application;

import com.storesync.sales.domain.FinancialYear;
import com.storesync.sales.domain.InvoiceBlocks;
import com.storesync.sales.domain.InvoiceNumber;
import com.storesync.shared.config.BusinessZone;
import com.storesync.shared.config.StoreSyncProperties;
import com.storesync.shared.domain.DomainConflictException;
import com.storesync.shared.error.ApiException;
import com.storesync.shared.security.Caller;
import com.storesync.shared.security.Role;
import com.storesync.store.application.StoreService;
import com.storesync.store.domain.Terminal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Invoice numbers. Online sales draw one number at a time from the store's server series (terminal 00) under a row
 * lock. Terminals get whole blocks of their own series in advance so they can keep numbering while offline.
 */
@Service
public class InvoiceService {

    public record Allocation(UUID terminalId, String series, InvoiceBlocks.Block block) {
    }

    private final SalesRepository repo;
    private final StoreService stores;
    private final StoreSyncProperties props;
    private final Clock clock;

    InvoiceService(SalesRepository repo, StoreService stores, StoreSyncProperties props, Clock clock) {
        this.repo = repo;
        this.stores = stores;
        this.props = props;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String nextOnline(String store, LocalDate day) {
        var fy = FinancialYear.of(day);
        return next(InvoiceNumber.series(store, 0, fy), store, fy);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String nextCreditNote(String store, LocalDate day) {
        var fy = FinancialYear.of(day);
        return next(InvoiceNumber.creditNoteSeries(store, fy), store, fy);
    }

    private String next(String series, String store, FinancialYear fy) {
        int n = repo.lockSeries(series, store, null, fy.startYear());
        if (n > InvoiceNumber.MAX) {
            throw new DomainConflictException("series-exhausted", "Series " + series + " is used up for this year");
        }
        repo.setNext(series, n + 1);
        return new InvoiceNumber(series, n).toString();
    }

    /** A terminal asks for more numbers when it is running low; its manager can do it for it. */
    @Transactional
    public Allocation allocate(Caller caller, UUID terminalId) {
        Terminal t = stores.terminal(terminalId);
        if (caller.has(Role.TERMINAL)) {
            if (!terminalId.equals(caller.terminalId())) {
                throw ApiException.forbidden("other-terminal", "A terminal can only ask for its own numbers");
            }
        } else {
            caller.requireStore(t.storeCode(), Role.MANAGER);
        }
        var fy = FinancialYear.of(BusinessZone.today(clock));
        String series = InvoiceNumber.series(t.storeCode(), t.number(), fy);
        int next = repo.lockSeries(series, t.storeCode(), t.id(), fy.startYear());
        var block = InvoiceBlocks.allocate(next, props.invoices().blockSize());
        repo.setNext(series, block.last() + 1);
        repo.insertBlock(series, block, t.id(), clock.instant());
        return new Allocation(t.id(), series, block);
    }

    public InvoiceBlocks blocks(Terminal t, FinancialYear fy) {
        String series = InvoiceNumber.series(t.storeCode(), t.number(), fy);
        return new InvoiceBlocks(series, repo.blocks(series));
    }

    /** Whether the server gave {@code no} to this terminal. */
    public boolean issuedTo(Terminal t, InvoiceNumber no) {
        String prefix = t.storeCode() + String.format("%02d", t.number()) + "-";
        return no.series().startsWith(prefix) && new InvoiceBlocks(no.series(), repo.blocks(no.series())).covers(no);
    }
}
