package com.storesync.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storesync.analytics.application.AnalyticsService;
import com.storesync.outbox.application.EventEnvelope;
import com.storesync.support.ItBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.storesync.it.OnlineSalesIT.race;
import static org.assertj.core.api.Assertions.assertThat;

class StockAndAlertsIT extends ItBase {

    @Autowired
    AnalyticsService analytics;

    @Autowired
    ObjectMapper json;

    private JsonNode alerts(Shop s) {
        return s.managerApi().get("/v1/stores/" + s.code + "/alerts").expect(200).body();
    }

    private long alertCount(Shop s, String type, String sku) {
        long n = 0;
        for (JsonNode a : alerts(s)) {
            if (a.path("type").asText().equals(type) && (sku == null || sku.equals(a.path("sku").asText()))) {
                n++;
            }
        }
        return n;
    }

    @Test
    void aRetriedTransferMovesStockOnceAndAShortReceiptRaisesShrinkage() throws Exception {
        var a = shop();
        var b = shop();
        String sku = product("200.00", "18");
        a.stock(sku, 10);
        UUID id = UUID.randomUUID();
        var body = Map.of("id", id, "from", a.code, "to", b.code, "lines", Map.of(sku, 4));

        var sent = race(8, () -> a.managerApi().post("/v1/transfers", body));
        assertThat(sent).allSatisfy(r -> assertThat(r.status()).isIn(201, 409));
        assertThat(sent).anySatisfy(r -> assertThat(r.status()).isEqualTo(201));
        assertThat(a.onHand(sku)).isEqualTo(6);
        assertThat(a.managerApi().post("/v1/transfers",
                Map.of("id", id, "from", a.code, "to", b.code, "lines", Map.of(sku, 5))).status()).isEqualTo(422);

        assertThat(a.managerApi().post("/v1/transfers/" + id + "/receive", Map.of()).status()).isEqualTo(403);
        var got = race(6, () -> b.managerApi().post("/v1/transfers/" + id + "/receive",
                Map.of("received", Map.of(sku, 3))));
        assertThat(got).allSatisfy(r -> assertThat(r.status()).isIn(200, 409));
        assertThat(b.onHand(sku)).isEqualTo(3);
        var t = b.managerApi().get("/v1/transfers/" + id).expect(200).body();
        assertThat(t.path("status").asText()).isEqualTo("RECEIVED");

        eventually(() -> alertCount(b, "SHRINKAGE", sku) == 1);
    }

    @Test
    void aCashVarianceOverTheLimitRaisesOneAlert() {
        var s = shop();
        UUID shiftId = UUID.randomUUID();
        s.cashierApi().post("/v1/shifts", Map.of("id", shiftId, "store", s.code,
                "openingFloat", new BigDecimal("1000.00"))).expect(201);
        var closed = s.cashierApi().post("/v1/shifts/" + shiftId + "/close",
                Map.of("countedCash", new BigDecimal("850.00"))).expect(200).body();
        assertThat(closed.path("variance").decimalValue()).isEqualByComparingTo("-150.00");
        eventually(() -> alertCount(s, "CASH_VARIANCE", null) == 1);

        UUID small = UUID.randomUUID();
        s.cashierApi().post("/v1/shifts", Map.of("id", small, "store", s.code,
                "openingFloat", new BigDecimal("1000.00"))).expect(201);
        s.cashierApi().post("/v1/shifts/" + small + "/close", Map.of("countedCash", new BigDecimal("990.00")))
                .expect(200);
        eventually(() -> count("select count(*) from processed_event p join outbox o on o.event_id = p.event_id"
                + " where o.type = 'ShiftClosed' and o.payload->'data'->>'shiftId' = ? and p.consumer = ?",
                small.toString(), AnalyticsService.ALERTS) >= 1);
        assertThat(alertCount(s, "CASH_VARIANCE", null)).isEqualTo(1);
    }

    @Test
    void theDailyRollupCountsARedeliveredEventOnce() throws Exception {
        var s = shop();
        String sku = product("105.00", "5");
        s.stock(sku, 10);
        for (int i = 0; i < 3; i++) {
            s.cashierApi().post("/v1/sales", sale(UUID.randomUUID(), s.code, null, List.of(line(sku, 2)),
                    List.of(pay("CARD", "210.00")))).expect(201);
        }
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        String path = "/v1/analytics/sales?store=" + s.code + "&from=" + today.minusDays(1) + "&to=" + today.plusDays(1);
        eventually(() -> {
            var r = s.managerApi().get(path).expect(200).body();
            return r.path("days").size() == 1 && r.path("days").get(0).path("invoices").asLong() == 3;
        });

        String payload = db.sql("select payload::text from outbox where type = 'SaleCompleted' and msg_key = ? "
                        + "order by id limit 1").param(s.code).query(String.class).single();
        var again = json.readValue(payload, EventEnvelope.class);
        analytics.rollup(again);
        analytics.rollup(again);

        var r = s.managerApi().get(path).expect(200).body();
        var day = r.path("days").get(0);
        assertThat(day.path("invoices").asLong()).isEqualTo(3);
        assertThat(day.path("total").decimalValue()).isEqualByComparingTo("630.00");
        var line = find(r.path("skus"), "sku", sku);
        assertThat(line.path("qty").asLong()).isEqualTo(6);
        assertThat(line.path("net").decimalValue().add(line.path("tax").decimalValue()))
                .isEqualByComparingTo("630.00");
    }
}
