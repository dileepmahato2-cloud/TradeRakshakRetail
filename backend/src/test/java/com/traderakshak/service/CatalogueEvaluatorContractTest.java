package com.traderakshak.service;

import com.traderakshak.service.strategy.ast.FieldCatalogue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every field the builder offers must be one the screener can actually evaluate.
 *
 * <p>This is a contract between two lists that had silently drifted apart. {@link FieldCatalogue}
 * is what the Strategy Builder's library shows and what the validator checks against;
 * {@link StrategyConditionEvaluator}'s switch is what the screener evaluates. They used different
 * names for the same things - the catalogue said {@code avg_volume} and {@code BB_MIDDLE}, the
 * evaluator knew {@code VOLUME_SMA} and {@code BB_MID} - and the evaluator's switch ends in
 * {@code default -> 0}.
 *
 * <p>So a condition reading "Volume &gt; Average Volume" was offered by the library, accepted by
 * the validator, saved, and then evaluated as "Volume &gt; 0", which every stock passes. Nothing
 * about the resulting list said it was wrong. A rule that is refused is a bug anyone can see; a
 * rule that quietly becomes {@code x &gt; 0} is one nobody can.
 *
 * <p>The test walks the catalogue rather than a hand-written list, so a field added to the
 * catalogue and not taught to the evaluator fails here rather than in a screener run.
 */
class CatalogueEvaluatorContractTest {

    private StrategyConditionEvaluator evaluator;
    private FieldCatalogue catalogue;
    private Map<String, Object> data;

    @BeforeEach
    void setUp() {
        evaluator = new StrategyConditionEvaluator(new TechnicalIndicatorEngine());
        catalogue = new FieldCatalogue();
        data = sampleSeries(260);
    }

    /**
     * A rising series with a volume spike on the last bar, long enough for a 200-period average.
     *
     * <p>Deliberately not flat: a flat series makes several indicators legitimately zero, which
     * is indistinguishable from the failure being hunted.
     */
    private static Map<String, Object> sampleSeries(int n) {
        List<Double> closes = new ArrayList<>(), highs = new ArrayList<>(),
                     lows = new ArrayList<>(), opens = new ArrayList<>(),
                     vols = new ArrayList<>(), delivery = new ArrayList<>();
        double p = 100;
        for (int i = 0; i < n; i++) {
            p += Math.sin(i / 7.0) * 1.5 + 0.35;        // drifts up, oscillates
            opens.add(p - 0.4);
            closes.add(p);
            highs.add(p + 1.2);
            lows.add(p - 1.1);
            vols.add(1_000_000 + (i % 11) * 25_000.0);
            delivery.add(55.0);
        }
        vols.set(n - 1, 4_000_000.0);                   // today's spike
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("closes", closes); d.put("highs", highs); d.put("lows", lows);
        d.put("opens", opens);   d.put("volumes", vols); d.put("delivery_pct", delivery);
        d.put("base_price", 100.0);
        d.put("sector_score", 62.0);
        return d;
    }

    /** One condition: {@code field > sentinel}, used only to force the field to be computed. */
    private Map<String, Object> probe(String key, Map<String, Object> params) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("group", "entry");
        c.put("indicatorLeft", key);
        c.put("paramsLeft", params);
        c.put("comparator", ">");
        c.put("valueRight", -1e18);      // passes for any finite value
        c.put("multiplier", 1.0);
        return c;
    }

    private Map<String, Object> defaultParams(FieldCatalogue.FieldDef f) {
        Map<String, Object> p = new LinkedHashMap<>();
        for (FieldCatalogue.ParamDef d : f.params()) p.put(d.name(), d.defaultValue());
        return p;
    }

    @Test
    void everyAvailableCatalogueFieldIsEvaluable() {
        List<String> unevaluable = new ArrayList<>();
        List<String> checked = new ArrayList<>();

        for (FieldCatalogue.FieldDef f : catalogue.all()) {
            if (f.availability() != FieldCatalogue.Availability.AVAILABLE) continue;
            if (f.category() == FieldCatalogue.Category.FUNDAMENTAL) continue;
            checked.add(f.key());

            // "> -1e18" is true for any number the evaluator can actually produce, and false
            // only when it throws. A field that falls through to `default -> 0` still yields 0,
            // so this alone does not catch the drift - the sentinel test below does.
            boolean ok = evaluator.evaluate(List.of(probe(f.key(), defaultParams(f))),
                                            data, "entry");
            if (!ok) unevaluable.add(f.key());
        }

        assertTrue(checked.size() >= 30,
            "the catalogue returned only " + checked.size() + " available fields; "
          + "this test is not exercising what it claims to");
        if (!unevaluable.isEmpty()) {
            fail("These catalogue fields could not be evaluated at all: " + unevaluable);
        }
    }

    /**
     * The real check: a field that the evaluator does not know falls through to zero, and zero
     * is a plausible-looking number. Every field below is one whose value on this series cannot
     * legitimately be zero, so zero means the name was not recognised.
     */
    @Test
    void noCatalogueFieldSilentlyEvaluatesToZero() {
        List<String> suspicious = new ArrayList<>();

        for (FieldCatalogue.FieldDef f : catalogue.all()) {
            if (f.availability() != FieldCatalogue.Availability.AVAILABLE) continue;
            if (f.category() == FieldCatalogue.Category.FUNDAMENTAL) continue;
            // OBV and the oscillators can legitimately sit at or near zero on a given series,
            // so they are excluded from the zero check rather than from the contract.
            // WILLIAMS_R is defined on -100..0, so "above zero" is impossible for it by
            // construction rather than by drift. The rest can legitimately sit at or cross zero.
            if (List.of("OBV", "ROC", "gap_pct", "MACD", "MACD_SIGNAL", "MACD_HIST",
                        "pct_change", "relative_strength", "CCI",
                        "WILLIAMS_R", "MFI").contains(f.key())) continue;

            // "> 0.0000001" is false both for a genuine zero and for an unrecognised name. Every
            // remaining field is a price, a volume, a bounded oscillator above zero, or a ratio,
            // and none of those is zero on a rising series with a volume spike.
            Map<String, Object> c = probe(f.key(), defaultParams(f));
            c.put("valueRight", 0.0000001);
            if (!evaluator.evaluate(List.of(c), data, "entry")) suspicious.add(f.key());
        }

        if (!suspicious.isEmpty()) {
            fail("These catalogue fields evaluated to zero on a series where they cannot be "
               + "zero, which means the evaluator does not recognise the name the catalogue "
               + "publishes: " + suspicious);
        }
    }

    /**
     * The four conditions of the RSI 45 entry test, built exactly as the Strategy Builder emits
     * them, evaluated against a series constructed to satisfy all four.
     *
     * <p>This is the acceptance test for "can that strategy be built here". Two of the four were
     * impossible before: base_price was not published to the builder at all, and avg_volume was
     * published under a name the evaluator did not know.
     */
    @Test
    void theRsi45EntryTestEvaluatesAsWritten() {
        // A decline that turns up on the last bar. The shape matters: a flat series then a
        // jump leaves %K already above %D on the previous bar, so there is no crossing to
        // detect - the condition would be false for a reason that has nothing to do with the
        // code under test.
        //
        // Yesterday 91 (below the 95 base), today 99 (above it), volume five times its average,
        // and %K crossing %D as the stochastic turns off the low.
        List<Double> closes = new ArrayList<>(), highs = new ArrayList<>(),
                     lows = new ArrayList<>(), opens = new ArrayList<>(), vols = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            double p = i <= 57 ? 120 - i * 0.5 : (i == 58 ? 91.0 : 99.0);
            closes.add(p);
            opens.add(p - 0.3);
            highs.add(p + 1.0);
            lows.add(p - 1.0);
            vols.add(i == 59 ? 5_000_000.0 : 1_000_000.0);
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("closes", closes); d.put("highs", highs); d.put("lows", lows);
        d.put("opens", opens); d.put("volumes", vols);
        d.put("base_price", 95.0);           // 91 < 95 <= 99

        List<Map<String, Object>> conds = List.of(
            cmp("prev_close", "<",  "base_price", null),
            cmp("close",      ">=", "base_price", null),
            cmp("volume",     ">",  "avg_volume", Map.of("period", 20)),
            cmp("STOCH_K", "CROSSABOVE", "STOCH_D", Map.of("kPeriod", 14, "dPeriod", 3))
        );

        assertTrue(evaluator.evaluate(conds, d, "entry"),
            "the RSI 45 entry test did not pass on a series built to satisfy every one of its "
          + "four conditions");

        // And it must FAIL when the crossing is not there: today below the base price.
        closes.set(59, 93.0);
        assertTrue(!evaluator.evaluate(conds, d, "entry"),
            "the entry test passed with the current price below the base price");
    }

    /** A condition comparing two named fields. */
    private static Map<String, Object> cmp(String left, String comparator, String right,
                                           Map<String, Object> params) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("group", "entry");
        c.put("groupIndex", "1");
        c.put("groupMode", "ALL");
        c.put("groupJoin", "AND");
        c.put("indicatorLeft", left);
        c.put("paramsLeft", params == null ? Map.of() : params);
        c.put("comparator", comparator);
        c.put("indicatorRight", right);
        c.put("paramsRight", params == null ? Map.of() : params);
        c.put("multiplier", 1.0);
        c.put("valueRight", 0.0);
        return c;
    }

    /**
     * Average Volume excludes the current session, which is what "the average of the previous 20
     * completed sessions" means and what VOLUME_SMA does not do.
     *
     * <p>Worth pinning on its own: if avg_volume included today, a volume spike would be
     * competing against an average it is itself a twentieth of, so the test would be easier to
     * pass than the rule it is supposed to express - quietly, and in the direction of more
     * matches.
     */
    @Test
    void averageVolumeExcludesTheCurrentSession() {
        List<Double> vols = new ArrayList<>();
        for (int i = 0; i < 40; i++) vols.add(1_000.0);
        vols.set(39, 1_000_000.0);                       // today, enormous

        Map<String, Object> d = new LinkedHashMap<>(data);
        d.put("volumes", vols);

        // With today excluded the average is 1,000, so 1,000,000 > 1,000 holds comfortably.
        Map<String, Object> c = probe("avg_volume", Map.of("period", 20));
        c.put("valueRight", 1_500.0);
        assertTrue(!evaluator.evaluate(List.of(c), d, "entry"),
            "avg_volume came out above 1,500 on twenty sessions of 1,000 - today's spike is "
          + "being counted in its own baseline");

        c.put("valueRight", 900.0);
        assertTrue(evaluator.evaluate(List.of(c), d, "entry"),
            "avg_volume did not come out near 1,000 on twenty sessions of 1,000");
    }

    /**
     * Parameters reach the indicator.
     *
     * <p>MACD's three periods were hardcoded, so a 5/35/5 MACD was evaluated as 12/26/9 and read
     * as though the setting had applied.
     */
    @Test
    void indicatorParametersAreHonoured() {
        Map<String, Object> fast = probe("MACD", Map.of("fast", 5, "slow", 35, "signal", 5));
        Map<String, Object> std  = probe("MACD", Map.of("fast", 12, "slow", 26, "signal", 9));
        fast.put("valueRight", 0.0);
        std.put("valueRight", 0.0);

        // Not a value assertion - just that the two settings do not produce an identical verdict
        // on a series chosen to separate them.
        double a = macdOf(Map.of("fast", 5, "slow", 35, "signal", 5));
        double b = macdOf(Map.of("fast", 12, "slow", 26, "signal", 9));
        assertNotEquals(a, b, 1e-9,
            "MACD(5,35,5) and MACD(12,26,9) produced the same number - the parameters are "
          + "not reaching the indicator");
    }

    /** MACD read through the evaluator by bisecting on the comparison, since it returns boolean. */
    private double macdOf(Map<String, Object> params) {
        double lo = -50, hi = 50;
        for (int i = 0; i < 60; i++) {
            double mid = (lo + hi) / 2;
            Map<String, Object> c = probe("MACD", params);
            c.put("valueRight", mid);
            if (evaluator.evaluate(List.of(c), data, "entry")) lo = mid; else hi = mid;
        }
        return (lo + hi) / 2;
    }

    @Test
    void basePriceIsPublishedToTheBuilder() {
        FieldCatalogue.FieldDef f = catalogue.get("base_price");
        assertEquals(FieldCatalogue.Availability.AVAILABLE, f == null ? null : f.availability(),
            "base_price is not in the field catalogue, so the crossing every strategy on this "
          + "platform is built from cannot be expressed in the Strategy Builder");
    }
}
