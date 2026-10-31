package com.storesync.procurement.domain;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ReplenishmentPlannerTest {

    final ReplenishmentPlanner planner = new ReplenishmentPlanner(0.3, 1.65, 7);

    @Test
    void ewmaWeightsRecentDays() {
        assertThat(ReplenishmentPlanner.ewma(List.of(10L, 10L, 20L), 0.5)).isEqualTo(15.0);
        assertThat(ReplenishmentPlanner.ewma(List.of(), 0.5)).isZero();
    }

    @Test
    void steadyDemandHasNoSafetyStock() {
        var p = planner.plan(Collections.nCopies(28, 10L), 4, 30, 0);
        assertThat(p.dailyForecast()).isCloseTo(10.0, within(1e-9));
        assertThat(p.safetyStock()).isZero();
        assertThat(p.reorderPoint()).isEqualTo(40);
        assertThat(p.orderUpTo()).isEqualTo(110);
        assertThat(p.suggestedQty()).isEqualTo(80);
    }

    @Test
    void variableDemandAddsSafetyStock() {
        var demand = List.of(5L, 15L, 5L, 15L, 5L, 15L, 5L, 15L);
        var p = planner.plan(demand, 4, 100, 0);
        // sample sd of alternating 5/15 over 8 days = sqrt(200/7) = 5.35; 1.65 * 5.35 * sqrt(4) = 17.64 -> 18
        assertThat(p.sigma()).isCloseTo(Math.sqrt(200.0 / 7), within(1e-9));
        assertThat(p.safetyStock()).isEqualTo(18);
        assertThat(p.suggestedQty()).isZero().as("100 on hand is above the reorder point");
    }

    @Test
    void stockOnOrderCountsTowardsThePosition() {
        var p = planner.plan(Collections.nCopies(28, 10L), 4, 10, 25);
        assertThat(p.position()).isEqualTo(35);
        assertThat(p.suggestedQty()).isEqualTo(75);
        assertThat(planner.plan(List.of(0L, 0L), 3, 0, 0).suggestedQty()).isZero();
        assertThatThrownBy(() -> planner.plan(List.of(1L), 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void purchaseOrderReceipts() {
        var lines = Map.of("A", new PoStatus.LineState(10, 4), "B", new PoStatus.LineState(5, 0));
        assertThat(PoStatus.afterReceipt(PoStatus.PARTIALLY_RECEIVED, lines, Map.of("A", 6, "B", 5)))
                .isEqualTo(PoStatus.RECEIVED);
        assertThat(PoStatus.afterReceipt(PoStatus.ISSUED, lines, Map.of("B", 2))).isEqualTo(PoStatus.PARTIALLY_RECEIVED);
        assertThatThrownBy(() -> PoStatus.afterReceipt(PoStatus.ISSUED, lines, Map.of("A", 7)))
                .hasMessageContaining("receiving 11 of 10");
        assertThatThrownBy(() -> PoStatus.afterReceipt(PoStatus.ISSUED, lines, Map.of("Z", 1)))
                .hasMessageContaining("not on this purchase order");
        assertThatThrownBy(() -> PoStatus.afterReceipt(PoStatus.RECEIVED, lines, Map.of("A", 1)))
                .hasMessageContaining("RECEIVED");
    }
}
