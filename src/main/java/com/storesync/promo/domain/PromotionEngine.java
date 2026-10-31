package com.storesync.promo.domain;

import com.storesync.shared.domain.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Picks the offer set with the largest total saving by branch and bound. Offers are visited in a fixed order (item
 * offers by priority, then basket offers); for each offer the search tries every multiplicity from the most it can
 * apply down to zero. A branch is cut when what it has saved plus an optimistic bound for the offers still to visit
 * (each one's best standalone saving on the untouched basket) cannot reach the best complete solution so far.
 * Within one application, units are taken most expensive first. Ties go to fewer offers, then fewer applications,
 * then the earlier offers in visiting order, so the answer is deterministic.
 */
public final class PromotionEngine {

    public static final long DEFAULT_NODE_BUDGET = 200_000;

    private final long nodeBudget;

    public PromotionEngine(long nodeBudget) {
        this.nodeBudget = nodeBudget;
    }

    public PromotionEngine() {
        this(DEFAULT_NODE_BUDGET);
    }

    /** Visiting order. Exposed so tests can enumerate the same space exhaustively. */
    public static List<Offer> order(List<Offer> offers) {
        return offers.stream().sorted(Comparator.comparing(Offer::basketLevel)
                .thenComparing(Comparator.comparingInt(Offer::priority).reversed())
                .thenComparing(Offer::code)).toList();
    }

    public Solution best(List<BasketLine> lines, List<Offer> offers) {
        List<Offer> ordered = order(offers);
        int n = ordered.size();
        State empty = State.start(lines, n);
        BigDecimal[] standalone = new BigDecimal[n];
        for (int i = 0; i < n; i++) {
            standalone[i] = Money.ZERO;
            Offer o = ordered.get(i);
            for (int m = empty.maxTimes(o); m >= 1; m--) {
                State s = empty.apply(o, i, m);
                if (s != null && s.saving.compareTo(standalone[i]) > 0) {
                    standalone[i] = s.saving;
                }
            }
        }
        BigDecimal[] itemSuffix = new BigDecimal[n + 1];
        BigDecimal[] basketSuffix = new BigDecimal[n + 1];
        itemSuffix[n] = Money.ZERO;
        basketSuffix[n] = Money.ZERO;
        for (int i = n - 1; i >= 0; i--) {
            boolean basket = ordered.get(i).basketLevel();
            itemSuffix[i] = itemSuffix[i + 1].add(basket ? Money.ZERO : standalone[i]);
            basketSuffix[i] = basket ? basketSuffix[i + 1].max(standalone[i]) : basketSuffix[i + 1];
        }
        var search = new Search(ordered, itemSuffix, basketSuffix, nodeBudget);
        search.run(0, empty);
        return search.best.toSolution(ordered, !search.cut, search.nodes);
    }

    /**
     * Applies offer {@code i} of {@link #order(List)} {@code times[i]} times, in order. Empty if that combination
     * breaks a rule. Brute-force reference for tests.
     */
    public static Optional<Solution> evaluate(List<BasketLine> lines, List<Offer> ordered, int[] times) {
        State s = State.start(lines, ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            if (times[i] > 0) {
                if (times[i] > s.maxTimes(ordered.get(i))) {
                    return Optional.empty();
                }
                s = s.apply(ordered.get(i), i, times[i]);
                if (s == null) {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(s.toSolution(ordered, true, 0));
    }

    /** The comparator {@link #best} uses; true if {@code a} should replace {@code b}. */
    public static boolean better(Solution a, int[] aTimes, Solution b, int[] bTimes) {
        return State.better(a.saving(), a.applied().size(), aTimes, b.saving(), b.applied().size(), bTimes);
    }

    private static final class Search {
        final List<Offer> offers;
        final BigDecimal[] itemSuffix;
        final BigDecimal[] basketSuffix;
        final long budget;
        long nodes;
        boolean cut;
        State best;

        Search(List<Offer> offers, BigDecimal[] itemSuffix, BigDecimal[] basketSuffix, long budget) {
            this.offers = offers;
            this.itemSuffix = itemSuffix;
            this.basketSuffix = basketSuffix;
            this.budget = budget;
        }

        void run(int i, State s) {
            nodes++;
            if (nodes > budget) {
                cut = true;
                if (best == null || s.betterThan(best)) {
                    best = s;
                }
                return;
            }
            if (i == offers.size()) {
                if (best == null || s.betterThan(best)) {
                    best = s;
                }
                return;
            }
            if (best != null && s.saving.add(itemSuffix[i]).add(basketSuffix[i]).compareTo(best.saving) < 0) {
                return;
            }
            Offer o = offers.get(i);
            for (int m = s.maxTimes(o); m >= 1 && !cut; m--) {
                State next = s.apply(o, i, m);
                if (next != null) {
                    run(i + 1, next);
                }
            }
            if (!cut) {
                run(i + 1, s);
            }
        }
    }

    /** Immutable search state: units left per line, saving per line, and how often each offer was applied. */
    private static final class State {
        final List<BasketLine> lines;
        final int[] left;
        final BigDecimal[] lineSaving;
        final int[] times;
        final BigDecimal[] offerSaving;
        final BigDecimal saving;
        final int used;
        final boolean nonStackableUsed;
        final boolean basketUsed;

        private State(List<BasketLine> lines, int[] left, BigDecimal[] lineSaving, int[] times, BigDecimal[] offerSaving,
                      BigDecimal saving, int used, boolean nonStackableUsed, boolean basketUsed) {
            this.lines = lines;
            this.left = left;
            this.lineSaving = lineSaving;
            this.times = times;
            this.offerSaving = offerSaving;
            this.saving = saving;
            this.used = used;
            this.nonStackableUsed = nonStackableUsed;
            this.basketUsed = basketUsed;
        }

        static State start(List<BasketLine> lines, int offers) {
            int[] left = lines.stream().mapToInt(BasketLine::qty).toArray();
            BigDecimal[] ls = new BigDecimal[lines.size()];
            Arrays.fill(ls, Money.ZERO);
            BigDecimal[] os = new BigDecimal[offers];
            Arrays.fill(os, Money.ZERO);
            return new State(List.copyOf(lines), left, ls, new int[offers], os, Money.ZERO, 0, false, false);
        }

        /** Line indexes holding {@code skus}, most expensive first. */
        private List<Integer> eligible(java.util.Collection<String> skus) {
            var idx = new ArrayList<Integer>();
            for (int k = 0; k < lines.size(); k++) {
                if (skus.contains(lines.get(k).sku())) {
                    idx.add(k);
                }
            }
            idx.sort(Comparator.comparing((Integer k) -> lines.get(k).unitPrice()).reversed()
                    .thenComparingInt(k -> lines.get(k).lineNo()));
            return idx;
        }

        private int unitsLeft(java.util.Collection<String> skus) {
            return eligible(skus).stream().mapToInt(k -> left[k]).sum();
        }

        int maxTimes(Offer o) {
            if (nonStackableUsed || (!o.stackable() && used > 0)) {
                return 0;
            }
            return switch (o) {
                case Offer.PercentOff p -> unitsLeft(p.skus());
                case Offer.BuyXGetY b -> unitsLeft(b.skus()) / (b.buy() + b.free());
                case Offer.BundlePrice b -> b.skus().stream().mapToInt(s -> unitsLeft(List.of(s))).min().orElse(0);
                case Offer.BasketOff b -> basketUsed ? 0 : 1;
            };
        }

        State apply(Offer o, int index, int m) {
            if (m < 1 || m > maxTimes(o)) {
                return null;
            }
            int[] l = left.clone();
            BigDecimal[] ls = lineSaving.clone();
            BigDecimal got = Money.ZERO;
            for (int t = 0; t < m; t++) {
                BigDecimal one = switch (o) {
                    case Offer.PercentOff p -> percentOnce(p, l, ls);
                    case Offer.BuyXGetY b -> buyGetOnce(b, l, ls);
                    case Offer.BundlePrice b -> bundleOnce(b, l, ls);
                    case Offer.BasketOff b -> basketOnce(b, l, ls);
                };
                if (one == null || one.signum() <= 0) {
                    return null;
                }
                got = got.add(one);
            }
            int[] tm = times.clone();
            tm[index] = m;
            BigDecimal[] os = offerSaving.clone();
            os[index] = got;
            return new State(lines, l, ls, tm, os, saving.add(got), used + 1, nonStackableUsed || !o.stackable(),
                    basketUsed || o.basketLevel());
        }

        private int take(List<Integer> order, int[] l) {
            for (int k : order) {
                if (l[k] > 0) {
                    l[k]--;
                    return k;
                }
            }
            return -1;
        }

        private BigDecimal percentOnce(Offer.PercentOff p, int[] l, BigDecimal[] ls) {
            int k = take(eligible(p.skus()), l);
            BigDecimal s = Money.percentOf(lines.get(k).unitPrice(), p.percent());
            ls[k] = ls[k].add(s);
            return s;
        }

        private BigDecimal buyGetOnce(Offer.BuyXGetY b, int[] l, BigDecimal[] ls) {
            var order = eligible(b.skus());
            int[] picked = new int[b.buy() + b.free()];
            for (int i = 0; i < picked.length; i++) {
                picked[i] = take(order, l);
            }
            BigDecimal s = Money.ZERO;
            for (int i = b.buy(); i < picked.length; i++) {
                int k = picked[i];
                ls[k] = ls[k].add(lines.get(k).unitPrice());
                s = s.add(lines.get(k).unitPrice());
            }
            return s;
        }

        private BigDecimal bundleOnce(Offer.BundlePrice b, int[] l, BigDecimal[] ls) {
            int[] picked = new int[b.skus().size()];
            BigDecimal full = Money.ZERO;
            for (int i = 0; i < picked.length; i++) {
                picked[i] = take(eligible(List.of(b.skus().get(i))), l);
                full = full.add(lines.get(picked[i]).unitPrice());
            }
            BigDecimal s = full.subtract(b.price());
            if (s.signum() <= 0) {
                return null;
            }
            BigDecimal given = Money.ZERO;
            for (int i = 0; i < picked.length; i++) {
                int k = picked[i];
                BigDecimal share = i == picked.length - 1 ? s.subtract(given)
                        : s.multiply(lines.get(k).unitPrice()).divide(full, 2, RoundingMode.HALF_UP);
                ls[k] = ls[k].add(share);
                given = given.add(share);
            }
            return s;
        }

        private BigDecimal basketOnce(Offer.BasketOff b, int[] l, BigDecimal[] ls) {
            BigDecimal net = Money.ZERO;
            BigDecimal[] lineNet = new BigDecimal[lines.size()];
            int largest = 0;
            for (int k = 0; k < lines.size(); k++) {
                lineNet[k] = lines.get(k).unitPrice().multiply(BigDecimal.valueOf(lines.get(k).qty())).subtract(ls[k]);
                net = net.add(lineNet[k]);
                if (lineNet[k].compareTo(lineNet[largest]) > 0) {
                    largest = k;
                }
            }
            if (net.signum() <= 0 || net.compareTo(b.minSpend()) < 0) {
                return null;
            }
            BigDecimal s = b.flat() != null ? b.flat().min(net) : Money.percentOf(net, b.percent());
            if (b.cap() != null) {
                s = s.min(b.cap());
            }
            BigDecimal given = Money.ZERO;
            for (int k = 0; k < lines.size(); k++) {
                if (k == largest) {
                    continue;
                }
                BigDecimal share = s.multiply(lineNet[k]).divide(net, 2, RoundingMode.DOWN);
                ls[k] = ls[k].add(share);
                given = given.add(share);
            }
            ls[largest] = ls[largest].add(s.subtract(given));
            return s;
        }

        boolean betterThan(State o) {
            return better(saving, used, times, o.saving, o.used, o.times);
        }

        static boolean better(BigDecimal aSaving, int aUsed, int[] aTimes, BigDecimal bSaving, int bUsed, int[] bTimes) {
            int c = aSaving.compareTo(bSaving);
            if (c != 0) {
                return c > 0;
            }
            if (aUsed != bUsed) {
                return aUsed < bUsed;
            }
            int at = Arrays.stream(aTimes).sum(), bt = Arrays.stream(bTimes).sum();
            if (at != bt) {
                return at < bt;
            }
            return Arrays.compare(bTimes, aTimes) < 0;
        }

        Solution toSolution(List<Offer> ordered, boolean exhaustive, long nodes) {
            var applied = new ArrayList<Solution.Applied>();
            for (int i = 0; i < ordered.size(); i++) {
                if (times[i] > 0) {
                    applied.add(new Solution.Applied(ordered.get(i).code(), times[i], offerSaving[i]));
                }
            }
            Map<Integer, BigDecimal> perLine = new TreeMap<>();
            for (int k = 0; k < lines.size(); k++) {
                if (lineSaving[k].signum() > 0) {
                    perLine.merge(lines.get(k).lineNo(), lineSaving[k], BigDecimal::add);
                }
            }
            return new Solution(saving, List.copyOf(applied), new LinkedHashMap<>(perLine), exhaustive, nodes);
        }
    }

    /** Multiplicity vector of a solution, in visiting order, so callers can use {@link #better}. */
    public static int[] timesOf(Solution s, List<Offer> ordered) {
        int[] t = new int[ordered.size()];
        for (var a : s.applied()) {
            for (int i = 0; i < ordered.size(); i++) {
                if (ordered.get(i).code().equals(a.code())) {
                    t[i] = a.times();
                }
            }
        }
        return t;
    }
}
