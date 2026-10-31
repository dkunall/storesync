package com.storesync.promo.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * @param lineDiscounts saving per basket line; sums to {@code saving}
 * @param exhaustive    false if the search hit its node budget and returned the best found so far
 * @param nodes         search nodes visited, for metrics and tests
 */
public record Solution(BigDecimal saving, List<Applied> applied, Map<Integer, BigDecimal> lineDiscounts,
                       boolean exhaustive, long nodes) {

    public record Applied(String code, int times, BigDecimal saving) {
    }
}
