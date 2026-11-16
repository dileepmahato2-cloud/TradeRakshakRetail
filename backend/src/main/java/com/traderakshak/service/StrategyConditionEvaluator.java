package com.traderakshak.service;

// =============================================================================
// StrategyConditionEvaluator
// Evaluates JSON condition trees (from all 3 builder levels) against OHLCV data
// Supports: AND/OR/NOT, nested groups, all 40+ indicators, multi-timeframe
// =============================================================================

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyConditionEvaluator {

    private final TechnicalIndicatorEngine indicator;

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

        boolean result = true;
        boolean firstInGroup = true;

        for (Map<String, Object> cond : group) {
            boolean condResult = evaluateSingleCondition(cond, data);
            String op = (String) cond.getOrDefault("operator", "AND");

            if (firstInGroup) {
                result = condResult;
                firstInGroup = false;
            } else if ("OR".equalsIgnoreCase(op)) {
                result = result || condResult;
            } else if ("NOT".equalsIgnoreCase(op)) {
                result = result && !condResult;
            } else { // AND (default)
                result = result && condResult;
            }
        }
        return result;
    }

    /**
     * Evaluate a single condition: left COMPARATOR right
     */
    @SuppressWarnings("unchecked")
    private boolean evaluateSingleCondition(Map<String, Object> cond, Map<String, Object> data) {
        try {
            String indLeft    = (String) cond.get("indicatorLeft");
            Map<?,?> paramsL  = cond.get("paramsLeft") instanceof Map ? (Map<?,?>) cond.get("paramsLeft") : Map.of();
            String comparator = (String) cond.getOrDefault("comparator", ">");
            String indRight   = (String) cond.get("indicatorRight");
            Map<?,?> paramsR  = cond.get("paramsRight") instanceof Map ? (Map<?,?>) cond.get("paramsRight") : Map.of();
            double mult       = getDouble(cond, "multiplier", 1.0);
            double valRight   = getDouble(cond, "valueRight", 0.0);

            double leftVal = computeIndicator(indLeft, paramsL, data);

            // Crossover/under patterns
            if ("CROSSABOVE".equalsIgnoreCase(comparator)) {
                List<Double> s1 = getSeriesFor(indLeft, paramsL, data, 2);
                List<Double> s2 = indRight != null
                    ? getSeriesFor(indRight, paramsR, data, 2)
                    : List.of(valRight, valRight);
                return indicator.crossAbove(s1, s2);
            }
            if ("CROSSBELOW".equalsIgnoreCase(comparator)) {
                List<Double> s1 = getSeriesFor(indLeft, paramsL, data, 2);
                List<Double> s2 = indRight != null
                    ? getSeriesFor(indRight, paramsR, data, 2)
                    : List.of(valRight, valRight);
                return indicator.crossBelow(s1, s2);
            }

            // Boolean/pattern indicators
            if ("IS_TRUE".equalsIgnoreCase(comparator) || indRight == null && valRight == 0 && isPattern(indLeft)) {
                return computePattern(indLeft, data);
            }

            double rightVal = indRight != null
                ? computeIndicator(indRight, paramsR, data) * mult
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
            case "MACD"           -> indicator.macd(closes, 12, 26, 9)[0];
            case "MACD_SIGNAL"    -> indicator.macd(closes, 12, 26, 9)[1];
            case "MACD_HIST"      -> indicator.macd(closes, 12, 26, 9)[2];
            case "BB_UPPER"       -> indicator.bollingerBands(closes, period, 2.0)[0];
            case "BB_MID"         -> indicator.bollingerBands(closes, period, 2.0)[1];
            case "BB_LOWER"       -> indicator.bollingerBands(closes, period, 2.0)[2];
            case "BB_WIDTH"       -> indicator.bollingerBandWidth(closes, period, 2.0);
            case "BB_PERCENT_B"   -> indicator.bollingerPercentB(closes, period, 2.0);
            case "STOCH_K"        -> indicator.stochastic(highs, lows, closes, period, 3)[0];
            case "STOCH_D"        -> indicator.stochastic(highs, lows, closes, period, 3)[1];
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
            case "SUPERTREND"     -> indicator.supertrend(highs, lows, closes, 7, 3.0)[0];
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
            default    -> left > right;
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

    @SuppressWarnings("unchecked")
    private List<Double> getSeriesFor(String ind, Map<?,?> params, Map<String, Object> data, int n) {
        List<Double> full = (List<Double>) data.get("closes");
        if (full == null || full.size() < n) return List.of(0.0, 0.0);
        // Return last 2 values of the indicator series for crossover detection
        List<Double> series = new ArrayList<>();
        int period = getInt(params, "period", 14);
        for (int i = full.size() - n; i < full.size(); i++) {
            List<Double> sub = full.subList(0, i + 1);
            series.add(switch (ind.toUpperCase()) {
                case "EMA"  -> indicator.ema(sub, period);
                case "SMA"  -> indicator.sma(sub, period);
                case "WMA"  -> indicator.wma(sub, period);
                case "RSI"  -> indicator.rsi(sub, period);
                case "MACD" -> indicator.macd(sub, 12, 26, 9)[0];
                case "MACD_SIGNAL" -> indicator.macd(sub, 12, 26, 9)[1];
                case "ROC"  -> indicator.roc(sub, period);
                case "CCI"  -> indicator.cci(sub, sub, sub, period);
                default     -> sub.get(sub.size()-1);
            });
        }
        return series;
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

    private int    getInt(Map<?,?> m, String k, int def)    { Object v = m.get(k); return v == null ? def : ((Number)v).intValue(); }
    private double getDouble(Map<?,?> m, String k, double def) { Object v = m.get(k); return v == null ? def : ((Number)v).doubleValue(); }
}
