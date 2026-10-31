package com.storesync.procurement.domain;

import com.storesync.shared.domain.DomainRuleException;

import java.util.Map;

/** ISSUED until goods arrive; a GRN can receive part of an order, never more than was ordered. */
public enum PoStatus {
    ISSUED, PARTIALLY_RECEIVED, RECEIVED, CANCELLED;

    public record LineState(int ordered, int received) {
    }

    /**
     * @param lines   per SKU, what was ordered and already received
     * @param receipt per SKU, what this GRN brings in
     * @return status after the receipt
     */
    public static PoStatus afterReceipt(PoStatus current, Map<String, LineState> lines, Map<String, Integer> receipt) {
        if (current == RECEIVED || current == CANCELLED) {
            throw new com.storesync.shared.domain.DomainConflictException("po-closed", "This purchase order is " + current);
        }
        boolean complete = true;
        for (var e : lines.entrySet()) {
            int now = receipt.getOrDefault(e.getKey(), 0);
            if (now < 0) {
                throw new DomainRuleException("bad-quantity", "Received quantities cannot be negative");
            }
            int total = e.getValue().received() + now;
            if (total > e.getValue().ordered()) {
                throw new DomainRuleException("over-receipt", e.getKey() + ": receiving " + total + " of "
                        + e.getValue().ordered() + " ordered");
            }
            complete &= total == e.getValue().ordered();
        }
        for (String sku : receipt.keySet()) {
            if (!lines.containsKey(sku)) {
                throw new DomainRuleException("not-on-order", sku + " is not on this purchase order");
            }
        }
        return complete ? RECEIVED : PARTIALLY_RECEIVED;
    }
}
