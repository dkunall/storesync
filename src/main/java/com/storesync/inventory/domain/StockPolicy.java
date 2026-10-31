package com.storesync.inventory.domain;

/**
 * How a stock change treats the floor of zero. Server-side moves (online sales, transfers out) are refused rather
 * than taking stock below zero. A sale a terminal already made offline is a fact: it is applied even if the merged
 * count goes negative, and the negative count raises an alert for someone to investigate.
 */
public enum StockPolicy {
    REFUSE_NEGATIVE, ALLOW_NEGATIVE
}
