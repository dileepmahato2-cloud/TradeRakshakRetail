package com.traderakshak.service;

// =============================================================================
// StrategyConditionEvaluator
// Evaluates JSON condition trees (from all 3 builder levels) against OHLCV data
// Supports: AND/OR/NOT, numbered condition groups, every field the catalogue publishes,
// and daily/weekly/monthly timeframes (weekly and monthly derived from the daily series).
// =============================================================================

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import com.traderakshak.service.strategy.ast.FieldCatalogue;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class StrategyConditionEvaluator {

    private final TechnicalIndicatorEngine indicator;

    /**
     * What the platform can measure at all, as opposed to what it can measure for one stock.
     *
     * <p>The distinction decides whether a condition fails a stock or is skipped entirely, and
     * they are not the same thing. A base price missing for ONE symbol is a fact about that
     * symbol: it should not be identified by a crossing rule, so it fails. A market cap missing
     * for EVERY symbol is a fact about the data pipeline: failing every stock on it would turn
     * a working strategy into one that silently returns nothing, and passing every stock would
     * silently drop the filter. Neither is honest, so the condition is skipped and said so.
     *
     * <p>Nullable, so the evaluator can still be constructed in a unit test without one; with no
     * catalogue nothing is treated as platform-wide missing, which is the old behaviour.
     */
    private FieldCatalogue catalogue;

    public StrategyConditionEvaluator(TechnicalIndicatorEngine indicator) {
        this.indicator = indicator;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setFieldCatalogue(FieldCatalogue catalogue) { this.catalogue = catalogue; }

    /** TRUE, FALSE, or "this cannot be measured at all, so it is not counted either way". */
    public enum Verdict { TRUE, FALSE, SKIP }

    /**
     * Every comparator this engine can honour.
     *
     * <p>It exists because {@code compare()} ended in {@code default -> left > right}: a
     * comparator the switch did not recognise silently became greater-than. The builder's
     * operator list offers BETWEEN, which had no implementation anywhere in the engine, so
     * "Close BETWEEN 45" evaluated as "Close > 45" and returned a perfectly plausible list of
     * stocks for a rule nobody wrote.
     *
     * <p>A wrong operator is worse than a missing one: a missing one is visible, while this
     * produced results that looked right. So an unsupported comparator now skips the condition
     * and says so, through the same mechanism that reports a field with no data.
     */
    private static final java.util.Set<String> SUPPORTED_COMPARATORS = java.util.Set.of(
        ">", ">=", "<", "<=", "=", "!=",
        "CROSSABOVE", "CROSSBELOW", "BETWEEN", "IS_TRUE");

    /** Conditions skipped on the last evaluate() call, as readable sentences. */
    private final ThreadLocal<List<String>> skipped = ThreadLocal.withInitial(ArrayList::new);

    /**
     * Why each condition was skipped in the most recent evaluation on this thread.
     *
     * <p>Read by the screener so the result can say "market cap was not applied" rather than
     * leaving a filter the user wrote silently absent from what they are looking at. A skipped
     * filter that nobody is told about is the same as a filter that was never written.
     */
    public List<String> lastSkipped() { return List.copyOf(skipped.get()); }

    @PersistenceContext
    private EntityManager em;

    /**
     * Evaluate a strategy's conditions for a single symbol, against its LATEST data.
     *
     * <p>Suitable for screening "what qualifies right now". Not suitable for a backtest: the
     * data is always the most recent 200 bars regardless of which day is being simulated, so
     * every historical day would be judged on today's indicators. Backtests must use
     * {@link #evaluate(List, Map, String)} with a point-in-time slice instead.
     */
    public boolean evaluate(List<Map<String, Object>> conditions, String symbol,
                            String exchange, String conditionGroup) {
        return evaluate(conditions, loadSymbolData(symbol, exchange, 200), conditionGroup);
    }

    /**
     * Evaluate a strategy's conditions against a supplied price series.
     *
     * <p>Same rules as the symbol form, but the caller owns the data. That is what makes a
     * backtest possible: it can hand in the series truncated at the day being simulated, so an
     * indicator can only ever see bars that existed at the time, and it can reuse one loaded
     * history across every day instead of re-querying per symbol per day.
     *
     * @param data the map {@link #loadSymbolData} produces - opens/highs/lows/closes/volumes as
     *             oldest-first {@code List<Double>}, plus delivery_pct and sector_score.
     */
    @SuppressWarnings("unchecked")
    public boolean evaluate(List<Map<String, Object>> conditions, Map<String, Object> data,
                            String conditionGroup) {
        if (conditions == null || conditions.isEmpty()) return false;
        if (data == null) return false;

        List<Map<String, Object>> group = conditions.stream()
            .filter(c -> conditionGroup.equals(c.get("group")) ||
                         conditionGroup.equals(c.get("conditionGroup")))
            .collect(Collectors.toList());

        if (group.isEmpty()) return false;

        skipped.get().clear();
        return evaluateGrouped(group, data);
    }

    /**
     * Fold the conditions, honouring condition groups.
     *
     * <p>The builder lets conditions be put into numbered groups - "all of these must hold" in
     * one, "any one of these" in another - and the groups are then joined. That structure has to
     * survive into evaluation or it is decoration: a flat left-to-right fold reads
     * {@code a AND b OR c} as {@code (a AND b) OR c} by accident of row order rather than because
     * anyone said so, and the same conditions reordered would mean something different.
     *
     * <p>So: each group is folded with its OWN mode - ALL means every condition in it must hold,
     * ANY means at least one must - and the group results are then folded left to right with
     * each group's connector to the next. Conditions that carry no group number all land in one
     * implicit group, which is exactly the old behaviour, so every strategy saved before groups
     * existed evaluates the same way it always did.
     *
     * <p>Within a group the per-condition {@code operator} is still read when the group has no
     * declared mode, because that is how pre-group strategies expressed OR.
     */
    private boolean evaluateGrouped(List<Map<String, Object>> conditions, Map<String, Object> data) {
        // Preserve the order groups first appear in; a LinkedHashMap keyed on the group number
        // keeps the connector between group 1 and group 2 meaningful.
        LinkedHashMap<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> c : conditions) {
            groups.computeIfAbsent(groupIndexOf(c), k -> new ArrayList<>()).add(c);
        }

        Boolean combined = null;
        String pendingJoin = "AND";

        for (Map.Entry<String, List<Map<String, Object>>> e : groups.entrySet()) {
            Verdict v = foldOneGroup(e.getValue(), data);
            // A group with nothing measurable in it is not an answer, so it does not join.
            if (v == Verdict.SKIP) continue;
            boolean groupResult = v == Verdict.TRUE;
            if (combined == null) {
                combined = groupResult;
            } else if ("OR".equalsIgnoreCase(pendingJoin)) {
                combined = combined || groupResult;
            } else {
                combined = combined && groupResult;
            }
            // The connector TO THE NEXT group is carried on the last condition of this one, which
            // is where the builder writes it.
            pendingJoin = joinAfterOf(e.getValue());
        }
        // Every group skipped: the strategy asked nothing this platform can answer. Identifying
        // every stock would be the literal reading and a disastrous one - a screen with no
        // measurable filter should return nothing and say why, not the whole exchange.
        return combined != null && combined;
    }

    /** One group's own verdict, from its declared mode or, failing that, its row operators. */
    /**
     * One group's verdict. SKIP when every condition in it was skipped.
     *
     * <p>Skipped conditions are dropped before folding, so a filter that cannot be measured
     * neither blocks an ALL group nor satisfies an ANY one - it is simply not part of the
     * question. A group with nothing left to ask has no opinion, and saying so is what stops it
     * being read as "everything qualifies".
     */
    private Verdict foldOneGroup(List<Map<String, Object>> group, Map<String, Object> data) {
        List<Map<String, Object>> live = new ArrayList<>(group.size());
        for (Map<String, Object> c : group) {
            if (verdictOf(c, data) != Verdict.SKIP) live.add(c);
        }
        if (live.isEmpty()) return Verdict.SKIP;

        String mode = modeOf(group);
        if ("ANY".equalsIgnoreCase(mode)) {
            for (Map<String, Object> c : live) {
                if (evaluateSingleCondition(c, data)) return Verdict.TRUE;
            }
            return Verdict.FALSE;
        }
        if ("ALL".equalsIgnoreCase(mode)) {
            for (Map<String, Object> c : live) {
                if (!evaluateSingleCondition(c, data)) return Verdict.FALSE;
            }
            return Verdict.TRUE;
        }

        // No declared mode: the original left-to-right fold over the row operators, unchanged.
        boolean result = true;
        boolean first = true;
        for (Map<String, Object> cond : live) {
            boolean condResult = evaluateSingleCondition(cond, data);
            String op = (String) cond.getOrDefault("operator", "AND");
            if (first) {
                result = condResult;
                first = false;
            } else if ("OR".equalsIgnoreCase(op)) {
                result = result || condResult;
            } else if ("NOT".equalsIgnoreCase(op)) {
                result = result && !condResult;
            } else {
                result = result && condResult;
            }
        }
        return result ? Verdict.TRUE : Verdict.FALSE;
    }

    /**
     * Which numbered group a condition belongs to.
     *
     * <p>{@code ""} for anything that names none, so every pre-group strategy keeps folding as a
     * single group and nothing about it changes.
     */
    private static String groupIndexOf(Map<String, Object> c) {
        for (String key : new String[]{"groupIndex", "group_index", "groupNo", "conditionGroupIndex"}) {
            Object v = c.get(key);
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v).trim();
        }
        return "";
    }

    /** A group's mode: ALL, ANY, or null when it declares none. */
    private static String modeOf(List<Map<String, Object>> group) {
        for (Map<String, Object> c : group) {
            for (String key : new String[]{"groupMode", "group_mode"}) {
                Object v = c.get(key);
                if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v).trim();
            }
        }
        return null;
    }

    /** The connector from this group to the next one, taken from its last condition. */
    private static String joinAfterOf(List<Map<String, Object>> group) {
        for (int i = group.size() - 1; i >= 0; i--) {
            for (String key : new String[]{"groupJoin", "group_join", "groupOperator"}) {
                Object v = group.get(i).get(key);
                if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v).trim();
            }
        }
        return "AND";
    }

    /**
     * Evaluate a single condition: left COMPARATOR right
     */
    private boolean evaluateSingleCondition(Map<String, Object> cond, Map<String, Object> data) {
        return verdictOf(cond, data) == Verdict.TRUE;
    }

    /**
     * One condition, as TRUE / FALSE / SKIP.
     *
     * <p>SKIP only when a side names something the platform has no data for at all. Everything
     * else - a stock with too little history, a missing base price, an indicator that cannot be
     * computed on this series - is FALSE, because those are facts about the stock and the stock
     * should not be identified on them.
     */
    private Verdict verdictOf(Map<String, Object> cond, Map<String, Object> data) {
        String unmeasurable = platformMissing((String) cond.get("indicatorLeft"));
        if (unmeasurable == null) {
            unmeasurable = platformMissing((String) cond.get("indicatorRight"));
        }
        if (unmeasurable != null) {
            String note = unmeasurable + " has no data loaded, so \""
                + describeCondition(cond) + "\" was skipped.";
            List<String> log = skipped.get();
            if (!log.contains(note)) log.add(note);
            return Verdict.SKIP;
        }

        // An operator the engine cannot honour is skipped and reported, never quietly
        // reinterpreted. Pattern conditions carry no comparator and are exempt.
        String comparator = (String) cond.get("comparator");
        String op = comparator == null ? "" : comparator.trim().toUpperCase();
        boolean pattern = isPattern((String) cond.get("indicatorLeft"));
        if (!op.isEmpty() && !pattern && !SUPPORTED_COMPARATORS.contains(op)) {
            String note = "\"" + describeCondition(cond) + "\" uses the operator "
                + comparator + ", which this screener does not support, so it was skipped.";
            List<String> log = skipped.get();
            if (!log.contains(note)) log.add(note);
            return Verdict.SKIP;
        }

        return rawEvaluate(cond, data) ? Verdict.TRUE : Verdict.FALSE;
    }

    /** The field's label when the platform has no data for it at all, otherwise null. */
    private String platformMissing(String key) {
        if (key == null || key.isBlank() || catalogue == null) return null;
        FieldCatalogue.FieldDef def = catalogue.get(key);
        return def != null && def.availability() == FieldCatalogue.Availability.NO_DATA
            ? def.label() : null;
    }

    private static String describeCondition(Map<String, Object> c) {
        Object right = c.get("indicatorRight") != null ? c.get("indicatorRight")
                                                       : c.get("valueRight");
        return c.get("indicatorLeft") + " " + c.getOrDefault("comparator", ">") + " " + right;
    }

    @SuppressWarnings("unchecked")
    private boolean rawEvaluate(Map<String, Object> cond, Map<String, Object> data) {
        try {
            // The bar width this condition is measured on. The file header claimed
            // "multi-timeframe" and the field was read by nothing: every condition, however it
            // was labelled, was evaluated on daily bars. A weekly RSI silently became a daily
            // one - the same number of points, a plausible value, and the wrong answer.
            data = onTimeframe(data, (String) cond.get("timeframe"));

            String indLeft    = (String) cond.get("indicatorLeft");
            Map<?,?> paramsL  = cond.get("paramsLeft") instanceof Map ? (Map<?,?>) cond.get("paramsLeft") : Map.of();
            String comparator = (String) cond.getOrDefault("comparator", ">");
            String indRight   = (String) cond.get("indicatorRight");
            Map<?,?> paramsR  = cond.get("paramsRight") instanceof Map ? (Map<?,?>) cond.get("paramsRight") : Map.of();
            double mult       = getDouble(cond, "multiplier", 1.0);
            double valRight   = getDouble(cond, "valueRight", 0.0);

            // "N bars ago", per side.
            //
            // A rule can name the previous session's value directly - "previous session %K was
            // at or below %D" - rather than only through the crossing operators. Both forms
            // should exist: the crossing operator says what is meant in one row, and the
            // explicit pair is what someone translating a screen written elsewhere will reach
            // for. Without offsets the second form could not be written at all, and silently
            // read as the CURRENT session on both sides, which is always false for a
            // "crossed above" pair and always true for a "stayed above" one.
            int offL = getInt(cond, "offsetLeft", 0);
            int offR = getInt(cond, "offsetRight", 0);
            Map<String, Object> dataL = offL > 0 ? shiftBack(data, offL) : data;
            Map<String, Object> dataR = offR > 0 ? shiftBack(data, offR) : data;

            double leftVal = computeIndicator(indLeft, paramsL, dataL);

            // Crossover/under patterns
            if ("CROSSABOVE".equalsIgnoreCase(comparator)) {
                List<Double> s1 = getSeriesFor(indLeft, paramsL, dataL, 2);
                List<Double> s2 = indRight != null
                    ? getSeriesFor(indRight, paramsR, dataR, 2)
                    : List.of(valRight, valRight);
                return indicator.crossAbove(s1, s2);
            }
            if ("CROSSBELOW".equalsIgnoreCase(comparator)) {
                List<Double> s1 = getSeriesFor(indLeft, paramsL, dataL, 2);
                List<Double> s2 = indRight != null
                    ? getSeriesFor(indRight, paramsR, dataR, 2)
                    : List.of(valRight, valRight);
                return indicator.crossBelow(s1, s2);
            }

            // BETWEEN: inclusive on both bounds, which is how every screener this is modelled
            // on reads it. The upper bound is its own field; without one the condition cannot be
            // judged, and verdictOf has already skipped it rather than guess a range.
            if ("BETWEEN".equalsIgnoreCase(comparator)) {
                double lower = valRight * mult;
                double upper = getDouble(cond, "valueRightUpper", Double.NaN) * mult;
                if (Double.isNaN(upper)) return false;
                if (lower > upper) { double t = lower; lower = upper; upper = t; }
                return leftVal >= lower && leftVal <= upper;
            }

            // Boolean/pattern indicators
            if ("IS_TRUE".equalsIgnoreCase(comparator) || indRight == null && valRight == 0 && isPattern(indLeft)) {
                return computePattern(indLeft, dataL);
            }

            double rightVal = indRight != null
                ? computeIndicator(indRight, paramsR, dataR) * mult
                : valRight * mult;

            return compare(leftVal, comparator, rightVal);

        } catch (Exception e) {
            log.debug("Condition eval error: {}", e.getMessage());
            return false;
        }
    }

    /** Compute indicator value from the symbol's data map */
    @SuppressWarnings("unchecked")
    private double computeIndicator(String ind, Map<?,?> params, Map<String, Object> data) {
        List<Double> closes  = (List<Double>) data.get("closes");
        List<Double> highs   = (List<Double>) data.get("highs");
        List<Double> lows    = (List<Double>) data.get("lows");
        List<Double> opens   = (List<Double>) data.get("opens");
        List<Double> volumes = (List<Double>) data.get("volumes");
        List<Double> delivery= (List<Double>) data.get("delivery_pct");

        int period = getInt(params, "period", 14);

        return switch (ind.toUpperCase()) {
            case "RSI"            -> indicator.rsi(closes, period);
            case "EMA"            -> indicator.ema(closes, period);
            case "SMA"            -> indicator.sma(closes, period);
            case "WMA"            -> indicator.wma(closes, period);
            case "CLOSE"          -> closes != null && !closes.isEmpty() ? closes.get(closes.size()-1) : 0;
            case "OPEN"           -> opens  != null && !opens.isEmpty()  ? opens.get(opens.size()-1)   : 0;
            case "HIGH"           -> highs  != null && !highs.isEmpty()  ? highs.get(highs.size()-1)   : 0;
            case "LOW"            -> lows   != null && !lows.isEmpty()   ? lows.get(lows.size()-1)     : 0;
            case "VOLUME"         -> volumes!= null && !volumes.isEmpty()? volumes.get(volumes.size()-1): 0;
            case "VOLUME_SMA"     -> indicator.sma(volumes, period);
            case "VOLUME_EMA"     -> indicator.ema(volumes, period);
            case "VWAP"           -> indicator.vwap(highs, lows, closes, volumes);
            case "ATR"            -> indicator.atr(highs, lows, closes, period);
            case "ADX"            -> indicator.adx(highs, lows, closes, period);
            case "PLUS_DI"        -> indicator.dmi(highs, lows, closes, period)[0];
            case "MINUS_DI"       -> indicator.dmi(highs, lows, closes, period)[1];
            // The three MACD periods were hardcoded, so a condition that asked for a 5/35/5
            // MACD was evaluated as 12/26/9 and read as though it had worked.
            case "MACD"           -> indicator.macd(closes, macdFast(params), macdSlow(params), macdSig(params))[0];
            case "MACD_SIGNAL"    -> indicator.macd(closes, macdFast(params), macdSlow(params), macdSig(params))[1];
            case "MACD_HIST"      -> indicator.macd(closes, macdFast(params), macdSlow(params), macdSig(params))[2];
            case "BB_UPPER"       -> indicator.bollingerBands(closes, bbPeriod(params), bbMult(params))[0];
            // BB_MIDDLE is the catalogue's name and BB_MID was the only one here, so a
            // condition on the Bollinger middle band evaluated to 0.
            case "BB_MID", "BB_MIDDLE" -> indicator.bollingerBands(closes, bbPeriod(params), bbMult(params))[1];
            case "BB_LOWER"       -> indicator.bollingerBands(closes, bbPeriod(params), bbMult(params))[2];
            case "BB_WIDTH"       -> indicator.bollingerBandWidth(closes, bbPeriod(params), bbMult(params));
            case "BB_PERCENT_B"   -> indicator.bollingerPercentB(closes, bbPeriod(params), bbMult(params));
            // kPeriod/dPeriod are the names the field catalogue publishes for these, so they
            // are the names that arrive. Reading "period" meant a %K built on a period nobody
            // asked for the moment anyone changed it from the default.
            case "STOCH_K"        -> indicator.stochastic(highs, lows, closes,
                                        getInt(params, "kPeriod", period),
                                        getInt(params, "smoothK", 3),
                                        getInt(params, "dPeriod", 3))[0];
            case "STOCH_D"        -> indicator.stochastic(highs, lows, closes,
                                        getInt(params, "kPeriod", period),
                                        getInt(params, "smoothK", 3),
                                        getInt(params, "dPeriod", 3))[1];
            case "STOCH_RSI_K"    -> indicator.stochasticRsi(closes, period, 14)[0];
            case "ROC"            -> indicator.roc(closes, period);
            case "MOMENTUM"       -> indicator.momentum(closes, period);
            case "WILLIAMS_R"     -> indicator.williamsR(highs, lows, closes, period);
            case "CCI"            -> indicator.cci(highs, lows, closes, period);
            case "MFI"            -> indicator.mfi(highs, lows, closes, volumes, period);
            case "OBV"            -> indicator.obv(closes, volumes);
            case "KELTNER_UPPER"  -> indicator.keltnerChannel(highs, lows, closes, 20, period, 1.5)[0];
            case "KELTNER_MID"    -> indicator.keltnerChannel(highs, lows, closes, 20, period, 1.5)[1];
            case "KELTNER_LOWER"  -> indicator.keltnerChannel(highs, lows, closes, 20, period, 1.5)[2];
            case "DONCHIAN_HIGH"  -> indicator.donchianChannel(highs, lows, period)[0];
            case "DONCHIAN_MID"   -> indicator.donchianChannel(highs, lows, period)[1];
            case "DONCHIAN_LOW"   -> indicator.donchianChannel(highs, lows, period)[2];
            case "SUPERTREND"     -> indicator.supertrend(highs, lows, closes,
                                        getInt(params, "period", 10),
                                        getDouble(params, "multiplier", 3.0))[0];
            case "PARABOLIC_SAR"  -> indicator.parabolicSar(highs, lows, closes, 0.02, 0.02, 0.2)[0];
            case "ICHIMOKU_TENKAN"-> indicator.ichimoku(highs, lows, closes, 9, 26, 52)[0];
            case "ICHIMOKU_KIJUN" -> indicator.ichimoku(highs, lows, closes, 9, 26, 52)[1];
            case "ICHIMOKU_SENKOU_A" -> indicator.ichimoku(highs, lows, closes, 9, 26, 52)[2];
            case "ICHIMOKU_SENKOU_B" -> indicator.ichimoku(highs, lows, closes, 9, 26, 52)[3];
            case "PIVOT"          -> {
                double pH = highs.size()>=2 ? highs.get(highs.size()-2) : 0;
                double pL = lows.size()>=2 ? lows.get(lows.size()-2) : 0;
                double pC = closes.size()>=2 ? closes.get(closes.size()-2) : 0;
                yield indicator.pivotPoints(pH, pL, pC)[0];
            }
            case "R1" -> { double pH=highs.size()>=2?highs.get(highs.size()-2):0; double pL=lows.size()>=2?lows.get(lows.size()-2):0; double pC=closes.size()>=2?closes.get(closes.size()-2):0; yield indicator.pivotPoints(pH,pL,pC)[1]; }
            case "R2" -> { double pH=highs.size()>=2?highs.get(highs.size()-2):0; double pL=lows.size()>=2?lows.get(lows.size()-2):0; double pC=closes.size()>=2?closes.get(closes.size()-2):0; yield indicator.pivotPoints(pH,pL,pC)[2]; }
            case "S1" -> { double pH=highs.size()>=2?highs.get(highs.size()-2):0; double pL=lows.size()>=2?lows.get(lows.size()-2):0; double pC=closes.size()>=2?closes.get(closes.size()-2):0; yield indicator.pivotPoints(pH,pL,pC)[4]; }
            case "S2" -> { double pH=highs.size()>=2?highs.get(highs.size()-2):0; double pL=lows.size()>=2?lows.get(lows.size()-2):0; double pC=closes.size()>=2?closes.get(closes.size()-2):0; yield indicator.pivotPoints(pH,pL,pC)[5]; }
            case "CPR_PIVOT"      -> { double pH=highs.size()>=2?highs.get(highs.size()-2):0; double pL=lows.size()>=2?lows.get(lows.size()-2):0; double pC=closes.size()>=2?closes.get(closes.size()-2):0; yield indicator.cpr(pH,pL,pC)[0]; }
            case "CPR_TC"         -> { double pH=highs.size()>=2?highs.get(highs.size()-2):0; double pL=lows.size()>=2?lows.get(lows.size()-2):0; double pC=closes.size()>=2?closes.get(closes.size()-2):0; yield indicator.cpr(pH,pL,pC)[1]; }
            case "CPR_BC"         -> { double pH=highs.size()>=2?highs.get(highs.size()-2):0; double pL=lows.size()>=2?lows.get(lows.size()-2):0; double pC=closes.size()>=2?closes.get(closes.size()-2):0; yield indicator.cpr(pH,pL,pC)[2]; }
            case "VOLUME_CHANGE"  -> indicator.volumeChange(volumes);
            case "VOLUME_SPIKE_RATIO" -> indicator.volumeSpikeRatio(volumes, period);
            case "GAP_PCT"        -> indicator.gapPercentage(opens, closes);
            case "DELIVERY_PCT"   -> delivery != null && !delivery.isEmpty() ? delivery.get(delivery.size()-1) : 0;
            case "PREV_CLOSE"     -> closes != null && closes.size() >= 2 ? closes.get(closes.size()-2) : 0;
            case "PREV_HIGH"      -> highs  != null && highs.size()  >= 2 ? highs.get(highs.size()-2)   : 0;
            case "PREV_LOW"       -> lows   != null && lows.size()   >= 2 ? lows.get(lows.size()-2)     : 0;
            case "CHANGE_PCT"     -> {
                double c = closes != null && !closes.isEmpty() ? closes.get(closes.size()-1) : 0;
                double p = closes != null && closes.size() >= 2 ? closes.get(closes.size()-2) : c;
                yield p > 0 ? (c - p) / p * 100 : 0;
            }
            // The level the crossover rules are written against. It is supplied by the
            // caller rather than computed, because it is not derived from the price series at
            // all - it is either the price uploaded with the stock or the price at a reference
            // date and time the strategy chose. 0 means the caller could not supply one, and a
            // comparison against 0 would silently pass, so conditions using it are made to fail
            // instead.
            case "BASE_PRICE" -> {
                Object bp = data.get("base_price");
                double v = bp == null ? 0 : ((Number) bp).doubleValue();
                yield v > 0 ? v : Double.NaN;
            }
            // ---- names the field catalogue publishes -------------------------------
            //
            // These five were in the catalogue, offered by the builder, accepted by the
            // validator, and had no case here - so they fell through to `default -> 0`. A
            // condition reading "Volume > Average Volume" became "Volume > 0", which every
            // stock on the exchange passes. That is the worst shape a bug can take on this
            // screen: it validates, it screens, it returns a long list, and nothing about the
            // result says it is wrong.

            // The average of the PREVIOUS n completed sessions, with the current one excluded.
            // Not VOLUME_SMA, which includes today: an average a figure is a twentieth of is not
            // a baseline that figure can meaningfully beat.
            case "AVG_VOLUME" -> {
                if (volumes == null || volumes.size() < 2) yield 0;
                yield indicator.sma(volumes.subList(0, volumes.size() - 1), period);
            }
            // Today's volume over that same baseline, so the ratio and the average agree.
            case "VOLUME_RATIO" -> {
                if (volumes == null || volumes.size() < 2) yield 0;
                double base = indicator.sma(volumes.subList(0, volumes.size() - 1), period);
                yield base > 0 ? volumes.get(volumes.size() - 1) / base : 0;
            }
            case "PCT_CHANGE" -> {
                if (closes == null || closes.size() < period + 1) yield 0;
                double now = closes.get(closes.size() - 1);
                double then = closes.get(closes.size() - 1 - period);
                yield then > 0 ? (now - then) / then * 100 : 0;
            }
            case "RELATIVE_STRENGTH" -> {
                // The stock's own return over the window against the sector score standing in
                // for the index. No index price series is stored, which is the same limitation
                // the market-breadth engine states rather than papering over.
                if (closes == null || closes.size() < period + 1) yield 0;
                double now = closes.get(closes.size() - 1);
                double then = closes.get(closes.size() - 1 - period);
                double own = then > 0 ? (now - then) / then * 100 : 0;
                double bench = getDouble(data, "sector_score", 50.0) - 50.0;
                yield round2(own - bench);
            }
            case "SECTOR_SCORE"   -> getDouble(data, "sector_score", 50.0);
            case "DELIVERY_RATIO" -> {
                double dv = delivery != null && !delivery.isEmpty() ? delivery.get(delivery.size()-1) : 0;
                yield dv / 60.0;
            }
            default -> 0;
        };
    }

    private boolean compare(double left, String op, double right) {
        return switch (op.trim()) {
            case ">"   -> left > right;
            case ">="  -> left >= right;
            case "<"   -> left < right;
            case "<="  -> left <= right;
            case "="   -> Math.abs(left - right) < 0.001;
            case "!="  -> Math.abs(left - right) >= 0.001;
            // Unreachable: verdictOf skips an unsupported comparator before evaluation. Kept as
            // false rather than ">" so that if a path ever reaches here it fails closed instead
            // of inventing a greater-than the author never wrote.
            default    -> false;
        };
    }

    @SuppressWarnings("unchecked")
    private boolean computePattern(String pattern, Map<String, Object> data) {
        List<Double> opens  = (List<Double>) data.get("opens");
        List<Double> highs  = (List<Double>) data.get("highs");
        List<Double> lows   = (List<Double>) data.get("lows");
        List<Double> closes = (List<Double>) data.get("closes");
        List<Double> volumes = (List<Double>) data.get("volumes");
        if (opens == null || opens.isEmpty()) return false;
        int n = opens.size();
        double o  = opens.get(n-1),  h  = highs.get(n-1),  l  = lows.get(n-1),  c  = closes.get(n-1);
        double po = n>1?opens.get(n-2):o, pc = n>1?closes.get(n-2):c;
        return switch (pattern.toUpperCase()) {
            case "HAMMER"            -> indicator.isHammer(o, h, l, c);
            case "DOJI"              -> indicator.isDoji(o, h, l, c);
            case "BULLISH_ENGULFING" -> indicator.isBullishEngulfing(po, pc, o, c);
            case "BEARISH_ENGULFING" -> indicator.isBearishEngulfing(po, pc, o, c);
            case "MARUBOZU"          -> indicator.isMarubozu(o, h, l, c);
            case "INVERTED_HAMMER"   -> indicator.isInvertedHammer(o, h, l, c);
            case "SHOOTING_STAR"     -> indicator.isShootingStar(o, h, l, c);
            case "SPINNING_TOP"      -> indicator.isSpinningTop(o, h, l, c);
            case "MORNING_STAR"      -> indicator.isMorningStar(opens, highs, lows, closes);
            case "EVENING_STAR"      -> indicator.isEveningStar(opens, highs, lows, closes);
            case "THREE_WHITE_SOLDIERS" -> indicator.isThreeWhiteSoldiers(opens, closes);
            case "THREE_BLACK_CROWS"    -> indicator.isThreeBlackCrows(opens, closes);
            case "BREAKOUT"          -> indicator.isBreakout(closes, highs, 20);
            case "BREAKDOWN"         -> indicator.isBreakdown(closes, lows, 20);
            case "HIGHER_HIGH"       -> indicator.isHigherHigh(highs);
            case "HIGHER_LOW"        -> indicator.isHigherLow(lows);
            case "LOWER_HIGH"        -> indicator.isLowerHigh(highs);
            case "LOWER_LOW"         -> indicator.isLowerLow(lows);
            case "SUPERTREND_BULL"   -> indicator.supertrendBullish(highs, lows, closes, 7, 3.0);
            case "VOLUME_SPIKE"      -> indicator.isVolumeSpikeDetected(volumes, 20, 2.0);
            default                  -> false;
        };
    }

    private boolean isPattern(String ind) {
        Set<String> patterns = Set.of("HAMMER","DOJI","BULLISH_ENGULFING","BEARISH_ENGULFING",
            "MARUBOZU","INVERTED_HAMMER","SHOOTING_STAR","SPINNING_TOP",
            "MORNING_STAR","EVENING_STAR","THREE_WHITE_SOLDIERS","THREE_BLACK_CROWS",
            "BREAKOUT","BREAKDOWN","HIGHER_HIGH","HIGHER_LOW","LOWER_HIGH","LOWER_LOW",
            "SUPERTREND_BULL","VOLUME_SPIKE");
        return patterns.contains(ind.toUpperCase());
    }

    /**
     * The last {@code n} values of an indicator, for the crossing operators.
     *
     * <p>Built by truncating the whole price series to each bar in turn and running the ordinary
     * {@link #computeIndicator} over it. That is deliberately the same code path a non-crossing
     * comparison takes, because the previous version was a second, shorter switch - EMA, SMA,
     * WMA, RSI, MACD, MACD_SIGNAL, ROC, CCI - ending in {@code default -> the raw close}.
     *
     * <p>Anything outside that list therefore produced the close price twice and
     * {@code crossAbove(close, close)} is never true, so "Stochastic %K crosses above %D" could
     * not fire at all. It did not error and it did not log: the condition was simply always
     * false, which on a screener is indistinguishable from nothing qualifying. The same went for
     * ADX, Supertrend, the Bollinger bands, VWAP, OBV and every other crossable indicator.
     *
     * <p>Two switches over the same vocabulary is the bug. One switch cannot drift from itself.
     */
    @SuppressWarnings("unchecked")
    private List<Double> getSeriesFor(String ind, Map<?,?> params, Map<String, Object> data, int n) {
        List<Double> full = (List<Double>) data.get("closes");
        if (full == null || full.size() < n) return List.of(0.0, 0.0);

        List<Double> series = new ArrayList<>(n);
        for (int i = full.size() - n; i < full.size(); i++) {
            // bars 0..i inclusive: the series exactly as it stood at that bar's close, so an
            // indicator can never see a bar that had not happened yet.
            series.add(computeIndicator(ind, params, truncate(data, i + 1)));
        }
        return series;
    }

    /**
     * The series as it stood {@code bars} bars ago.
     *
     * <p>Truncation, not a window: everything after that bar is removed, so an indicator reads
     * exactly what it would have read on that session and cannot see a bar that had not
     * happened yet. That is the same guarantee the crossing operators rely on.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> shiftBack(Map<String, Object> data, int bars) {
        List<Double> closes = (List<Double>) data.get("closes");
        if (closes == null || bars <= 0 || closes.size() <= bars) return data;
        return truncate(data, closes.size() - bars);
    }

    /**
     * The same data map cut to its first {@code len} bars.
     *
     * <p>Views, not copies - {@code subList} is a window onto the same backing list - so walking
     * two bars for a crossing costs two map allocations rather than two copies of a 200-bar
     * history per symbol.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> truncate(Map<String, Object> data, int len) {
        Map<String, Object> out = new HashMap<>(data);
        for (String key : new String[]{"closes", "highs", "lows", "opens", "volumes", "delivery_pct"}) {
            Object v = data.get(key);
            if (v instanceof List<?> list && list.size() >= len) {
                out.put(key, ((List<Double>) list).subList(0, len));
            }
        }
        return out;
    }

    /**
     * The same history, resampled to a wider bar.
     *
     * <p>Weekly and monthly candles are not stored - only daily and hourly are - so they are
     * built from the daily series, which is exactly what the timeframe catalogue reports when it
     * publishes them as {@code derived_from: DAILY}. Deriving them is not a workaround: a weekly
     * bar IS five daily bars aggregated, and storing a second copy would be two sources that can
     * disagree.
     *
     * <p>Anything daily or narrower is returned untouched. An hourly condition cannot be served
     * from daily bars in any honest way - the information is not there to recover - so it is
     * left on the daily series rather than quietly answered with the wrong width; the builder
     * marks the intraday widths by their own availability.
     */
    private Map<String, Object> onTimeframe(Map<String, Object> data, String timeframe) {
        if (timeframe == null) return data;
        String tf = timeframe.trim().toUpperCase();
        int span = switch (tf) {
            case "WEEKLY"  -> 5;      // trading days, not calendar days
            case "MONTHLY" -> 21;
            default        -> 0;
        };
        return span == 0 ? data : resample(data, span);
    }

    /**
     * Aggregate the series into buckets of {@code span} bars, newest bucket last.
     *
     * <p>Bucketed from the END backwards, so the most recent bucket always ends on the latest
     * session. Bucketing forwards would leave the newest bar in a partial bucket at an arbitrary
     * offset, which moves every indicator value as history grows by one day.
     *
     * <p>Open is the bucket's first open, close its last close, high the maximum, low the
     * minimum, volume the sum. A trailing partial bucket is kept - a week in progress is a real
     * week, and dropping it would make the last value of every weekly indicator a week old.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> resample(Map<String, Object> data, int span) {
        List<Double> closes = (List<Double>) data.get("closes");
        if (closes == null || closes.size() <= span) return data;

        List<Double> highs = (List<Double>) data.get("highs");
        List<Double> lows  = (List<Double>) data.get("lows");
        List<Double> opens = (List<Double>) data.get("opens");
        List<Double> vols  = (List<Double>) data.get("volumes");

        int n = closes.size();
        List<Double> rc = new ArrayList<>(), rh = new ArrayList<>(),
                     rl = new ArrayList<>(), ro = new ArrayList<>(), rv = new ArrayList<>();

        // Walk the bucket boundaries from the oldest edge so the newest bucket ends at n.
        int first = n % span;
        if (first > 0) {
            addBucket(rc, rh, rl, ro, rv, closes, highs, lows, opens, vols, 0, first);
        }
        for (int start = first; start < n; start += span) {
            addBucket(rc, rh, rl, ro, rv, closes, highs, lows, opens, vols, start,
                      Math.min(start + span, n));
        }

        Map<String, Object> out = new HashMap<>(data);
        out.put("closes", rc);
        if (highs != null) out.put("highs", rh);
        if (lows  != null) out.put("lows",  rl);
        if (opens != null) out.put("opens", ro);
        if (vols  != null) out.put("volumes", rv);
        return out;
    }

    private static void addBucket(List<Double> rc, List<Double> rh, List<Double> rl,
                                  List<Double> ro, List<Double> rv,
                                  List<Double> closes, List<Double> highs, List<Double> lows,
                                  List<Double> opens, List<Double> vols, int from, int to) {
        if (to <= from) return;
        rc.add(closes.get(to - 1));
        if (opens != null && opens.size() >= to) ro.add(opens.get(from));
        if (highs != null && highs.size() >= to) {
            double h = highs.get(from);
            for (int i = from + 1; i < to; i++) h = Math.max(h, highs.get(i));
            rh.add(h);
        }
        if (lows != null && lows.size() >= to) {
            double l = lows.get(from);
            for (int i = from + 1; i < to; i++) l = Math.min(l, lows.get(i));
            rl.add(l);
        }
        if (vols != null && vols.size() >= to) {
            double v = 0;
            for (int i = from; i < to; i++) v += vols.get(i);
            rv.add(v);
        }
    }

    /** Load OHLCV + indicators for a symbol from the DB */
    @SuppressWarnings("unchecked")
    public Map<String, Object> loadSymbolData(String symbol, String exchange, int bars) {
        try {
            List<Object[]> rows = em.createNativeQuery(
                "SELECT open, high, low, close, volume, delivery_pct " +
                "FROM broker_market_candles WHERE exchange=:ex AND symbol=:sym " +
                "AND UPPER(COALESCE(candle_mode,''))='DAILY' " +
                "ORDER BY trade_date DESC LIMIT :n")
                .setParameter("ex", exchange).setParameter("sym", symbol)
                .setParameter("n", bars).getResultList();

            if (rows.isEmpty()) return null;
            Collections.reverse(rows);

            List<Double> opens  = new ArrayList<>(), highs = new ArrayList<>(),
                         lows   = new ArrayList<>(), closes= new ArrayList<>(),
                         volumes= new ArrayList<>(), deliv = new ArrayList<>();
            for (Object[] r : rows) {
                opens.add(r[0] != null ? ((Number)r[0]).doubleValue() : 0);
                highs.add(r[1] != null ? ((Number)r[1]).doubleValue() : 0);
                lows.add (r[2] != null ? ((Number)r[2]).doubleValue() : 0);
                closes.add(r[3]!= null ? ((Number)r[3]).doubleValue() : 0);
                volumes.add(r[4]!=null ? ((Number)r[4]).doubleValue() : 0);
                deliv.add(r[5] != null ? ((Number)r[5]).doubleValue() : 0);
            }

            // Sector score from sector_data
            double sectorScore = 50.0;
            try {
                Object ss = em.createNativeQuery(
                    "SELECT sd.trend_score FROM sector_data sd JOIN stock_master sm " +
                    "ON sm.sector=sd.sector WHERE sm.symbol=:sym ORDER BY sd.trade_date DESC LIMIT 1")
                    .setParameter("sym", symbol).getSingleResult();
                if (ss != null) sectorScore = ((Number)ss).doubleValue();
            } catch (Exception ignored) {}

            // The uploaded base price, so a BASE_PRICE condition means something on the
            // screening path too. A backtest overrides this with whatever reference point the
            // strategy chose; here there is only ever the level the stock was uploaded with.
            double basePrice = 0.0;
            try {
                Object bp = em.createNativeQuery(
                    "SELECT base_price FROM tracked_stocks WHERE symbol=:sym " +
                    "AND base_price > 0 LIMIT 1")
                    .setParameter("sym", symbol).getSingleResult();
                if (bp != null) basePrice = ((Number) bp).doubleValue();
            } catch (Exception ignored) { }

            Map<String, Object> data = new HashMap<>();
            data.put("base_price", basePrice);
            data.put("opens",   opens);   data.put("highs",   highs);
            data.put("lows",    lows);    data.put("closes",  closes);
            data.put("volumes", volumes); data.put("delivery_pct", deliv);
            data.put("sector_score", sectorScore);
            return data;
        } catch (Exception e) {
            log.debug("loadSymbolData error {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    private static int macdFast(Map<?,?> p) { return intOf(p, "fast", 12); }
    private static int macdSlow(Map<?,?> p) { return intOf(p, "slow", 26); }
    private static int macdSig(Map<?,?> p)  { return intOf(p, "signal", 9); }
    private static int bbPeriod(Map<?,?> p) { return intOf(p, "period", 20); }
    private static double bbMult(Map<?,?> p) { return dblOf(p, "stdMult", 2.0); }

    private static int intOf(Map<?,?> m, String k, int def) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Number n ? n.intValue() : def;
    }
    private static double dblOf(Map<?,?> m, String k, double def) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Number n ? n.doubleValue() : def;
    }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private int    getInt(Map<?,?> m, String k, int def)    {
        Object v = m == null ? null : m.get(k);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String str && !str.isBlank()) {
            try { return (int) Double.parseDouble(str.trim()); } catch (NumberFormatException e) { return def; }
        }
        return def;
    }
    private double getDouble(Map<?,?> m, String k, double def) { Object v = m.get(k); return v == null ? def : ((Number)v).doubleValue(); }
}
