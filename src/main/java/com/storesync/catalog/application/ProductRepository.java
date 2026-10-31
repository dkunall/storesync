package com.storesync.catalog.application;

import com.storesync.catalog.domain.Product;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository {

    /** @return false if the SKU or barcode already exists */
    boolean insert(Product p);

    /** @return the previous version, if the SKU existed */
    Optional<Product> update(Product p);

    Optional<Product> find(String sku);

    Optional<Product> byBarcode(String barcode);

    Map<String, Product> find(Collection<String> skus);

    List<Product> changedSince(long cursor);

    List<Product> bySupplier(UUID supplierId);

    List<Product> all();
}
