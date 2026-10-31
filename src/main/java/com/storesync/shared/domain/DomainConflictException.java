package com.storesync.shared.domain;

/** A request that conflicts with the current state of a record (wrong state, someone else got there first). 409. */
public class DomainConflictException extends DomainRuleException {

    public DomainConflictException(String code, String message) {
        super(code, message);
    }
}
