package com.storesync.catalog.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storesync.catalog.application.ProductCache;
import com.storesync.catalog.domain.Product;
import com.storesync.shared.config.StoreSyncProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/** Barcode to product JSON in Redis. Any Redis failure is counted, logged at debug and treated as a miss. */
@Component
class RedisProductCache implements ProductCache {

    private static final Logger log = LoggerFactory.getLogger(RedisProductCache.class);
    private static final String PREFIX = "storesync:barcode:";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Duration ttl;
    private final MeterRegistry meters;

    RedisProductCache(StringRedisTemplate redis, ObjectMapper json, StoreSyncProperties props, MeterRegistry meters) {
        this.redis = redis;
        this.json = json;
        this.ttl = props.cache().barcodeTtl();
        this.meters = meters;
    }

    @Override
    public Optional<Product> get(String barcode) {
        try {
            String v = redis.opsForValue().get(PREFIX + barcode);
            return v == null ? Optional.empty() : Optional.of(json.readValue(v, Product.class));
        } catch (Exception e) {
            failed("get", e);
            return Optional.empty();
        }
    }

    @Override
    public void put(Product p) {
        try {
            redis.opsForValue().set(PREFIX + p.barcode(), json.writeValueAsString(p), ttl);
        } catch (Exception e) {
            failed("put", e);
        }
    }

    @Override
    public void evict(String barcode) {
        try {
            redis.delete(PREFIX + barcode);
        } catch (Exception e) {
            failed("evict", e);
        }
    }

    private void failed(String op, Exception e) {
        meters.counter("storesync.catalog.cache.errors", "op", op).increment();
        log.debug("barcode cache {} failed: {}", op, e.toString());
    }
}
