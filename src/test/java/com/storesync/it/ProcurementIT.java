package com.storesync.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.storesync.support.ItBase;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.storesync.it.OnlineSalesIT.race;
import static org.assertj.core.api.Assertions.assertThat;

class ProcurementIT extends ItBase {

    private String supplier(int leadDays) {
        var id = UUID.randomUUID();
        admin().post("/v1/suppliers", Map.of("id", id, "name", "Supplier " + code(4), "leadTimeDays", leadDays))
                .expect(201);
        return id.toString();
    }

    private static Map<String, Object> order(String supplier, String sku, int qty) {
        return Map.of("supplierId", supplier, "lines",
                List.of(Map.of("sku", sku, "qty", qty, "unitCost", new BigDecimal("12.50"))));
    }

    @Test
    void racingOrdersWithOneIdempotencyKeyCreateOnePurchaseOrder() throws Exception {
        var s = shop();
        String sup = supplier(3);
        String sku = product("25.00", "5", sup);
        String key = "po-" + UUID.randomUUID();

        var results = race(8, () -> s.managerApi().post("/v1/stores/" + s.code + "/purchase-orders",
                order(sup, sku, 40), Map.of("Idempotency-Key", key)));

        assertThat(results).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
        assertThat(results.stream().map(r -> r.body().path("id").asText()).distinct()).hasSize(1);
        assertThat(count("select count(*) from purchase_order where idem_key = ?", key)).isEqualTo(1);

        var reused = s.managerApi().post("/v1/stores/" + s.code + "/purchase-orders", order(sup, sku, 41),
                Map.of("Idempotency-Key", key));
        assertThat(reused.status()).isEqualTo(422);
        assertThat(reused.text("code")).isEqualTo("idempotency-key-reused");

        var noKey = s.managerApi().post("/v1/stores/" + s.code + "/purchase-orders", order(sup, sku, 41));
        assertThat(noKey.status()).isEqualTo(400);

        String otherSup = supplier(2);
        var wrong = s.managerApi().post("/v1/stores/" + s.code + "/purchase-orders", order(otherSup, sku, 1),
                Map.of("Idempotency-Key", "po-" + UUID.randomUUID()));
        assertThat(wrong.status()).isEqualTo(422);
        assertThat(wrong.text("code")).isEqualTo("wrong-supplier");
    }

    @Test
    void aGoodsReceiptPostedTwiceAddsStockOnce() throws Exception {
        var s = shop();
        String sup = supplier(3);
        String sku = product("25.00", "5", sup);
        String po = s.managerApi().post("/v1/stores/" + s.code + "/purchase-orders", order(sup, sku, 10),
                Map.of("Idempotency-Key", "po-" + UUID.randomUUID())).expect(201).body().path("id").asText();

        var grn = Map.of("ref", "DC-1001", "received", Map.of(sku, 6));
        var results = race(6, () -> s.managerApi().post("/v1/purchase-orders/" + po + "/receipts", grn));
        assertThat(results).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
        assertThat(results.stream().map(r -> r.body().path("id").asText()).distinct()).hasSize(1);
        assertThat(s.onHand(sku)).isEqualTo(6);
        assertThat(s.managerApi().get("/v1/purchase-orders/" + po).expect(200).body().path("status").asText())
                .isEqualTo("PARTIALLY_RECEIVED");

        var changed = s.managerApi().post("/v1/purchase-orders/" + po + "/receipts",
                Map.of("ref", "DC-1001", "received", Map.of(sku, 5)));
        assertThat(changed.status()).isEqualTo(422);
        assertThat(changed.text("code")).isEqualTo("grn-ref-reused");

        var over = s.managerApi().post("/v1/purchase-orders/" + po + "/receipts",
                Map.of("ref", "DC-1002", "received", Map.of(sku, 5)));
        assertThat(over.status()).isEqualTo(422);
        assertThat(over.text("code")).isEqualTo("over-receipt");

        s.managerApi().post("/v1/purchase-orders/" + po + "/receipts",
                Map.of("ref", "DC-1003", "received", Map.of(sku, 4))).expect(201);
        assertThat(s.onHand(sku)).isEqualTo(10);
        assertThat(s.managerApi().get("/v1/purchase-orders/" + po).expect(200).body().path("status").asText())
                .isEqualTo("RECEIVED");
        var closed = s.managerApi().post("/v1/purchase-orders/" + po + "/receipts",
                Map.of("ref", "DC-1004", "received", Map.of(sku, 1)));
        assertThat(closed.status()).isEqualTo(409);
    }

    @Test
    void replenishmentSuggestsFromDemandAndRaisingIsIdempotent() {
        var s = shop();
        String sup = supplier(4);
        String fast = product("30.00", "5", sup);
        String slow = product("30.00", "5", sup);
        s.stock(fast, 5);
        s.stock(slow, 500);
        // Synthetic history: 28 days at 10 units/day for one SKU, 1 unit/day for the other.
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        for (int i = 1; i <= 28; i++) {
            for (var e : Map.of(fast, 10, slow, 1).entrySet()) {
                db.sql("insert into sales_daily (store_code, day, sku, qty, net, tax) values (?, ?, ?, ?, 0, 0)")
                        .params(s.code, Date.valueOf(today.minusDays(i)), e.getKey(), e.getValue()).update();
            }
        }

        var plan = s.managerApi().get("/v1/stores/" + s.code + "/replenishment").expect(200).body();
        JsonNode f = find(plan.path("suggestions"), "sku", fast);
        JsonNode sl = find(plan.path("suggestions"), "sku", slow);
        assertThat(f.path("plan").path("dailyForecast").asDouble()).isBetween(9.99, 10.01);
        assertThat(f.path("plan").path("suggestedQty").asLong()).isPositive();
        assertThat(sl.path("plan").path("suggestedQty").asLong()).isZero();
        long suggested = f.path("plan").path("suggestedQty").asLong();

        String key = "raise-" + UUID.randomUUID();
        var first = s.managerApi().post("/v1/stores/" + s.code + "/replenishment/raise", Map.of(),
                Map.of("Idempotency-Key", key)).expect(200).body();
        var second = s.managerApi().post("/v1/stores/" + s.code + "/replenishment/raise", Map.of(),
                Map.of("Idempotency-Key", key)).expect(200).body();
        assertThat(first.path("raised")).hasSize(1);
        assertThat(second.path("raised").get(0).path("id").asText())
                .isEqualTo(first.path("raised").get(0).path("id").asText());
        assertThat(first.path("raised").get(0).path("lines").get(0).path("qty").asLong()).isEqualTo(suggested);

        // What is on order now counts towards the position, so nothing more is suggested.
        var after = find(s.managerApi().get("/v1/stores/" + s.code + "/replenishment").expect(200).body()
                .path("suggestions"), "sku", fast);
        assertThat(after.path("onOrder").asLong()).isEqualTo(suggested);
        assertThat(after.path("plan").path("suggestedQty").asLong()).isZero();
    }
}
