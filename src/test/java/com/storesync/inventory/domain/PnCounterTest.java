package com.storesync.inventory.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PnCounterTest {

    static final int SEQUENCES = 1000;

    @Test
    void valueIsIncrementsMinusDecrements() {
        var c = PnCounter.empty().increment("t1", 10).decrement("t1", 3).decrement("t2", 4).increment("hq", 2);
        assertThat(c.value()).isEqualTo(5);
        assertThat(c.incrementsOf("t1")).isEqualTo(10);
        assertThat(c.decrementsOf("t2")).isEqualTo(4);
    }

    @Test
    void mergeTakesPerNodeMaximum() {
        var a = PnCounter.empty().increment("t1", 5).decrement("t2", 1);
        var b = PnCounter.empty().increment("t1", 3).decrement("t2", 4).increment("t3", 1);
        var m = a.merge(b);
        assertThat(m.incrementsOf("t1")).isEqualTo(5);
        assertThat(m.decrementsOf("t2")).isEqualTo(4);
        assertThat(m.value()).isEqualTo(5 + 1 - 4);
    }

    @Test
    void rejectsNonPositiveUpdatesAndNegativeTotals() {
        assertThatThrownBy(() -> PnCounter.empty().increment("a", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PnCounter.empty().decrement("a", -2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PnCounter.of(java.util.Map.of("a", -1L), java.util.Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Several replicas each update only their own node, then exchange state in a random order, with random
     * duplicate and stale deliveries. Every replica must end on the same counter, whose value is the sum of all
     * updates, whatever the order.
     */
    @Test
    void replicasConvergeUnderRandomDeliveryOrder() {
        var rnd = new Random(20261031L);
        for (int seq = 0; seq < SEQUENCES; seq++) {
            int replicas = 2 + rnd.nextInt(5);
            var states = new ArrayList<PnCounter>();
            long expected = 0;
            for (int r = 0; r < replicas; r++) {
                var c = PnCounter.empty();
                int ops = rnd.nextInt(30);
                for (int i = 0; i < ops; i++) {
                    long by = 1 + rnd.nextInt(9);
                    if (rnd.nextBoolean()) {
                        c = c.increment("n" + r, by);
                        expected += by;
                    } else {
                        c = c.decrement("n" + r, by);
                        expected -= by;
                    }
                }
                states.add(c);
            }
            List<PnCounter> snapshots = new ArrayList<>(states);
            var deliveries = new ArrayList<int[]>();
            for (int from = 0; from < replicas; from++) {
                for (int to = 0; to < replicas; to++) {
                    for (int dup = 0; dup <= rnd.nextInt(3); dup++) {
                        deliveries.add(new int[]{from, to});
                    }
                }
            }
            Collections.shuffle(deliveries, rnd);
            for (int[] d : deliveries) {
                PnCounter msg = rnd.nextInt(4) == 0 ? snapshots.get(d[0]) : states.get(d[0]);
                states.set(d[1], states.get(d[1]).merge(msg));
            }
            // one final full round so every replica has seen every original state at least once
            for (int to = 0; to < replicas; to++) {
                for (PnCounter s : snapshots) {
                    states.set(to, states.get(to).merge(s));
                }
            }
            for (PnCounter s : states) {
                assertThat(s).isEqualTo(states.getFirst());
                assertThat(s.value()).as("sequence %d", seq).isEqualTo(expected);
            }
        }
    }

    @Test
    void mergeIsCommutativeAssociativeAndIdempotent() {
        var rnd = new Random(7);
        for (int i = 0; i < SEQUENCES; i++) {
            var a = random(rnd);
            var b = random(rnd);
            var c = random(rnd);
            assertThat(a.merge(b)).isEqualTo(b.merge(a));
            assertThat(a.merge(b).merge(c)).isEqualTo(a.merge(b.merge(c)));
            assertThat(a.merge(a)).isEqualTo(a);
            assertThat(a.merge(b).merge(b)).isEqualTo(a.merge(b));
        }
    }

    private static PnCounter random(Random rnd) {
        var c = PnCounter.empty();
        for (int i = 0; i < rnd.nextInt(10); i++) {
            String node = "n" + rnd.nextInt(4);
            c = rnd.nextBoolean() ? c.increment(node, 1 + rnd.nextInt(5)) : c.decrement(node, 1 + rnd.nextInt(5));
        }
        return c;
    }
}
