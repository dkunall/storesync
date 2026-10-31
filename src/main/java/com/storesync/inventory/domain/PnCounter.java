package com.storesync.inventory.domain;

import java.util.Map;
import java.util.TreeMap;

/**
 * A state-based PN-counter: each node only ever grows its own increment and decrement totals, and two replicas
 * merge by taking the per-node maximum. Merge is commutative, associative and idempotent, so replicas that have
 * seen the same updates in any order and with any amount of re-delivery hold the same value.
 * Immutable; every operation returns a new counter.
 */
public final class PnCounter {

    private final Map<String, Long> inc;
    private final Map<String, Long> dec;

    private PnCounter(Map<String, Long> inc, Map<String, Long> dec) {
        this.inc = inc;
        this.dec = dec;
    }

    public static PnCounter empty() {
        return new PnCounter(Map.of(), Map.of());
    }

    /** Rebuilds a replica from stored per-node totals. */
    public static PnCounter of(Map<String, Long> inc, Map<String, Long> dec) {
        inc.values().forEach(PnCounter::requireNonNegative);
        dec.values().forEach(PnCounter::requireNonNegative);
        return new PnCounter(Map.copyOf(inc), Map.copyOf(dec));
    }

    public PnCounter increment(String node, long by) {
        requirePositive(by);
        var m = new TreeMap<>(inc);
        m.merge(node, by, Math::addExact);
        return new PnCounter(Map.copyOf(m), dec);
    }

    public PnCounter decrement(String node, long by) {
        requirePositive(by);
        var m = new TreeMap<>(dec);
        m.merge(node, by, Math::addExact);
        return new PnCounter(inc, Map.copyOf(m));
    }

    public PnCounter merge(PnCounter other) {
        return new PnCounter(max(inc, other.inc), max(dec, other.dec));
    }

    public long value() {
        return inc.values().stream().mapToLong(Long::longValue).sum() - dec.values().stream().mapToLong(Long::longValue).sum();
    }

    public long incrementsOf(String node) {
        return inc.getOrDefault(node, 0L);
    }

    public long decrementsOf(String node) {
        return dec.getOrDefault(node, 0L);
    }

    public Map<String, Long> increments() {
        return inc;
    }

    public Map<String, Long> decrements() {
        return dec;
    }

    private static Map<String, Long> max(Map<String, Long> a, Map<String, Long> b) {
        var m = new TreeMap<>(a);
        b.forEach((k, v) -> m.merge(k, v, Math::max));
        return Map.copyOf(m);
    }

    private static void requirePositive(long by) {
        if (by <= 0) {
            throw new IllegalArgumentException("counter updates must be positive, got " + by);
        }
    }

    private static void requireNonNegative(long v) {
        if (v < 0) {
            throw new IllegalArgumentException("counter totals cannot be negative");
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PnCounter p && normal(inc).equals(normal(p.inc)) && normal(dec).equals(normal(p.dec));
    }

    @Override
    public int hashCode() {
        return normal(inc).hashCode() * 31 + normal(dec).hashCode();
    }

    private static Map<String, Long> normal(Map<String, Long> m) {
        var t = new TreeMap<String, Long>();
        m.forEach((k, v) -> {
            if (v != 0) {
                t.put(k, v);
            }
        });
        return t;
    }

    @Override
    public String toString() {
        return "PnCounter{value=" + value() + ", inc=" + new TreeMap<>(inc) + ", dec=" + new TreeMap<>(dec) + "}";
    }
}
