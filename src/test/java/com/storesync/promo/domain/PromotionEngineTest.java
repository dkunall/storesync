package com.storesync.promo.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionEngineTest {

    static final int RANDOM_BASKETS = 500;
    final PromotionEngine engine = new PromotionEngine();

    static BasketLine line(int no, String sku, int qty, String price) {
        return new BasketLine(no, sku, qty, new BigDecimal(price));
    }

    static Offer.PercentOff pct(String code, String percent, String... skus) {
        return new Offer.PercentOff(code, 0, true, Set.of(skus), new BigDecimal(percent));
    }

    @Test
    void noOffersMeansNoSaving() {
        var s = engine.best(List.of(line(1, "A", 2, "10.00")), List.of());
        assertThat(s.saving()).isEqualByComparingTo("0");
        assertThat(s.applied()).isEmpty();
    }

    @Test
    void buyTwoGetOneGivesTheCheapestUnitOfEachGroup() {
        var b2g1 = new Offer.BuyXGetY("B2G1", 0, true, Set.of("A", "B"), 2, 1);
        var s = engine.best(List.of(line(1, "A", 2, "100.00"), line(2, "B", 1, "40.00")), List.of(b2g1));
        assertThat(s.saving()).isEqualByComparingTo("40.00");
        assertThat(s.lineDiscounts()).containsEntry(2, new BigDecimal("40.00"));
    }

    @Test
    void picksTheBetterOfTwoOffersCompetingForTheSameUnits() {
        // 3 units of A at 100: 10% off each = 30, B2G1 = 100. Same units, so only one can have them.
        var s = engine.best(List.of(line(1, "A", 3, "100.00")),
                List.of(pct("TENPC", "10", "A"), new Offer.BuyXGetY("B2G1", 5, true, Set.of("A"), 2, 1)));
        assertThat(s.saving()).isEqualByComparingTo("100.00");
        assertThat(s.applied()).extracting(Solution.Applied::code).containsExactly("B2G1");
    }

    @Test
    void mixesOffersWhenUnitsAllowIt() {
        // 4 units: B2G1 on three of them (100) and 10% on the fourth (10) beats either alone.
        var s = engine.best(List.of(line(1, "A", 4, "100.00")),
                List.of(pct("TENPC", "10", "A"), new Offer.BuyXGetY("B2G1", 0, true, Set.of("A"), 2, 1)));
        assertThat(s.saving()).isEqualByComparingTo("110.00");
        assertThat(s.lineDiscounts().get(1)).isEqualByComparingTo("110.00");
    }

    @Test
    void basketOfferAppliesToWhatIsLeftAfterItemOffers() {
        var basket = new Offer.BasketOff("FIVEPC500", 0, true, new BigDecimal("500"), new BigDecimal("5"), null, null);
        var s = engine.best(List.of(line(1, "A", 3, "200.00")),
                List.of(new Offer.BuyXGetY("B2G1", 0, true, Set.of("A"), 2, 1), basket));
        // 600 - 200 = 400 is under the 500 threshold, so B2G1 alone (200) vs basket alone (30): B2G1 wins.
        assertThat(s.saving()).isEqualByComparingTo("200.00");
        var s2 = engine.best(List.of(line(1, "A", 6, "200.00")),
                List.of(new Offer.BuyXGetY("B2G1", 0, true, Set.of("A"), 2, 1), basket));
        // 1200 - 400 = 800 >= 500, so 5% of 800 = 40 more.
        assertThat(s2.saving()).isEqualByComparingTo("440.00");
    }

    @Test
    void nonStackableOfferStandsAlone() {
        var flat = new Offer.BasketOff("FLAT150", 0, false, new BigDecimal("1000"), null, new BigDecimal("150"), null);
        var s = engine.best(List.of(line(1, "A", 10, "110.00")), List.of(pct("FIVEPC", "5", "A"), flat));
        // 5% of 1100 = 55 plus nothing (flat cannot stack) vs flat alone 150.
        assertThat(s.saving()).isEqualByComparingTo("150.00");
        assertThat(s.applied()).extracting(Solution.Applied::code).containsExactly("FLAT150");
    }

    @Test
    void bundleSavingIsSplitAcrossItsLines() {
        var bundle = new Offer.BundlePrice("COMBO", 0, true, List.of("TEA", "MUG"), new BigDecimal("250.00"));
        var s = engine.best(List.of(line(1, "TEA", 1, "200.00"), line(2, "MUG", 1, "100.00")), List.of(bundle));
        assertThat(s.saving()).isEqualByComparingTo("50.00");
        assertThat(s.lineDiscounts().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("50.00");
    }

    @Test
    void percentCapIsHonoured() {
        var capped = new Offer.BasketOff("TENCAP", 0, true, BigDecimal.ZERO, new BigDecimal("10"), null, new BigDecimal("75"));
        var s = engine.best(List.of(line(1, "A", 1, "1000.00")), List.of(capped));
        assertThat(s.saving()).isEqualByComparingTo("75.00");
    }

    /**
     * The pruned search must find exactly what exhaustive enumeration of every multiplicity vector finds, on random
     * baskets and random offer mixes. Fails if the bound ever cuts off a better solution.
     */
    @Test
    void branchAndBoundMatchesExhaustiveSearch() {
        var rnd = new Random(1031);
        String[] skus = {"A", "B", "C", "D"};
        long prunedNodes = 0, fullNodes = 0;
        for (int b = 0; b < RANDOM_BASKETS; b++) {
            var lines = new ArrayList<BasketLine>();
            int nLines = 1 + rnd.nextInt(4);
            for (int i = 0; i < nLines; i++) {
                lines.add(line(i + 1, skus[rnd.nextInt(skus.length)], 1 + rnd.nextInt(4),
                        BigDecimal.valueOf(100 + rnd.nextInt(50_000), 2).toPlainString()));
            }
            var offers = new ArrayList<Offer>();
            int nOffers = rnd.nextInt(5);
            for (int i = 0; i < nOffers; i++) {
                offers.add(randomOffer(rnd, "O" + i, skus));
            }
            Solution fast = engine.best(lines, offers);
            assertThat(fast.exhaustive()).isTrue();
            List<Offer> ordered = PromotionEngine.order(offers);
            int[] bestTimes = null;
            Solution best = null;
            int[] times = new int[ordered.size()];
            long[] visited = {0};
            Solution[] holder = {null};
            int[][] holderTimes = {null};
            enumerate(lines, ordered, times, 0, visited, holder, holderTimes);
            best = holder[0];
            bestTimes = holderTimes[0];
            prunedNodes += fast.nodes();
            fullNodes += visited[0];
            assertThat(fast.saving()).as("basket %d %s %s", b, lines, offers).isEqualByComparingTo(best.saving());
            assertThat(PromotionEngine.timesOf(fast, ordered)).as("basket %d", b).isEqualTo(bestTimes);
            assertThat(fast.lineDiscounts().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isEqualByComparingTo(fast.saving());
            for (var l : lines) {
                BigDecimal d = fast.lineDiscounts().getOrDefault(l.lineNo(), BigDecimal.ZERO);
                assertThat(d).isLessThanOrEqualTo(l.unitPrice().multiply(BigDecimal.valueOf(l.qty())));
            }
        }
        assertThat(prunedNodes).isLessThanOrEqualTo(fullNodes);
    }

    private static void enumerate(List<BasketLine> lines, List<Offer> ordered, int[] times, int i, long[] visited,
                                  Solution[] best, int[][] bestTimes) {
        if (i == ordered.size()) {
            visited[0]++;
            Optional<Solution> s = PromotionEngine.evaluate(lines, ordered, times);
            if (s.isPresent() && (best[0] == null || PromotionEngine.better(s.get(), times, best[0], bestTimes[0]))) {
                best[0] = s.get();
                bestTimes[0] = times.clone();
            }
            return;
        }
        int maxUnits = lines.stream().mapToInt(BasketLine::qty).sum();
        for (int m = 0; m <= maxUnits; m++) {
            times[i] = m;
            enumerate(lines, ordered, times, i + 1, visited, best, bestTimes);
        }
        times[i] = 0;
    }

    private static Offer randomOffer(Random rnd, String code, String[] skus) {
        boolean stack = rnd.nextInt(5) != 0;
        int prio = rnd.nextInt(3);
        Set<String> some = new java.util.HashSet<>(List.of(skus[rnd.nextInt(skus.length)], skus[rnd.nextInt(skus.length)]));
        return switch (rnd.nextInt(4)) {
            case 0 -> new Offer.PercentOff(code, prio, stack, some, BigDecimal.valueOf(5 + rnd.nextInt(40)));
            case 1 -> new Offer.BuyXGetY(code, prio, stack, some, 1 + rnd.nextInt(2), 1);
            case 2 -> {
                String a = skus[rnd.nextInt(skus.length)];
                String c = skus[(java.util.Arrays.asList(skus).indexOf(a) + 1 + rnd.nextInt(skus.length - 1)) % skus.length];
                yield new Offer.BundlePrice(code, prio, stack, List.of(a, c), BigDecimal.valueOf(100 + rnd.nextInt(40_000), 2));
            }
            default -> rnd.nextBoolean()
                    ? new Offer.BasketOff(code, prio, stack, BigDecimal.valueOf(rnd.nextInt(800)), BigDecimal.valueOf(2 + rnd.nextInt(15)), null,
                    rnd.nextBoolean() ? BigDecimal.valueOf(20 + rnd.nextInt(100)) : null)
                    : new Offer.BasketOff(code, prio, stack, BigDecimal.valueOf(rnd.nextInt(800)), null, BigDecimal.valueOf(10 + rnd.nextInt(100)), null);
        };
    }

    @Test
    void nodeBudgetStopsTheSearchAndSaysSo() {
        var lines = List.of(line(1, "A", 12, "50.00"), line(2, "B", 12, "70.00"));
        var offers = new ArrayList<Offer>();
        for (int i = 0; i < 8; i++) {
            offers.add(pct("P" + i, String.valueOf(5 + i), "A", "B"));
        }
        var s = new PromotionEngine(50).best(lines, offers);
        assertThat(s.exhaustive()).isFalse();
        assertThat(s.saving().signum()).isGreaterThanOrEqualTo(0);
    }
}
