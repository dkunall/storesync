package com.storesync.catalog.application;

import com.storesync.catalog.domain.Product;
import com.storesync.shared.error.ApiException;
import com.storesync.shared.security.Caller;
import com.storesync.shared.security.Role;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CatalogService {

    private final ProductRepository repo;
    private final ProductCache cache;
    private final MeterRegistry meters;

    CatalogService(ProductRepository repo, ProductCache cache, MeterRegistry meters) {
        this.repo = repo;
        this.cache = cache;
        this.meters = meters;
    }

    @Transactional
    public Product create(Caller caller, Product p) {
        caller.require(Role.ADMIN);
        if (!repo.insert(p)) {
            var existing = repo.find(p.sku());
            if (existing.isPresent() && existing.get().equals(p)) {
                return p;
            }
            throw ApiException.conflict("product-exists", "SKU or barcode already in the catalog");
        }
        evictAfterCommit(p.barcode());
        return p;
    }

    @Transactional
    public Product update(Caller caller, Product p) {
        caller.require(Role.ADMIN);
        var before = repo.update(p).orElseThrow(() -> ApiException.notFound("product " + p.sku()));
        evictAfterCommit(before.barcode());
        evictAfterCommit(p.barcode());
        return p;
    }

    /** Evict once the new row is visible, so a concurrent lookup cannot re-cache the old one after the evict. */
    private void evictAfterCommit(String barcode) {
        cache.evict(barcode);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.evict(barcode);
                }
            });
        }
    }

    public Product byBarcode(Caller caller, String barcode) {
        caller.require(Role.ADMIN, Role.MANAGER, Role.CASHIER, Role.TERMINAL);
        var hit = cache.get(barcode);
        if (hit.isPresent()) {
            meters.counter("storesync.catalog.barcode.lookups", "cache", "hit").increment();
            return hit.get();
        }
        meters.counter("storesync.catalog.barcode.lookups", "cache", "miss").increment();
        Product p = repo.byBarcode(barcode).orElseThrow(() -> ApiException.notFound("barcode " + barcode));
        cache.put(p);
        return p;
    }

    public Product get(Caller caller, String sku) {
        caller.require(Role.ADMIN, Role.MANAGER, Role.CASHIER, Role.TERMINAL);
        return repo.find(sku).orElseThrow(() -> ApiException.notFound("product " + sku));
    }

    public List<Product> list(Caller caller) {
        caller.require(Role.ADMIN, Role.MANAGER, Role.CASHIER, Role.TERMINAL);
        return repo.all();
    }

    public Map<String, Product> find(Collection<String> skus) {
        return repo.find(skus);
    }

    public java.util.Optional<Product> byBarcode(String barcode) {
        return repo.byBarcode(barcode);
    }

    public List<Product> changedSince(long cursor) {
        return repo.changedSince(cursor);
    }

    public List<Product> bySupplier(UUID supplier) {
        return repo.bySupplier(supplier);
    }

    public List<Product> all() {
        return repo.all();
    }
}
