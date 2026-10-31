package com.storesync.it;

import com.storesync.support.ItBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogCacheIT extends ItBase {

    @Autowired
    StringRedisTemplate redis;

    @Test
    void barcodeLookupsAreCachedAndAnUpdateEvictsTheOldEntry() {
        var s = shop();
        String sku = product("100.00", "12");
        var p = admin().get("/v1/products/" + sku).expect(200).body();
        String barcode = p.path("barcode").asText();
        String key = "storesync:barcode:" + barcode;
        assertThat(redis.hasKey(key)).isFalse();

        var first = s.cashierApi().get("/v1/products/by-barcode/" + barcode).expect(200).body();
        assertThat(first.path("sku").asText()).isEqualTo(sku);
        assertThat(redis.hasKey(key)).isTrue();
        assertThat(redis.opsForValue().get(key)).contains(sku);

        Map<String, Object> update = new HashMap<>();
        update.put("sku", sku);
        update.put("barcode", barcode);
        update.put("name", p.path("name").asText());
        update.put("hsn", "6109");
        update.put("taxRate", new BigDecimal("12"));
        update.put("price", new BigDecimal("90.00"));
        admin().put("/v1/products/" + sku, update).expect(200);

        assertThat(redis.hasKey(key)).isFalse();
        var after = s.cashierApi().get("/v1/products/by-barcode/" + barcode).expect(200).body();
        assertThat(after.path("price").decimalValue()).isEqualByComparingTo("90.00");
    }

    @Test
    void unknownAndMalformedBarcodes() {
        var s = shop();
        assertThat(s.cashierApi().get("/v1/products/by-barcode/" + ean13("899999999999")).status()).isEqualTo(404);
        assertThat(s.cashierApi().get("/v1/products/by-barcode/not-a-code").status()).isIn(400, 404);
    }
}
