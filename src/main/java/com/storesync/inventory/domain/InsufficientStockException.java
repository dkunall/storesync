package com.storesync.inventory.domain;

import com.storesync.shared.domain.DomainConflictException;

public class InsufficientStockException extends DomainConflictException {

    public InsufficientStockException(String store, String sku, long onHand, long wanted) {
        super("insufficient-stock", "Store " + store + " has " + onHand + " of " + sku + ", " + wanted + " requested");
    }
}
