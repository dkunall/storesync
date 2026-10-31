package com.storesync.store.application;

import com.storesync.shared.security.Role;
import com.storesync.store.domain.Store;
import com.storesync.store.domain.Terminal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StoreRepository {

    record StaffMember(String subject, String storeCode, Role role, String name) {
    }

    boolean insertStore(Store s);

    Optional<Store> store(String code);

    List<Store> stores();

    void upsertStaff(StaffMember m);

    Optional<StaffMember> staff(String subject);

    boolean subjectTaken(String subject);

    void insertTerminal(Terminal t);

    Optional<Terminal> terminal(UUID id);

    Optional<Terminal> lockTerminal(UUID id);

    List<Terminal> terminals(String store);

    void reEnrol(UUID id, int epoch, Instant at);

    void advance(UUID id, long lastSeq, Instant at);
}
