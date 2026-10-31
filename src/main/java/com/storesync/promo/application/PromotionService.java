package com.storesync.promo.application;

import com.storesync.promo.domain.BasketLine;
import com.storesync.promo.domain.Offer;
import com.storesync.promo.domain.PromotionEngine;
import com.storesync.promo.domain.Promotion;
import com.storesync.promo.domain.Solution;
import com.storesync.shared.error.ApiException;
import com.storesync.shared.security.Caller;
import com.storesync.shared.security.Role;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class PromotionService {

    private final PromotionRepository repo;
    private final PromotionEngine engine = new PromotionEngine();
    private final MeterRegistry meters;
    private final Timer solveTimer;

    PromotionService(PromotionRepository repo, MeterRegistry meters) {
        this.repo = repo;
        this.meters = meters;
        this.solveTimer = Timer.builder("storesync.promo.solve").publishPercentileHistogram().register(meters);
    }

    @Transactional
    public Promotion create(Caller caller, Promotion p) {
        caller.require(Role.ADMIN);
        if (!repo.insert(p)) {
            var existing = repo.find(p.code()).orElseThrow();
            if (sameDefinition(existing, p)) {
                return existing;
            }
            throw ApiException.conflict("promotion-exists", "Promotion " + p.code() + " already exists");
        }
        return p;
    }

    private static boolean sameDefinition(Promotion a, Promotion b) {
        return a.type() == b.type() && a.toOffer().equals(b.toOffer()) && java.util.Objects.equals(a.startsAt(), b.startsAt())
                && java.util.Objects.equals(a.endsAt(), b.endsAt());
    }

    @Transactional
    public Promotion setActive(Caller caller, String code, boolean active) {
        caller.require(Role.ADMIN);
        repo.find(code).orElseThrow(() -> ApiException.notFound("promotion " + code));
        repo.setActive(code, active);
        return repo.find(code).orElseThrow();
    }

    public List<Promotion> list(Caller caller) {
        caller.require(Role.ADMIN, Role.MANAGER, Role.CASHIER, Role.TERMINAL);
        return repo.all();
    }

    public List<Promotion> changedSince(long cursor) {
        return repo.changedSince(cursor);
    }

    /** Best combination of the promotions running at {@code at} for this basket. */
    public Solution best(List<BasketLine> lines, Instant at) {
        List<Offer> offers = repo.all().stream().filter(p -> p.runsAt(at)).map(Promotion::toOffer).toList();
        long start = System.nanoTime();
        Solution s = engine.best(lines, offers);
        solveTimer.record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
        if (!s.exhaustive()) {
            meters.counter("storesync.promo.budget_exhausted").increment();
        }
        return s;
    }
}
