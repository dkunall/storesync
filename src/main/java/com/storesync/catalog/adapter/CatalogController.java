package com.storesync.catalog.adapter;

import com.storesync.catalog.application.CatalogService;
import com.storesync.catalog.domain.Product;
import com.storesync.shared.security.Caller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
class CatalogController {

    private final CatalogService catalog;

    CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    record ProductRequest(@NotBlank String sku, @NotBlank String barcode, @NotBlank String name, @NotBlank String hsn,
                          @NotNull BigDecimal taxRate, @NotNull BigDecimal price, BigDecimal cost, UUID supplierId,
                          Integer lowStockLevel, Boolean active) {
        Product toProduct(String sku) {
            return new Product(sku, barcode, name, hsn, taxRate, price, cost, supplierId,
                    lowStockLevel == null ? 5 : lowStockLevel, active == null || active);
        }
    }

    @PostMapping("/v1/products")
    @ResponseStatus(HttpStatus.CREATED)
    Product create(Caller caller, @Valid @RequestBody ProductRequest r) {
        return catalog.create(caller, r.toProduct(r.sku()));
    }

    @PutMapping("/v1/products/{sku}")
    Product update(Caller caller, @PathVariable String sku, @Valid @RequestBody ProductRequest r) {
        return catalog.update(caller, r.toProduct(sku));
    }

    @GetMapping("/v1/products")
    List<Product> list(Caller caller) {
        return catalog.list(caller);
    }

    @GetMapping("/v1/products/{sku}")
    Product get(Caller caller, @PathVariable String sku) {
        return catalog.get(caller, sku);
    }

    @GetMapping("/v1/products/by-barcode/{code}")
    Product byBarcode(Caller caller, @PathVariable String code) {
        return catalog.byBarcode(caller, code);
    }
}
