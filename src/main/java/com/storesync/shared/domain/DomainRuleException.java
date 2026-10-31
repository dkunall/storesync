package com.storesync.shared.domain;

/** A request that breaks a business rule. Reported as 422 with {@code code}. */
public class DomainRuleException extends RuntimeException {

    private final String code;

    public DomainRuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
