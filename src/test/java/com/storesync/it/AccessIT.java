package com.storesync.it;

import com.storesync.support.ItBase;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccessIT extends ItBase {

    @Test
    void anonymousCallersAreTurnedAway() {
        var s = shop();
        assertThat(anon().get("/v1/stores/" + s.code + "/stock").status()).isEqualTo(401);
        assertThat(anon().post("/v1/sales", Map.of()).status()).isEqualTo(401);
        assertThat(anon().get("/actuator/health").status()).isEqualTo(200);
    }

    @Test
    void staffOnlySeeTheirOwnStore() {
        var a = shop();
        var b = shop();
        String sku = product("50.00", "5");
        a.stock(sku, 5);

        assertThat(b.cashierApi().get("/v1/stores/" + a.code + "/stock").status()).isEqualTo(403);
        assertThat(b.managerApi().get("/v1/stores/" + a.code + "/reconciliation?day=2026-10-01").status())
                .isEqualTo(403);
        assertThat(b.cashierApi().post("/v1/sales", sale(UUID.randomUUID(), a.code, null, List.of(line(sku, 1)),
                List.of(pay("UPI", "50.00")))).status()).isEqualTo(403);
        assertThat(b.managerApi().post("/v1/stores/" + a.code + "/stock-counts",
                Map.of("id", UUID.randomUUID(), "sku", sku, "counted", 0)).status()).isEqualTo(403);
        assertThat(a.onHand(sku)).isEqualTo(5);
    }

    @Test
    void cashiersCannotManageTheCatalogOrStock() {
        var s = shop();
        String sku = product("50.00", "5");
        var body = Map.of("sku", "SKU-" + code(6), "barcode", ean13("890000000001"), "name", "Rogue",
                "hsn", "6109", "taxRate", new BigDecimal("5"), "price", new BigDecimal("1.00"));
        assertThat(s.cashierApi().post("/v1/products", body).status()).isEqualTo(403);
        assertThat(s.managerApi().post("/v1/products", body).status()).isEqualTo(403);
        assertThat(s.cashierApi().post("/v1/stores/" + s.code + "/stock-counts",
                Map.of("id", UUID.randomUUID(), "sku", sku, "counted", 100)).status()).isEqualTo(403);
        assertThat(s.cashierApi().post("/v1/stores", Map.of("code", "X" + code(3), "name", "x", "stateCode", "29",
                "gstin", "29ABCDE1234F1Z5")).status()).isEqualTo(403);
    }

    @Test
    void aTerminalOnlyActsForItself() {
        var s = shop();
        var one = s.terminal(1);
        var two = s.terminal(2);
        assertThat(one.api().post("/v1/terminals/" + two.id() + "/invoice-blocks", Map.of()).status()).isEqualTo(403);
        assertThat(one.api().post("/v1/terminals/" + one.id() + "/invoice-blocks", Map.of()).status()).isEqualTo(201);
        assertThat(s.cashierApi().get("/v1/sync/pull?since=0").status()).isEqualTo(403);
        assertThat(s.cashierApi().post("/v1/terminals/" + one.id() + "/invoice-blocks", Map.of()).status())
                .isEqualTo(403);
    }
}
