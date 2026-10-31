package com.storesync.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.storesync.support.ItBase;
import com.storesync.support.Till;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.storesync.it.OnlineSalesIT.race;
import static org.assertj.core.api.Assertions.assertThat;

class SyncIT extends ItBase {

    private static List<String> statuses(JsonNode push) {
        var out = new ArrayList<String>();
        push.path("results").forEach(r -> out.add(r.path("status").asText()));
        return out;
    }

    @Test
    void replayingABatchIsIdempotentEvenWhenPushesRace() throws Exception {
        var s = shop();
        String sku = product("59.00", "18");
        s.stock(sku, 5);
        var till = new Till(s.terminal(1), "29");
        till.allocate();
        var shift = UUID.randomUUID();
        var ops = new ArrayList<Map<String, Object>>();
        ops.add(till.shiftOpened(shift, s.cashier, "200.00"));
        for (int i = 0; i < 3; i++) {
            ops.add(till.cashSale(UUID.randomUUID(), shift, till.nextInvoice(), s.cashier, sku, 1, "59.00", "18", "60.00"));
        }

        var pushes = race(6, () -> till.push(ops).expect(200).body());

        long applied = pushes.stream().flatMap(p -> statuses(p).stream()).filter("APPLIED"::equals).count();
        long dupes = pushes.stream().flatMap(p -> statuses(p).stream()).filter("DUPLICATE"::equals).count();
        assertThat(applied).isEqualTo(4);
        assertThat(dupes).isEqualTo(5 * 4);
        assertThat(count("select count(*) from sale where terminal_id = ?::uuid", till.terminal().id())).isEqualTo(3);
        assertThat(count("select count(*) from op_log where terminal_id = ?::uuid", till.terminal().id())).isEqualTo(4);
        assertThat(s.onHand(sku)).isEqualTo(2);

        // A till that lost the ack and sends everything again a minute later.
        var again = till.push(ops).expect(200).body();
        assertThat(statuses(again)).containsOnly("DUPLICATE");
        assertThat(again.path("lastSeq").asLong()).isEqualTo(4);
        assertThat(s.onHand(sku)).isEqualTo(2);
    }

    @Test
    void aReusedSeqIsAConflictAGapStopsTheBatchAndAStaleEpochIsRefused() {
        var s = shop();
        var till = new Till(s.terminal(2), "29");
        var shift = UUID.randomUUID();
        var first = till.shiftOpened(shift, s.cashier, "100.00");
        assertThat(statuses(till.push(List.of(first)).expect(200).body())).containsExactly("APPLIED");

        var reused = till.op(1, "SHIFT_OPENED", Map.of("shiftId", UUID.randomUUID(), "cashier", s.cashier,
                "openingFloat", new BigDecimal("100.00"), "openedAt", "2026-10-30T04:00:00Z"));
        var conflict = till.push(List.of(reused)).expect(200).body();
        assertThat(conflict.path("results").get(0).path("status").asText()).isEqualTo("CONFLICT");
        assertThat(conflict.path("results").get(0).path("code").asText()).isEqualTo("seq-reused");

        var skipped = till.op(3, "SHIFT_CLOSED", Map.of("shiftId", shift, "countedCash", new BigDecimal("100.00"),
                "declaredSales", 0, "closedAt", "2026-10-31T04:00:00Z"));
        var gap = till.push(List.of(skipped)).expect(200).body();
        assertThat(gap.path("results").get(0).path("status").asText()).isEqualTo("GAP");
        assertThat(gap.path("expectedSeq").asLong()).isEqualTo(2);

        var reEnrolled = s.managerApi().post("/v1/terminals/" + till.terminal().id() + "/re-enrol", Map.of())
                .expect(200).body();
        int newEpoch = reEnrolled.path("epoch").asInt();
        assertThat(newEpoch).isEqualTo(till.epoch() + 1);
        var stale = till.push(List.of(till.op(2, "SHIFT_CLOSED", Map.of("shiftId", shift,
                "countedCash", new BigDecimal("100.00"), "declaredSales", 0, "closedAt", "2026-10-31T04:00:00Z"))));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.text("code")).isEqualTo("stale-epoch");

        till.epoch(newEpoch);
        var fresh = till.push(List.of(till.shiftClosed(shift, "100.00", 0))).expect(200).body();
        assertThat(statuses(fresh)).containsExactly("APPLIED");
    }

    @Test
    void aRejectedOpIsLoggedAlertedAndDoesNotBlockTheOpsBehindIt() {
        var s = shop();
        String sku = product("25.00", "5");
        s.stock(sku, 10);
        var till = new Till(s.terminal(3), "29");
        till.allocate();
        var shift = UUID.randomUUID();
        var opened = till.shiftOpened(shift, s.cashier, "0.00");
        var rogue = till.cashSale(UUID.randomUUID(), shift, till.invoice(999), s.cashier, sku, 1, "25.00", "5", "25.00");
        var good = till.cashSale(UUID.randomUUID(), shift, till.nextInvoice(), s.cashier, sku, 2, "25.00", "5", "50.00");
        var r = till.push(List.of(opened, rogue, good)).expect(200).body();

        assertThat(statuses(r)).containsExactly("APPLIED", "REJECTED", "APPLIED");
        assertThat(r.path("results").get(1).path("code").asText()).isEqualTo("invoice-not-issued");
        assertThat(r.path("lastSeq").asLong()).isEqualTo(3);
        assertThat(s.onHand(sku)).isEqualTo(8);
        assertThat(count("select count(*) from op_log where terminal_id = ?::uuid and status = 'REJECTED'",
                till.terminal().id())).isEqualTo(1);

        eventually(() -> alerts(s).stream().anyMatch(a -> a.path("type").asText().equals("SYNC_REJECTED")));
    }

    @Test
    void twoOfflineTillsOversellingConvergeNegativeAndRaiseAnAlert() {
        var s = shop();
        String sku = product("399.00", "12");
        s.stock(sku, 1);
        var a = new Till(s.terminal(4), "29");
        var b = new Till(s.terminal(5), "29");
        a.allocate();
        b.allocate();
        var shiftA = UUID.randomUUID();
        var shiftB = UUID.randomUUID();
        a.push(List.of(a.shiftOpened(shiftA, s.cashier, "0.00"),
                a.cashSale(UUID.randomUUID(), shiftA, a.nextInvoice(), s.cashier, sku, 1, "399.00", "12", "400.00")))
                .expect(200);
        b.push(List.of(b.shiftOpened(shiftB, s.manager, "0.00"),
                b.cashSale(UUID.randomUUID(), shiftB, b.nextInvoice(), s.manager, sku, 1, "399.00", "12", "400.00")))
                .expect(200);

        assertThat(s.onHand(sku)).isEqualTo(-1);
        var view = find(a.pull(0).path("stock"), "sku", sku);
        assertThat(view.path("onHand").asLong()).isEqualTo(-1);
        eventually(() -> alerts(s).stream().anyMatch(x -> x.path("type").asText().equals("NEGATIVE_STOCK")
                && x.toString().contains(sku)));

        // Online sales still refuse to go below zero.
        var online = s.cashierApi().post("/v1/sales", sale(UUID.randomUUID(), s.code, null, List.of(line(sku, 1)),
                List.of(pay("CARD", "399.00"))));
        assertThat(online.status()).isEqualTo(409);
    }

    @Test
    void deltaPullReturnsOnlyWhatChangedSinceTheLastVersion() {
        var s = shop();
        String keep = product("10.00", "5");
        String change = product("20.00", "5");
        var till = new Till(s.terminal(6), "29");
        var full = till.pull(0);
        assertThat(full.path("full").asBoolean()).isTrue();
        find(full.path("products"), "sku", keep);
        long version = full.path("version").asLong();

        var p = admin().get("/v1/products/" + change).expect(200).body();
        Map<String, Object> body = new HashMap<>();
        p.fields().forEachRemaining(e -> body.put(e.getKey(), e.getValue()));
        body.put("price", new BigDecimal("22.00"));
        admin().put("/v1/products/" + change, body).expect(200);

        var delta = till.pull(version);
        assertThat(delta.path("full").asBoolean()).isFalse();
        var skus = new ArrayList<String>();
        delta.path("products").forEach(n -> skus.add(n.path("sku").asText()));
        assertThat(skus).contains(change).doesNotContain(keep);
        assertThat(find(delta.path("products"), "sku", change).path("price").decimalValue()).isEqualByComparingTo("22.00");
    }

    @Test
    void onlyThatTerminalMayPushOrPull() {
        var s = shop();
        var mine = new Till(s.terminal(7), "29");
        var other = s.terminal(8);
        var r = other.api().post("/v1/sync/push", Map.of("terminalId", mine.terminal().id(), "epoch", mine.epoch(),
                "ops", List.of()));
        assertThat(r.status()).isEqualTo(403);
        assertThat(s.cashierApi().get("/v1/sync/pull?since=0").status()).isEqualTo(403);
    }

    private List<JsonNode> alerts(Shop s) {
        var out = new ArrayList<JsonNode>();
        s.managerApi().get("/v1/stores/" + s.code + "/alerts?limit=100").expect(200).body().forEach(out::add);
        return out;
    }
}
