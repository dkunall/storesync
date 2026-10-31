package com.storesync.promo.adapter;

import com.storesync.catalog.application.CatalogService;
import com.storesync.promo.application.PromotionService;
import com.storesync.promo.domain.BasketLine;
import com.storesync.promo.domain.Promotion;
import com.storesync.promo.domain.Solution;
import com.storesync.shared.error.ApiException;
import com.storesync.shared.security.Caller;
import com.storesync.shared.security.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
class PromotionController {

    private final PromotionService promos;
    private final CatalogService catalog;
    private final Clock clock;

    PromotionController(PromotionService promos, CatalogService catalog, Clock clock) {
        this.promos = promos;
        this.catalog = catalog;
        this.clock = clock;
    }

    record PromotionRequest(@NotBlank String code, @NotNull Promotion.Type type, @NotNull Promotion.Rules rules,
                            int priority, Boolean stackable, Instant startsAt, Instant endsAt) {
    }

    record QuoteLine(@NotBlank String sku, int qty) {
    }

    record QuoteRequest(@NotEmpty List<@Valid QuoteLine> lines) {
    }

    @PostMapping("/v1/promotions")
    @ResponseStatus(HttpStatus.CREATED)
    Promotion create(Caller caller, @Valid @RequestBody PromotionRequest r) {
        return promos.create(caller, new Promotion(UUID.randomUUID(), r.code(), r.type(), r.rules(), r.priority(),
                r.stackable() == null || r.stackable(), r.startsAt(), r.endsAt(), true));
    }

    @GetMapping("/v1/promotions")
    List<Promotion> list(Caller caller) {
        return promos.list(caller);
    }

    @PostMapping("/v1/promotions/{code}/deactivate")
    Promotion deactivate(Caller caller, @PathVariable String code) {
        return promos.setActive(caller, code, false);
    }

    /** What the best-offer search would give this basket right now, at shelf prices. Does not sell anything. */
    @PostMapping("/v1/promotions/quote")
    Solution quote(Caller caller, @Valid @RequestBody QuoteRequest r) {
        caller.require(Role.ADMIN, Role.MANAGER, Role.CASHIER, Role.TERMINAL);
        var products = catalog.find(r.lines().stream().map(QuoteLine::sku).toList());
        var lines = new ArrayList<BasketLine>();
        int no = 1;
        for (QuoteLine l : r.lines()) {
            var p = products.get(l.sku());
            if (p == null || l.qty() <= 0) {
                throw ApiException.invalid("Unknown sku or bad quantity on line " + no);
            }
            lines.add(new BasketLine(no++, l.sku(), l.qty(), p.price()));
        }
        return promos.best(lines, clock.instant());
    }
}
