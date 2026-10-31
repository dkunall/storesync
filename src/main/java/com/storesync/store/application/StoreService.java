package com.storesync.store.application;

import com.storesync.shared.error.ApiException;
import com.storesync.shared.security.Caller;
import com.storesync.shared.security.Role;
import com.storesync.store.application.StoreRepository.StaffMember;
import com.storesync.store.domain.Store;
import com.storesync.store.domain.Terminal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Stores, their staff and their terminals. Other modules read stores and lock terminals through here. */
@Service
public class StoreService {

    private final StoreRepository repo;
    private final Clock clock;

    StoreService(StoreRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    public record NewStaff(String subject, Role role, String name) {
    }

    @Transactional
    public Store create(Caller caller, Store s) {
        caller.require(Role.ADMIN);
        if (!repo.insertStore(s)) {
            Store existing = repo.store(s.code()).orElseThrow();
            if (!existing.equals(s)) {
                throw ApiException.conflict("store-exists", "Store " + s.code() + " already exists with other details");
            }
            return existing;
        }
        return s;
    }

    @Transactional
    public StaffMember addStaff(Caller caller, String store, NewStaff n) {
        caller.require(Role.ADMIN);
        store(store);
        if (n.role() != Role.MANAGER && n.role() != Role.CASHIER) {
            throw ApiException.invalid("Staff are MANAGER or CASHIER");
        }
        if (n.subject() == null || n.subject().isBlank() || n.name() == null || n.name().isBlank()) {
            throw ApiException.invalid("subject and name are required");
        }
        var existing = repo.staff(n.subject());
        if (existing.isEmpty() && repo.subjectTaken(n.subject())) {
            throw ApiException.conflict("subject-taken", "That subject belongs to a terminal");
        }
        var m = new StaffMember(n.subject(), store, n.role(), n.name());
        repo.upsertStaff(m);
        return m;
    }

    /** Managers enrol tills in their own store. Terminal numbers 01..99; 00 is the server's own invoice series. */
    @Transactional
    public Terminal enrol(Caller caller, String store, int number, String subject) {
        caller.requireStore(store, Role.MANAGER);
        store(store);
        if (number < 1 || number > 99) {
            throw ApiException.invalid("terminal number must be 1..99");
        }
        if (subject == null || subject.isBlank()) {
            throw ApiException.invalid("subject is required");
        }
        if (repo.subjectTaken(subject)) {
            throw ApiException.conflict("subject-taken", "That subject is already a staff member or terminal");
        }
        if (repo.terminals(store).stream().anyMatch(t -> t.number() == number)) {
            throw ApiException.conflict("terminal-number-taken", "Store " + store + " already has terminal " + number);
        }
        var now = clock.instant();
        var t = new Terminal(UUID.randomUUID(), store, number, subject, 1, 0, null, now);
        repo.insertTerminal(t);
        return t;
    }

    /**
     * New device or wiped storage: bump the epoch so ops the old installation never uploaded are refused rather
     * than replayed against a sequence the new one restarts from 1. Counters and invoice blocks stay with the
     * terminal; unused numbers in its old blocks are simply never issued.
     */
    @Transactional
    public Terminal reEnrol(Caller caller, UUID terminalId) {
        Terminal t = repo.lockTerminal(terminalId).orElseThrow(() -> ApiException.notFound("terminal"));
        caller.requireStore(t.storeCode(), Role.MANAGER);
        repo.reEnrol(terminalId, t.epoch() + 1, clock.instant());
        return repo.terminal(terminalId).orElseThrow();
    }

    public Store store(String code) {
        return repo.store(code).orElseThrow(() -> ApiException.notFound("store " + code));
    }

    public List<Store> stores() {
        return repo.stores();
    }

    public List<Terminal> terminals(Caller caller, String store) {
        caller.requireStore(store, Role.MANAGER);
        return repo.terminals(store);
    }

    public Optional<StaffMember> staff(String subject) {
        return repo.staff(subject);
    }

    public Terminal terminal(UUID id) {
        return repo.terminal(id).orElseThrow(() -> ApiException.notFound("terminal"));
    }

    /** Row lock that serialises everything one terminal uploads. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Terminal lockTerminal(UUID id) {
        return repo.lockTerminal(id).orElseThrow(() -> ApiException.notFound("terminal"));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void advance(UUID id, long lastSeq) {
        repo.advance(id, lastSeq, clock.instant());
    }
}
