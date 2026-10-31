package com.storesync.store.adapter;

import com.storesync.shared.security.Caller;
import com.storesync.shared.security.Role;
import com.storesync.store.application.StoreRepository.StaffMember;
import com.storesync.store.application.StoreService;
import com.storesync.store.domain.Store;
import com.storesync.store.domain.Terminal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
class StoreController {

    private final StoreService stores;

    StoreController(StoreService stores) {
        this.stores = stores;
    }

    record StoreRequest(@NotBlank String code, @NotBlank String name, @NotBlank String stateCode, @NotBlank String gstin) {
    }

    record StaffRequest(@NotBlank String subject, @NotNull Role role, @NotBlank String name) {
    }

    record TerminalRequest(int number, @NotBlank String subject) {
    }

    @PostMapping("/v1/stores")
    @ResponseStatus(HttpStatus.CREATED)
    Store create(Caller caller, @Valid @RequestBody StoreRequest r) {
        return stores.create(caller, new Store(r.code(), r.name(), r.stateCode(), r.gstin()));
    }

    @GetMapping("/v1/stores")
    List<Store> list(Caller caller) {
        caller.require(Role.ADMIN);
        return stores.stores();
    }

    @PostMapping("/v1/stores/{code}/staff")
    @ResponseStatus(HttpStatus.CREATED)
    StaffMember staff(Caller caller, @PathVariable String code, @Valid @RequestBody StaffRequest r) {
        return stores.addStaff(caller, code, new StoreService.NewStaff(r.subject(), r.role(), r.name()));
    }

    @PostMapping("/v1/stores/{code}/terminals")
    @ResponseStatus(HttpStatus.CREATED)
    Terminal enrol(Caller caller, @PathVariable String code, @Valid @RequestBody TerminalRequest r) {
        return stores.enrol(caller, code, r.number(), r.subject());
    }

    @GetMapping("/v1/stores/{code}/terminals")
    List<Terminal> terminals(Caller caller, @PathVariable String code) {
        return stores.terminals(caller, code);
    }

    @PostMapping("/v1/terminals/{id}/re-enrol")
    Terminal reEnrol(Caller caller, @PathVariable UUID id) {
        return stores.reEnrol(caller, id);
    }
}
