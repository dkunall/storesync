package com.storesync.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.storesync.support.ItBase;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.storesync.it.OnlineSalesIT.race;
import static org.assertj.core.api.Assertions.assertThat;

class ReturnsIT extends ItBase {

    private static Map<String, Object> ret(UUID id, int qty, String method, boolean restock, UUID shift) {
        Map<String, Object> b = new HashMap<>();
        b.put("returnId", id);
        b.put("lines", List.of(Map.of("lineNo", 1, "qty", qty)));
        b.put("refundMethod", method);
        b.put("restock", restock);
        b.put("reason", "size");
        if (shift != null) {
            b.put("shiftId", shift);
        }
        return b;
    }

    @Test
    void concurrentReturnsNeverRefundMoreThanWasSold() throws Exception {
        var s = shop();
        String sku = product("333.33", "18");
        s.stock(sku, 3);
        var saleId = UUID.randomUUID();
        var sold = s.cashierApi().post("/v1/sales", sale(saleId, s.code, null, List.of(line(sku, 3)),
                List.of(pay("UPI", "999.99")))).expect(201).body().path("sale");
        assertThat(s.onHand(sku)).isZero();

        var results = race(8, () -> s.cashierApi().post("/v1/sales/" + saleId + "/return",
                ret(UUID.randomUUID(), 1, "UPI", true, null)));

        assertThat(results.stream().filter(r -> r.status() == 201)).hasSize(3);
        assertThat(results.stream().filter(r -> r.status() != 201))
                .allSatisfy(r -> assertThat(r.status()).isIn(409, 422));
        // Each unit's share is rounded on its own; the last unit takes the remainder, so the sum is exact.
        BigDecimal refunded = results.stream().filter(r -> r.status() == 201)
                .map(r -> r.body().path("amount").decimalValue()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(refunded).isEqualByComparingTo(sold.path("total").decimalValue());
        assertThat(s.onHand(sku)).isEqualTo(3);
        var after = s.cashierApi().get("/v1/sales/" + saleId).expect(200).body().path("sale");
        assertThat(after.path("status").asText()).isEqualTo("RETURNED");
        assertThat(after.path("refunded").decimalValue()).isEqualByComparingTo("999.99");
    }

    @Test
    void aRetriedReturnIsAppliedOnce() throws Exception {
        var s = shop();
        String sku = product("80.00", "5");
        s.stock(sku, 5);
        var saleId = UUID.randomUUID();
        s.cashierApi().post("/v1/sales", sale(saleId, s.code, null, List.of(line(sku, 4)),
                List.of(pay("CARD", "320.00")))).expect(201);
        var returnId = UUID.randomUUID();

        var results = race(6, () -> s.cashierApi().post("/v1/sales/" + saleId + "/return",
                ret(returnId, 2, "CARD", true, null)));

        assertThat(results).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
        assertThat(count("select count(*) from sale_return where id = ?", returnId)).isEqualTo(1);
        assertThat(s.onHand(sku)).isEqualTo(3);
        var reused = s.cashierApi().post("/v1/sales/" + saleId + "/return", ret(returnId, 1, "CARD", true, null));
        assertThat(reused.status()).isEqualTo(422);
        assertThat(reused.text("code")).isEqualTo("return-id-reused");
    }

    @Test
    void anExchangeRefundsToACreditNoteAndSpendsItOnTheReplacement() {
        var s = shop();
        String medium = product("118.00", "18");
        String large = product("100.00", "18");
        s.stock(medium, 5);
        s.stock(large, 5);
        var saleId = UUID.randomUUID();
        s.cashierApi().post("/v1/sales", sale(saleId, s.code, null, List.of(line(medium, 2)),
                List.of(pay("CARD", "236.00")))).expect(201);

        var body = Map.of("ret", ret(UUID.randomUUID(), 1, "CREDIT_NOTE", true, null),
                "newSale", sale(UUID.randomUUID(), s.code, null, List.of(line(large, 1)), List.of()));
        var x = s.cashierApi().post("/v1/sales/" + saleId + "/exchange", body).expect(201).body();

        String cn = x.path("returned").path("creditNoteNo").asText();
        assertThat(x.path("returned").path("amount").decimalValue()).isEqualByComparingTo("118.00");
        assertThat(x.path("creditNoteBalance").decimalValue()).isEqualByComparingTo("18.00");
        JsonNode payments = x.path("sale").path("payments");
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).path("method").asText()).isEqualTo("CREDIT_NOTE");
        assertThat(s.onHand(medium)).isEqualTo(4);
        assertThat(s.onHand(large)).isEqualTo(4);

        var note = s.cashierApi().get("/v1/credit-notes/" + cn).expect(200).body();
        assertThat(note.path("amount").decimalValue().subtract(note.path("used").decimalValue()))
                .isEqualByComparingTo("18.00");
    }

    @Test
    void aCreditNoteCannotBeSpentTwiceByRacingSales() throws Exception {
        var s = shop();
        String sku = product("50.00", "5");
        s.stock(sku, 20);
        var saleId = UUID.randomUUID();
        s.cashierApi().post("/v1/sales", sale(saleId, s.code, null, List.of(line(sku, 1)),
                List.of(pay("CARD", "50.00")))).expect(201);
        String cn = s.cashierApi().post("/v1/sales/" + saleId + "/return",
                ret(UUID.randomUUID(), 1, "CREDIT_NOTE", true, null)).expect(201).body().path("creditNoteNo").asText();

        var results = race(6, () -> s.cashierApi().post("/v1/sales", sale(UUID.randomUUID(), s.code, null,
                List.of(line(sku, 1)), List.of(Map.of("method", "CREDIT_NOTE", "amount", new BigDecimal("50.00"),
                        "reference", cn)))));

        assertThat(results.stream().filter(r -> r.status() == 201)).hasSize(1);
        var note = s.cashierApi().get("/v1/credit-notes/" + cn).expect(200).body();
        assertThat(note.path("used").decimalValue()).isEqualByComparingTo("50.00");
    }

    @Test
    void cashRefundsComeOutOfAnOpenShift() {
        var s = shop();
        String sku = product("40.00", "5");
        s.stock(sku, 5);
        var shift = UUID.randomUUID();
        s.cashierApi().post("/v1/shifts", Map.of("id", shift, "store", s.code, "openingFloat", new BigDecimal("100.00")))
                .expect(201);
        var saleId = UUID.randomUUID();
        s.cashierApi().post("/v1/sales", sale(saleId, s.code, shift, List.of(line(sku, 2)),
                List.of(pay("CASH", "80.00")))).expect(201);

        var noShift = s.cashierApi().post("/v1/sales/" + saleId + "/return", ret(UUID.randomUUID(), 1, "CASH", false, null));
        assertThat(noShift.status()).isEqualTo(422);
        assertThat(noShift.text("code")).isEqualTo("cash-needs-shift");

        s.cashierApi().post("/v1/sales/" + saleId + "/return", ret(UUID.randomUUID(), 1, "CASH", false, shift)).expect(201);
        // Not restocked (damaged), so stock stays at 3.
        assertThat(s.onHand(sku)).isEqualTo(3);
        // 100 float + 80 sale - 40 refund.
        var closed = s.cashierApi().post("/v1/shifts/" + shift + "/close", Map.of("countedCash", new BigDecimal("140.00")))
                .expect(200).body();
        assertThat(closed.path("expectedCash").decimalValue()).isEqualByComparingTo("140.00");
        assertThat(closed.path("variance").decimalValue()).isEqualByComparingTo("0");
    }
}
