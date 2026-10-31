package com.storesync.sales.domain;

/** Card and UPI are recorded with the terminal's approval reference; there is no gateway behind them. */
public enum PaymentMethod {
    CASH, CARD, UPI, CREDIT_NOTE;

    public boolean needsReference() {
        return this != CASH;
    }
}
