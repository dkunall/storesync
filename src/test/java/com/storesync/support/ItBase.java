package com.storesync.support;

import com.fasterxml.jackson.databind.JsonNode;
import org.awaitility.Awaitility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * The whole app on a random port against real Postgres, Kafka and Redis, one shared Spring context. Every test
 * builds its own store, staff, terminals and SKUs with random codes, so tests never see each other's data.
 */
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ItBase.TestBeans.class)
public abstract class ItBase {

    public static final String ADMIN = "admin@it.example.test";
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return TestJwt.decoder();
        }
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcClient db;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        var pg = Containers.postgres();
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", () -> Containers.DB_PASSWORD);
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "40");
        r.add("spring.kafka.bootstrap-servers", () -> Containers.kafka().getBootstrapServers());
        r.add("spring.data.redis.host", () -> Containers.redis().getHost());
        r.add("spring.data.redis.port", () -> Containers.redis().getMappedPort(6379));
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://test-issuer/default");
        r.add("storesync.security.admin-subjects", () -> ADMIN);
        r.add("storesync.outbox.poll", () -> "PT0.1S");
        r.add("storesync.invoices.block-size", () -> "20");
    }

    protected Api anon() {
        return Api.at(port);
    }

    protected Api as(String subject) {
        return Api.at(port).as(TestJwt.user(subject));
    }

    protected Api admin() {
        return as(ADMIN);
    }

    protected static String code(int n) {
        var sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public record Terminal(String id, int number, String subject, int epoch, Api api) {
    }

    /** A store with one manager and one cashier. Karnataka (29) unless told otherwise. */
    public final class Shop {
        public final String code;
        public final String manager;
        public final String cashier;

        Shop(String code, String manager, String cashier) {
            this.code = code;
            this.manager = manager;
            this.cashier = cashier;
        }

        public Api managerApi() {
            return as(manager);
        }

        public Api cashierApi() {
            return as(cashier);
        }

        public Terminal terminal(int number) {
            String subject = "till-" + code.toLowerCase() + "-" + number + "@it.example.test";
            var t = managerApi().post("/v1/stores/" + code + "/terminals", Map.of("number", number, "subject", subject))
                    .expect(201).body();
            return new Terminal(t.path("id").asText(), number, subject, t.path("epoch").asInt(), as(subject));
        }

        public void stock(String sku, long counted) {
            managerApi().post("/v1/stores/" + code + "/stock-counts",
                    Map.of("id", UUID.randomUUID(), "sku", sku, "counted", counted)).expect(201);
        }

        public long onHand(String sku) {
            return managerApi().get("/v1/stores/" + code + "/stock/" + sku).expect(200).body().path("onHand").asLong();
        }
    }

    protected Shop shop() {
        return shop("29");
    }

    protected Shop shop(String state) {
        String c = "S" + code(3);
        admin().post("/v1/stores", Map.of("code", c, "name", "Test store " + c, "stateCode", state,
                "gstin", state + "ABCDE1234F1Z5")).expect(201);
        String manager = "mgr-" + c.toLowerCase() + "@it.example.test";
        String cashier = "cashier-" + c.toLowerCase() + "@it.example.test";
        admin().post("/v1/stores/" + c + "/staff", Map.of("subject", manager, "role", "MANAGER", "name", "Manager " + c))
                .expect(201);
        admin().post("/v1/stores/" + c + "/staff", Map.of("subject", cashier, "role", "CASHIER", "name", "Cashier " + c))
                .expect(201);
        return new Shop(c, manager, cashier);
    }

    /** A product priced GST-inclusive. */
    protected String product(String price, String taxRate) {
        return product(price, taxRate, null);
    }

    protected String product(String price, String taxRate, String supplierId) {
        String sku = "SKU-" + code(6);
        Map<String, Object> b = new HashMap<>();
        b.put("sku", sku);
        b.put("barcode", ean13("89" + String.format("%010d", Math.floorMod(RANDOM.nextLong(), 10_000_000_000L))));
        b.put("name", "Test item " + sku);
        b.put("hsn", "6109");
        b.put("taxRate", new BigDecimal(taxRate));
        b.put("price", new BigDecimal(price));
        b.put("cost", new BigDecimal(price).divide(BigDecimal.TWO, 2, java.math.RoundingMode.HALF_UP));
        b.put("lowStockLevel", 2);
        if (supplierId != null) {
            b.put("supplierId", supplierId);
        }
        admin().post("/v1/products", b).expect(201);
        return sku;
    }

    /** Appends the EAN-13 check digit to twelve digits. */
    protected static String ean13(String twelve) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int d = twelve.charAt(i) - '0';
            sum += i % 2 == 0 ? d : 3 * d;
        }
        return twelve + (10 - sum % 10) % 10;
    }

    protected static Map<String, Object> line(String sku, int qty) {
        return Map.of("sku", sku, "qty", qty);
    }

    protected static Map<String, Object> pay(String method, String amount) {
        return "CASH".equals(method) ? Map.of("method", method, "amount", new BigDecimal(amount))
                : Map.of("method", method, "amount", new BigDecimal(amount), "reference", "REF-" + code(8));
    }

    protected static Map<String, Object> sale(UUID id, String store, UUID shiftId, List<Map<String, Object>> lines,
                                              List<Map<String, Object>> payments) {
        Map<String, Object> b = new HashMap<>();
        b.put("id", id);
        b.put("store", store);
        if (shiftId != null) {
            b.put("shiftId", shiftId);
        }
        b.put("lines", lines);
        b.put("payments", payments);
        return b;
    }

    protected static void eventually(BooleanSupplier check) {
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(150))
                .until(check::getAsBoolean);
    }

    protected long count(String sql, Object... params) {
        return db.sql(sql).params(params).query(Long.class).single();
    }

    protected static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode n : array) {
            if (n.path(field).asText().equals(value)) {
                return n;
            }
        }
        throw new AssertionError("no element with " + field + "=" + value + " in " + array);
    }
}
