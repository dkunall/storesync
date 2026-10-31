package com.storesync.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.storesync.support.Api;
import com.storesync.support.ItBase;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class OnlineSalesIT extends ItBase {

    static <T> List<T> race(int n, Callable<T> task) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<T>>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            var out = new ArrayList<T>();
            for (var f : futures) {
                out.add(f.get());
            }
            return out;
        }
    }

    @Test
    void concurrentSalesNeverTakeStockBelowZero() throws Exception {
        var s = shop();
        String sku = product("118.00", "18");
        s.stock(sku, 20);
        Api till = s.cashierApi();

        var results = race(60, () -> till.post("/v1/sales",
                sale(UUID.randomUUID(), s.code, null, List.of(line(sku, 1)), List.of(pay("UPI", "118.00")))));

        var ok = results.stream().filter(r -> r.status() == 201).toList();
        var refused = results.stream().filter(r -> r.status() == 409).toList();
        assertThat(ok).hasSize(20);
        assertThat(refused).hasSize(40)
                .allSatisfy(r -> assertThat(r.text("code")).isEqualTo("insufficient-stock"));
        assertThat(s.onHand(sku)).isZero();
        assertThat(count("select count(*) from stock_movement where store_code = ? and sku = ? and on_hand < 0",
                s.code, sku)).isZero();

        Set<String> invoices = new HashSet<>();
        ok.forEach(r -> invoices.add(r.body().path("sale").path("invoiceNo").asText()));
        assertThat(invoices).hasSize(20);
        // Failed sales never reach the series, so the online series has no holes.
        var numbers = invoices.stream().map(i -> Integer.parseInt(i.substring(i.lastIndexOf('-') + 1))).sorted().toList();
        assertThat(numbers.getFirst()).isEqualTo(1);
        assertThat(numbers.getLast()).isEqualTo(20);
        assertThat(invoices).allSatisfy(i -> assertThat(i).startsWith(s.code + "00-"));
    }

    @Test
    void aRetriedSaleIsRecordedOnceEvenWhenRetriesRace() throws Exception {
        var s = shop();
        String sku = product("50.00", "5");
        s.stock(sku, 10);
        var id = UUID.randomUUID();
        var body = sale(id, s.code, null, List.of(line(sku, 2)), List.of(pay("CARD", "100.00")));

        var results = race(10, () -> s.cashierApi().post("/v1/sales", body));

        assertThat(results).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
        assertThat(results.stream().map(r -> r.body().path("sale").path("invoiceNo").asText()).distinct()).hasSize(1);
        assertThat(count("select count(*) from sale where id = ?", id)).isEqualTo(1);
        assertThat(s.onHand(sku)).isEqualTo(8);

        var changed = sale(id, s.code, null, List.of(line(sku, 3)), List.of(pay("CARD", "150.00")));
        var reuse = s.cashierApi().post("/v1/sales", changed);
        assertThat(reuse.status()).isEqualTo(422);
        assertThat(reuse.text("code")).isEqualTo("sale-id-reused");
        assertThat(s.onHand(sku)).isEqualTo(8);
    }

    @Test
    void gstSplitsIntraStateAndInterStateAndPromotionsApply() {
        var s = shop("29");
        String shirt = product("1180.00", "18");
        s.stock(shirt, 10);
        String promo = "IT" + code(6);
        admin().post("/v1/promotions", Map.of("code", promo, "type", "PERCENT_OFF", "priority", 1,
                "rules", Map.of("skus", List.of(shirt), "percent", new BigDecimal("10")))).expect(201);

        // 2 x 1180 = 2360, 10% off = 2124.00 inclusive of 18% GST: taxable 1800.00, tax 324.00 split 162/162.
        var walkIn = s.cashierApi().post("/v1/sales", sale(UUID.randomUUID(), s.code, null,
                List.of(line(shirt, 2)), List.of(pay("CARD", "2124.00")))).expect(201).body();
        JsonNode sale = walkIn.path("sale");
        assertThat(sale.path("total").decimalValue()).isEqualByComparingTo("2124.00");
        assertThat(sale.path("discount").decimalValue()).isEqualByComparingTo("236.00");
        assertThat(sale.path("taxable").decimalValue()).isEqualByComparingTo("1800.00");
        assertThat(sale.path("cgst").decimalValue()).isEqualByComparingTo("162.00");
        assertThat(sale.path("sgst").decimalValue()).isEqualByComparingTo("162.00");
        assertThat(sale.path("igst").decimalValue()).isEqualByComparingTo("0");
        assertThat(walkIn.path("promotions").get(0).path("code").asText()).isEqualTo(promo);

        // A buyer registered in Maharashtra (27): the same sale is inter-state, all IGST.
        var b2b = sale(UUID.randomUUID(), s.code, null, List.of(line(shirt, 1)), List.of(pay("CARD", "1062.00")));
        b2b.put("customerGstin", "27ABCDE1234F1Z5");
        JsonNode inter = s.cashierApi().post("/v1/sales", b2b).expect(201).body().path("sale");
        assertThat(inter.path("igst").decimalValue()).isEqualByComparingTo("162.00");
        assertThat(inter.path("cgst").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void cashierDiscountsAreCappedButAManagerCanGoFurther() {
        var s = shop();
        String sku = product("100.00", "12");
        s.stock(sku, 10);
        var big = Map.<String, Object>of("sku", sku, "qty", 1, "manualDiscount", new BigDecimal("20.00"));
        var refused = s.cashierApi().post("/v1/sales", sale(UUID.randomUUID(), s.code, null, List.of(big),
                List.of(pay("CARD", "80.00"))));
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.text("code")).isEqualTo("discount-over-limit");

        s.managerApi().post("/v1/sales", sale(UUID.randomUUID(), s.code, null, List.of(big),
                List.of(pay("CARD", "80.00")))).expect(201);
        assertThat(s.onHand(sku)).isEqualTo(9);
    }

    @Test
    void cashNeedsAnOpenShiftAndTheDrawerReconciles() {
        var s = shop();
        String sku = product("45.00", "5");
        s.stock(sku, 10);
        var noShift = s.cashierApi().post("/v1/sales", sale(UUID.randomUUID(), s.code, null, List.of(line(sku, 1)),
                List.of(pay("CASH", "50.00"))));
        assertThat(noShift.status()).isEqualTo(422);
        assertThat(noShift.text("code")).isEqualTo("cash-needs-shift");

        var shiftId = UUID.randomUUID();
        s.cashierApi().post("/v1/shifts", Map.of("id", shiftId, "store", s.code, "openingFloat", new BigDecimal("500.00")))
                .expect(201);
        var paid = s.cashierApi().post("/v1/sales", sale(UUID.randomUUID(), s.code, shiftId, List.of(line(sku, 2)),
                List.of(pay("CASH", "100.00")))).expect(201).body().path("sale");
        assertThat(paid.path("changeDue").decimalValue()).isEqualByComparingTo("10.00");

        // Drawer: 500 float + 100 tendered - 10 change = 590. The cashier counts 580.
        var closed = s.cashierApi().post("/v1/shifts/" + shiftId + "/close", Map.of("countedCash", new BigDecimal("580.00")))
                .expect(200).body();
        assertThat(closed.path("expectedCash").decimalValue()).isEqualByComparingTo("590.00");
        assertThat(closed.path("variance").decimalValue()).isEqualByComparingTo("-10.00");
        // Closing again is a no-op.
        s.cashierApi().post("/v1/shifts/" + shiftId + "/close", Map.of("countedCash", new BigDecimal("580.00"))).expect(200);

        var rec = s.managerApi().get("/v1/stores/" + s.code + "/reconciliation?day=" + istDay(paid.path("soldAt").asText()))
                .expect(200).body();
        assertThat(rec.path("invoices").asLong()).isEqualTo(1);
        assertThat(rec.path("salesTotal").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(rec.path("cashVariance").decimalValue()).isEqualByComparingTo("-10.00");
        assertThat(rec.path("tenderBalanced").asBoolean()).isTrue();
    }

    static String istDay(String instant) {
        return java.time.Instant.parse(instant).atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate().toString();
    }
}
