package com.storesync.catalog.application;

import com.storesync.catalog.domain.Product;

import java.util.Optional;

/**
 * Port for the barcode lookup cache. Best effort: implementations swallow their own failures, return empty, and
 * the caller falls back to the database. It never holds anything the database does not.
 */
public interface ProductCache {

    Optional<Product> get(String barcode);

    void put(Product p);

    void evict(String barcode);
}
