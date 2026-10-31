package com.storesync.sales.domain;

import java.util.List;

/** The invoice numbers a terminal has been handed. An offline sale may only use a number from one of these. */
public record InvoiceBlocks(String series, List<Block> blocks) {

    public record Block(int first, int last) {
        public Block {
            if (first < 1 || last < first || last > InvoiceNumber.MAX) {
                throw new IllegalArgumentException("bad block " + first + ".." + last);
            }
        }

        public boolean contains(int n) {
            return n >= first && n <= last;
        }
    }

    public boolean covers(InvoiceNumber no) {
        return no.series().equals(series) && blocks.stream().anyMatch(b -> b.contains(no.number()));
    }

    /** Next block of {@code size} after {@code nextFree}, clipped at the series maximum. */
    public static Block allocate(int nextFree, int size) {
        if (nextFree > InvoiceNumber.MAX) {
            throw new com.storesync.shared.domain.DomainConflictException("series-exhausted",
                    "The invoice series is used up for this financial year");
        }
        return new Block(nextFree, Math.min(InvoiceNumber.MAX, nextFree + size - 1));
    }
}
