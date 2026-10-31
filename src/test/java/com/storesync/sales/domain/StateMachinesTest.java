package com.storesync.sales.domain;

import com.storesync.shared.domain.DomainConflictException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateMachinesTest {

    @Test
    void saleFollowsThePageStateMachine() {
        assertThat(SaleStatus.OPEN.moveTo(SaleStatus.TENDERING).moveTo(SaleStatus.PAID).moveTo(SaleStatus.SYNCED)
                .moveTo(SaleStatus.PARTIALLY_RETURNED).moveTo(SaleStatus.RETURNED)).isEqualTo(SaleStatus.RETURNED);
        assertThat(SaleStatus.OPEN.canMoveTo(SaleStatus.VOIDED)).isTrue();
        assertThat(SaleStatus.PAID.canMoveTo(SaleStatus.VOIDED)).isFalse();
        assertThatThrownBy(() -> SaleStatus.RETURNED.moveTo(SaleStatus.PARTIALLY_RETURNED))
                .isInstanceOf(DomainConflictException.class);
        assertThat(SaleStatus.SYNCED.returnable()).isTrue();
        assertThat(SaleStatus.VOIDED.returnable()).isFalse();
    }

    @Test
    void shiftClosesOnlyWhenEveryDeclaredSaleArrived() {
        assertThat(ShiftState.afterClose(3, 5)).isEqualTo(ShiftState.CLOSING);
        assertThat(ShiftState.afterClose(5, 5)).isEqualTo(ShiftState.CLOSED);
        assertThat(ShiftState.OPEN.moveTo(ShiftState.ACTIVE).moveTo(ShiftState.CLOSING).moveTo(ShiftState.CLOSED))
                .isEqualTo(ShiftState.CLOSED);
        assertThatThrownBy(() -> ShiftState.CLOSED.moveTo(ShiftState.ACTIVE)).isInstanceOf(DomainConflictException.class);
        assertThat(ShiftState.CLOSING.acceptsSales()).isFalse();
    }

    @Test
    void cashDrawerVariance() {
        var d = new CashDrawer(new BigDecimal("2000.00"), new BigDecimal("5300.00"), new BigDecimal("120.00"),
                new BigDecimal("250.00"));
        assertThat(d.expected()).isEqualByComparingTo("6930.00");
        assertThat(d.variance(new BigDecimal("6900.00"))).isEqualByComparingTo("-30.00");
    }
}
