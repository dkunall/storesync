package com.storesync.promo.application;

import com.storesync.promo.domain.Promotion;

import java.util.List;
import java.util.Optional;

public interface PromotionRepository {

    boolean insert(Promotion p);

    Optional<Promotion> find(String code);

    void setActive(String code, boolean active);

    List<Promotion> all();

    List<Promotion> changedSince(long cursor);
}
