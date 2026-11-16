package com.traderakshak.service;

import com.traderakshak.util.DateUtil;
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
public class BacktestEngine {

    private final AppConfigService appConfig;
    private final StrategyConditionEvaluator evaluator;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * Calendar days of history loaded before the window starts. Indicators need a run-up - a
     * 200-period average is meaningless on its first 199 bars - and 400 calendar days is about
     * 270 sessions, comfortably more than the 200 bars the evaluator works with.
     */
    private static final int WARMUP_CALENDAR_DAYS = 400;

    /** Bars a symbol must have before its conditions are trusted at all. */
    private static final int MIN_BARS_FOR_ENTRY   = 60;

    @PersistenceContext
    private EntityManager em;

    /**
     * One symbol's daily history, oldest first, held column-wise so a point-in-time view is a
     * sublist rather than a copy.
     */
    private static final class Series {
        final List<String> dates  = new ArrayList<>();
        final List<Double> opens  = new ArrayList<>();
        final List<Double> highs  = new ArrayList<>();
        final List<Double> lows   = new ArrayList<>();
        final List<Double> closes = new ArrayList<>();
        final List<Double> vols   = new ArrayList<>();
        final List<Double> deliv  = new ArrayList<>();
        double sectorScore = 50.0;
        double basePrice    = 0.0;

        /** The series as it stood after {@code n} bars, in the shape the evaluator expects. */
        Map<String, Object> sliceTo(int n) {
            Map<String, Object> d = new HashMap<>();
            d.put("opens",   opens.subList(0, n));   d.put("highs",  highs.subList(0, n));
            d.put("lows",    lows.subList(0, n));    d.put("closes", closes.subList(0, n));
            d.put("volumes", vols.subList(0, n));    d.put("delivery_pct", deliv.subList(0, n));
            d.put("sector_score", sectorScore);
            d.put("base_price",   basePrice);
            return d;
        }
    }

    /**
     * The strategy's entry conditions, in the shape StrategyConditionEvaluator reads.
     *
     * <p>Conditions live in one of two places. The normalised {@code strategy_conditions} table
     * is the intended home, but it is currently empty for every strategy - all three of them
     * keep their rules in {@code strategies.conditions_json}, already in the evaluator's own
     * shape. Both are read, table first, exactly as the screener does.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadEntryConditions(Long strategyId) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            List<Object[]> rows = em.createNativeQuery(
                "SELECT condition_group, operator, indicator_left, params_left, comparator, " +
                "indicator_right, params_right, value_right, multiplier, group_operator " +
                "FROM strategy_conditions WHERE strategy_id=:id ORDER BY condition_order")
                .setParameter("id", strategyId).getResultList();
            for (Object[] r : rows) {
                Map<String, Object> c = new HashMap<>();
                c.put("group",          r[0]); c.put("operator",       r[1]);
                c.put("indicatorLeft",  r[2]); c.put("paramsLeft",     parseParams(r[3]));
                c.put("comparator",     r[4]); c.put("indicatorRight", r[5]);
                c.put("paramsRight",    parseParams(r[6]));
                c.put("valueRight",     r[7] != null ? ((Number) r[7]).doubleValue() : 0);
                c.put("multiplier",     r[8] != null ? ((Number) r[8]).doubleValue() : 1.0);
                c.put("groupOperator",  r[9]);
                out.add(c);
            }
        } catch (Exception e) {
            log.warn("[Backtest] could not load conditions for strategy {}: {}",
                     strategyId, e.getMessage());
        }

        if (out.isEmpty()) {
            try {
                Object json = em.createNativeQuery(
                    "SELECT conditions_json FROM strategies WHERE id=:id")
                    .setParameter("id", strategyId).getSingleResult();
                if (json != null && !String.valueOf(json).isBlank()) {
                    out = objectMapper.readValue(String.valueOf(json), List.class);
                }
            } catch (Exception e) {
                log.warn("[Backtest] could not parse conditions_json for strategy {}: {}",
                         strategyId, e.getMessage());
            }
        }
        return normaliseEntryGroup(out);
    }

    /**
     * Make sure the entry rules are actually labelled "entry".
     *
     * <p>The evaluator selects conditions by group name, and not every strategy uses that word:
     * strategy 2 files its rules under "G1". Left alone, its backtest would find no entry
     * conditions and report a strategy with real rules as untestable. So when nothing is
     * labelled "entry", the FIRST group encountered is treated as the entry group - and only
     * that one, so a strategy that separately defines "exit" keeps them apart.
     */
    private List<Map<String, Object>> normaliseEntryGroup(List<Map<String, Object>> conditions) {
        if (conditions == null || conditions.isEmpty()) return List.of();
        boolean hasEntry = conditions.stream().anyMatch(c ->
            "entry".equals(c.get("group")) || "entry".equals(c.get("conditionGroup")));
        if (hasEntry) return conditions;

        String firstGroup = null;
        for (Map<String, Object> c : conditions) {
            Object g = c.get("group") != null ? c.get("group") : c.get("conditionGroup");
            if (g != null) { firstGroup = String.valueOf(g); break; }
        }
        if (firstGroup == null) {                    // no grouping at all - treat all as entry
            conditions.forEach(c -> c.put("group", "entry"));
            return conditions;
        }
        for (Map<String, Object> c : conditions) {
            Object g = c.get("group") != null ? c.get("group") : c.get("conditionGroup");
            if (g != null && firstGroup.equals(String.valueOf(g))) c.put("group", "entry");
        }
        log.info("[Backtest] strategy groups its entry rules as '{}' - treating that as entry.",
                 firstGroup);
        return conditions;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseParams(Object json) {
        if (json == null) return Map.of();
        try { return objectMapper.readValue(String.valueOf(json), Map.class); }
        catch (Exception e) { return Map.of(); }
    }

    /** Every symbol's daily bars for the window, in one query. */
    @SuppressWarnings("unchecked")
    private Map<String, Series> loadHistory(List<String> symbols, String from, String to) {
        Map<String, Series> out = new LinkedHashMap<>();
        if (symbols == null || symbols.isEmpty()) return out;
        int batch = 400;
        for (int start = 0; start < symbols.size(); start += batch) {
            List<String> chunk = symbols.subList(start, Math.min(start + batch, symbols.size()));
            try {
                List<Object[]> rows = em.createNativeQuery(
                    "SELECT symbol,trade_date,open,high,low,close,volume,delivery_pct " +
                    "FROM broker_market_candles " +
                    "WHERE exchange='NSE' AND UPPER(COALESCE(candle_mode,''))='DAILY' " +
                    "AND trade_date BETWEEN :from AND :to AND symbol IN (:syms) " +
                    "ORDER BY symbol, trade_date")
                    .setParameter("from", from).setParameter("to", to)
                    .setParameter("syms", chunk).getResultList();
                for (Object[] r : rows) {
                    Series s = out.computeIfAbsent(String.valueOf(r[0]), k -> new Series());
                    s.dates.add(String.valueOf(r[1]));
                    s.opens.add(num(r[2]));  s.highs.add(num(r[3]));
                    s.lows.add(num(r[4]));   s.closes.add(num(r[5]));
                    s.vols.add(num(r[6]));   s.deliv.add(num(r[7]));
                }
            } catch (Exception e) {
                log.warn("[Backtest] history load failed for {} symbols: {}",
                         chunk.size(), e.getMessage());
            }
        }
        return out;
    }

    private static double num(Object v) { return v == null ? 0 : ((Number) v).doubleValue(); }

    /**
     * The base price for each symbol, from whichever reference the strategy chose.
     *
     * <ul>
     *   <li>no date - the price uploaded with the stock, {@code tracked_stocks.base_price}. This
     *       is the master record for uploaded stocks and stays the default, so a strategy that
     *       never sets a reference behaves exactly as before.</li>
     *   <li>date only - that session's closing price. A holiday, a weekend or a symbol with no
     *       bar that day falls back to the last session on or before it, rather than dropping
     *       the symbol out of the test for a calendar accident.</li>
     *   <li>date and time - the last 5-minute candle at or before that moment. candle_time is
     *       stored as {@code yyyy-MM-ddTHH:mm:ss} text, so comparing strings orders it
     *       correctly.</li>
     * </ul>
     *
     * <p>A symbol with no resolvable base price is simply absent from the result. Its Series
     * keeps a base price of 0, the evaluator turns that into NaN, and any condition referencing
     * BASE_PRICE fails for it - which is the honest outcome, rather than comparing against zero
     * and letting everything through.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Double> resolveBasePrices(List<String> symbols, String date, String time) {
        Map<String, Double> out = new HashMap<>();
        if (symbols == null || symbols.isEmpty()) return out;

        boolean hasDate = date != null && !date.isBlank();
        boolean hasTime = hasDate && time != null && !time.isBlank();
        String stamp = hasTime ? date.trim() + "T" + (time.trim().length() == 5
                                                      ? time.trim() + ":00" : time.trim()) : null;

        int batch = 400;
        for (int start = 0; start < symbols.size(); start += batch) {
            List<String> chunk = symbols.subList(start, Math.min(start + batch, symbols.size()));
            try {
                List<Object[]> rows;
                if (!hasDate) {
                    rows = em.createNativeQuery(
                        "SELECT symbol, base_price FROM tracked_stocks " +
                        "WHERE base_price > 0 AND symbol IN (:syms)")
                        .setParameter("syms", chunk).getResultList();
                } else if (!hasTime) {
                    rows = em.createNativeQuery(
                        "SELECT symbol, close FROM (" +
                        "  SELECT symbol, close, ROW_NUMBER() OVER (" +
                        "    PARTITION BY symbol ORDER BY trade_date DESC) rn " +
                        "  FROM broker_market_candles " +
                        "  WHERE exchange='NSE' AND UPPER(COALESCE(candle_mode,''))='DAILY' " +
                        "    AND close > 0 AND trade_date <= :d AND symbol IN (:syms)) t " +
                        "WHERE rn = 1")
                        .setParameter("d", date.trim()).setParameter("syms", chunk).getResultList();
                } else {
                    rows = em.createNativeQuery(
                        "SELECT symbol, close FROM (" +
                        "  SELECT symbol, close, ROW_NUMBER() OVER (" +
                        "    PARTITION BY symbol ORDER BY candle_time DESC) rn " +
                        "  FROM broker_market_candles " +
                        "  WHERE UPPER(COALESCE(candle_mode,''))='INTRADAY' " +
                        "    AND close > 0 AND candle_time <= :ts AND symbol IN (:syms)) t " +
                        "WHERE rn = 1")
                        .setParameter("ts", stamp).setParameter("syms", chunk).getResultList();
                }
                for (Object[] r : rows) {
                    double px = num(r[1]);
                    if (px > 0) out.put(String.valueOf(r[0]), px);
                }
            } catch (Exception e) {
                log.warn("[Backtest] base price lookup failed for {} symbols: {}",
                         chunk.size(), e.getMessage());
            }
        }
        log.info("[Backtest] base price resolved for {}/{} symbols ({})",
                 out.size(), symbols.size(),
                 !hasDate ? "uploaded base price"
                          : hasTime ? "market price at " + stamp : "close of " + date);
        return out;
    }

    public Map<String, Object> run(Long strategyId, String fromDate, String toDate,
                                   double initialCapital, int maxPositions,
                                   double stopLossPct, double targetPct,
                                   double brokeragePct, String universe) {

        // Validate strategy
        List<?> stratList;
        try {
            stratList = em.createNativeQuery(
                "SELECT id, name, base_price_date, base_price_time " +
                "FROM strategies WHERE id=:id AND status='active'")
                .setParameter("id", strategyId).getResultList();
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "failed"); err.put("error", "Strategy not found: " + strategyId);
            return err;
        }
        if (stratList.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "failed"); err.put("error", "Strategy not found: " + strategyId);
            return err;
        }

        // Fetch symbols from universe
        List<String> symbols;
        try {
            symbols = em.createNativeQuery(
                "SELECT DISTINCT symbol FROM broker_market_candles WHERE exchange='NSE' " +
                "AND UPPER(COALESCE(candle_mode,''))='DAILY' " +
                "AND trade_date BETWEEN :from AND :to ORDER BY symbol LIMIT " + appConfig.getInt("backtest.symbol_limit", 500))
                .setParameter("from", fromDate).setParameter("to", toDate)
                .getResultList();
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "failed"); err.put("error", "No market data found for period: " + fromDate + " to " + toDate);
            return err;
        }

        if (symbols.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "failed"); err.put("error", "No symbols in universe: " + universe);
            return err;
        }

        // Fetch all trading dates in range.
        //
        // This carried TWO WHERE clauses - "...='DAILY' AND true WHERE trade_date BETWEEN..." -
        // which Postgres rejects outright. The catch below swallowed the syntax error into an
        // empty list and the next block turned that into "No trading data available for
        // selected period", so every backtest this engine ever ran failed here, on valid data.
        List<String> tradingDates;
        try {
            tradingDates = em.createNativeQuery(
                "SELECT DISTINCT trade_date FROM broker_market_candles " +
                "WHERE UPPER(COALESCE(candle_mode,''))='DAILY' " +
                "AND trade_date BETWEEN :from AND :to ORDER BY trade_date")
                .setParameter("from", fromDate).setParameter("to", toDate)
                .getResultList();
        } catch (Exception e) {
            log.warn("[Backtest] trading-date query failed: {}", e.getMessage());
            tradingDates = new ArrayList<>();
        }

        if (tradingDates.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "failed"); err.put("error", "No trading data available for selected period");
            return err;
        }

        // ── The strategy's own entry rules ───────────────────────────────────
        //
        // There was no entry signal here at all. A comment claimed an "RSI < 40 placeholder",
        // but the code below it simply bought symbols in list order until maxPositions was full
        // - and StrategyConditionEvaluator, which the screener and the ranking service both
        // depend on, was never called from this class. Every backtest therefore measured the
        // same buy-everything portfolio no matter which strategy had been selected.
        List<Map<String, Object>> entryConditions = loadEntryConditions(strategyId);
        if (entryConditions.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "failed");
            err.put("error", "Strategy " + strategyId + " has no entry conditions, so there is "
                           + "nothing to test. Add at least one entry condition and re-run.");
            return err;
        }

        // ── Price history, loaded once ───────────────────────────────────────
        //
        // The old loop ran one SELECT per symbol per day, in two places - roughly 125,000 round
        // trips for 500 symbols across a year. Everything is loaded up front instead, starting
        // a warm-up period before the window because indicators need bars from before the first
        // simulated day. Each day is then evaluated against the slice of the series ending that
        // day, which is also what keeps the test honest: a condition can only ever see bars the
        // strategy could have seen at the time.
        String warmupFrom = java.time.LocalDate.parse(fromDate)
                                .minusDays(WARMUP_CALENDAR_DAYS).toString();
        Map<String, Series> history = loadHistory(symbols, warmupFrom, toDate);

        // ── The base price each symbol is measured against ───────────────────
        //
        // Null date means "whatever was uploaded with the stock", which is how every strategy
        // behaved before a reference point could be chosen. A date (optionally with a time)
        // reads the price out of the market instead, so the same rules can be tested against
        // any level without re-uploading anything.
        Object[] strat = (Object[]) stratList.get(0);
        String basePriceDate = strat.length > 2 && strat[2] != null ? String.valueOf(strat[2]) : null;
        String basePriceTime = strat.length > 3 && strat[3] != null ? String.valueOf(strat[3]) : null;
        Map<String, Double> basePrices = resolveBasePrices(symbols, basePriceDate, basePriceTime);
        basePrices.forEach((sym, px) -> {
            Series s = history.get(sym);
            if (s != null) s.basePrice = px;
        });
        if (history.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "failed");
            err.put("error", "No daily bars could be loaded for the selected universe.");
            return err;
        }

        double capital = initialCapital;
        Map<String, Double> openPositions = new LinkedHashMap<>();
        Map<String, String>  openDates    = new LinkedHashMap<>();
        List<Map<String, Object>> trades  = new ArrayList<>();
        Map<String, Map<String, Object>> equityCurve = new LinkedHashMap<>();
        int maxPos = maxPositions > 0 ? maxPositions : 10;
        int maxHold = appConfig.getInt("backtest.max_holding_days", 20);

        // How many bars of each symbol are visible on the day being simulated. Days run in
        // ascending order, so each cursor only ever moves forward.
        Map<String, Integer> visible = new HashMap<>();
        for (String sym : history.keySet()) visible.put(sym, 0);

        for (String day : tradingDates) {
            for (Map.Entry<String, Series> e : history.entrySet()) {
                Series s = e.getValue();
                int n = visible.get(e.getKey());
                while (n < s.dates.size() && s.dates.get(n).compareTo(day) <= 0) n++;
                visible.put(e.getKey(), n);
            }
            // Exit check — stop loss / target
            Set<String> toExit = new LinkedHashSet<>();
            for (Map.Entry<String, Double> pos : openPositions.entrySet()) {
                String sym = pos.getKey();
                Double entryPrice = pos.getValue();
                try {
                    Series s = history.get(sym);
                    int idx = visible.getOrDefault(sym, 0) - 1;
                    if (s == null || idx < 0 || !s.dates.get(idx).equals(day)) continue;

                    // Stop and target are judged on the day's RANGE. Comparing them against the
                    // close only, as this did, misses every level that was breached during the
                    // session and recovered by the bell - which flatters the strategy, because
                    // the stop is the level that gets missed most.
                    double high = s.highs.get(idx), low = s.lows.get(idx);
                    double close = s.closes.get(idx);
                    double stopAt   = stopLossPct > 0 ? entryPrice * (1 - stopLossPct / 100) : -1;
                    double targetAt = targetPct   > 0 ? entryPrice * (1 + targetPct   / 100) : -1;
                    int holdDays    = (int) DateUtil.daysBetween(openDates.get(sym), day);

                    boolean shouldExit = true;
                    double exitPrice;
                    if (stopAt > 0 && low <= stopAt)          exitPrice = stopAt;   // stop wins ties
                    else if (targetAt > 0 && high >= targetAt) exitPrice = targetAt;
                    else if (holdDays >= maxHold)              exitPrice = close;
                    else { shouldExit = false; exitPrice = close; }

                    double grossPnl  = exitPrice - entryPrice;
                    double brok      = Math.abs(grossPnl * brokeragePct / 100);
                    double netPnl    = grossPnl - brok;

                    if (shouldExit) {
                        Map<String, Object> trade = new LinkedHashMap<>();
                        trade.put("symbol",     sym);
                        trade.put("entryDate",  openDates.get(sym));
                        trade.put("exitDate",   day);
                        trade.put("entryPrice", entryPrice);
                        trade.put("exitPrice",  exitPrice);
                        trade.put("grossPnl",   round2(grossPnl));
                        trade.put("netPnl",     round2(netPnl));
                        trade.put("returnPct",  round2((exitPrice - entryPrice) / entryPrice * 100));
                        trade.put("holdingDays",holdDays);
                        trade.put("result",     netPnl > 0 ? "Win" : netPnl < -0.01 ? "Loss" : "Scratch");
                        trades.add(trade);
                        capital += netPnl;
                        toExit.add(sym);
                    }
                } catch (Exception e) { /* no data for this day */ }
            }
            toExit.forEach(s -> { openPositions.remove(s); openDates.remove(s); });

            // Entry — the strategy's own conditions, judged on bars up to and including today.
            if (openPositions.size() < maxPos) {
                for (String sym : symbols) {
                    if (openPositions.containsKey(sym)) continue;
                    if (openPositions.size() >= maxPos) break;
                    try {
                        Series s = history.get(sym);
                        int n = visible.getOrDefault(sym, 0);
                        // MIN_BARS of warm-up, and a bar for today - a condition evaluated on a
                        // stale series would be answering about a different day.
                        if (s == null || n < MIN_BARS_FOR_ENTRY) continue;
                        if (!s.dates.get(n - 1).equals(day)) continue;

                        if (!evaluator.evaluate(entryConditions, s.sliceTo(n), "entry")) continue;

                        double price = s.closes.get(n - 1);
                        if (price > 0 && capital >= price) {
                            openPositions.put(sym, price);
                            openDates.put(sym, day);
                        }
                    } catch (Exception e) { /* skip */ }
                }
            }

            // Record equity curve
            // Mark open positions to the day's close. This used to add pos.getValue() - the
            // ENTRY price - so the equity curve ignored every unrealised gain and loss and only
            // moved when a trade closed.
            double totalValue = capital;
            for (Map.Entry<String, Double> pos : openPositions.entrySet()) {
                Series s = history.get(pos.getKey());
                int idx = visible.getOrDefault(pos.getKey(), 0) - 1;
                totalValue += (s != null && idx >= 0) ? s.closes.get(idx) : pos.getValue();
            }
            Map<String, Object> eq = new LinkedHashMap<>();
            eq.put("equity", round2(totalValue));
            eq.put("date",   day);
            equityCurve.put(day, eq);
        }

        // Close remaining positions at last price
        for (Map.Entry<String, Double> pos : openPositions.entrySet()) {
            String sym = pos.getKey(); Double entryPrice = pos.getValue();
            try {
                Object closeObj = em.createNativeQuery(
                    "SELECT close FROM broker_market_candles WHERE symbol=:sym " + "AND UPPER(COALESCE(candle_mode,''))='DAILY' " + "ORDER BY trade_date DESC LIMIT 1")
                    .setParameter("sym", sym).getSingleResult();
                if (closeObj != null) {
                    double exitPrice = ((Number) closeObj).doubleValue();
                    double netPnl = exitPrice - entryPrice;
                    Map<String, Object> trade = new LinkedHashMap<>();
                    trade.put("symbol", sym); trade.put("entryDate", openDates.get(sym));
                    trade.put("exitDate", toDate); trade.put("entryPrice", entryPrice);
                    trade.put("exitPrice", exitPrice); trade.put("netPnl", round2(netPnl));
                    trade.put("result", netPnl > 0 ? "Win" : "Loss");
                    trades.add(trade);
                    capital += netPnl;
                }
            } catch (Exception e) { /* skip */ }
        }

        // Compute metrics
        Map<String, Object> metrics = computeMetrics(trades, initialCapital, capital, equityCurve);

        // Save backtest record
        try {
            em.createNativeQuery(
                "INSERT INTO backtests (strategy_id,user_id,institution_id,from_date,to_date," +
                "initial_capital,final_capital,universe,total_trades,win_rate,net_profit_pct," +
                "max_drawdown,sharpe_ratio,status,created_at) VALUES (?,1,1,?,?,?,?,?,?,?,?,?,?,?,?)")
                .setParameter(1, strategyId).setParameter(2, fromDate).setParameter(3, toDate)
                .setParameter(4, initialCapital).setParameter(5, round2(capital))
                .setParameter(6, universe).setParameter(7, trades.size())
                .setParameter(8, metrics.get("winRate")).setParameter(9, metrics.get("netProfitPct"))
                .setParameter(10, metrics.get("maxDrawdown")).setParameter(11, metrics.get("sharpeRatio"))
                .setParameter(12, "completed").setParameter(13, DateUtil.now())
                .executeUpdate();
        } catch (Exception e) {
            log.debug("Backtest save error: {}", e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategy",       ((Object[]) stratList.get(0))[1]);
        result.put("fromDate",       fromDate);
        result.put("toDate",         toDate);
        result.put("initialCapital", initialCapital);
        result.put("finalCapital",   round2(capital));
        result.put("universe",       universe);
        result.put("totalTrades",    trades.size());
        result.put("metrics",        metrics);
        result.put("trades",         trades.subList(0, Math.min(trades.size(), 100)));
        result.put("equityCurve",    equityCurve);

        // Say out loud where this engine cannot yet honour a condition, rather than returning a
        // confident zero. sector_score is fixed at 50 for every symbol and every day - it is not
        // loaded per symbol and there is no point-in-time history for it - so a rule like
        // "SECTOR_SCORE >= 65" can never pass and the strategy silently books no trades at all.
        List<String> warnings = new ArrayList<>();
        boolean usesSector = entryConditions.stream().anyMatch(c ->
            String.valueOf(c.get("indicatorLeft")).toUpperCase(Locale.ROOT).contains("SECTOR")
         || String.valueOf(c.get("indicatorRight")).toUpperCase(Locale.ROOT).contains("SECTOR"));
        if (usesSector) {
            warnings.add("This strategy tests SECTOR_SCORE, which the backtest cannot supply "
                       + "historically - it is held at 50 for every symbol and date. Any result "
                       + "involving that condition is not meaningful.");
        }
        if (trades.isEmpty()) {
            warnings.add("No entry condition was met on any day in the range. Widen the dates, "
                       + "or loosen the entry rules.");
        }
        result.put("warnings", warnings);
        return result;
    }

    private Map<String, Object> computeMetrics(List<Map<String, Object>> trades,
                                                double initialCapital, double finalCapital,
                                                Map<String, Map<String, Object>> equityCurve) {
        int wins = 0, losses = 0;
        double totalPnl = 0, totalWin = 0, totalLoss = 0;
        for (Map<String, Object> t : trades) {
            double pnl = t.get("netPnl") instanceof Number ? ((Number) t.get("netPnl")).doubleValue() : 0;
            totalPnl += pnl;
            if (pnl > 0) { wins++; totalWin += pnl; } else if (pnl < 0) { losses++; totalLoss += Math.abs(pnl); }
        }

        int total = trades.size();
        double winRate     = total > 0 ? round2((double) wins / total * 100) : 0;
        double netProfitPct= initialCapital > 0 ? round2((finalCapital - initialCapital) / initialCapital * 100) : 0;
        double avgWin      = wins   > 0 ? round2(totalWin   / wins)   : 0;
        double avgLoss     = losses > 0 ? round2(totalLoss  / losses) : 0;
        double rr          = avgLoss > 0 ? round2(avgWin / avgLoss) : 0;
        double expectancy  = total > 0 ? round2(totalPnl / total) : 0;

        // Max drawdown
        double peak = initialCapital, maxDD = 0;
        for (Map<String, Object> eq : equityCurve.values()) {
            double val = ((Number) eq.get("equity")).doubleValue();
            if (val > peak) peak = val;
            double dd = peak > 0 ? (peak - val) / peak * 100 : 0;
            if (dd > maxDD) maxDD = dd;
        }

        // Sharpe ratio (simplified)
        double sharpe = computeSharpe(equityCurve, Math.max(1, equityCurve.size()) / 252.0);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalTrades",   total);
        m.put("wins",          wins);
        m.put("losses",        losses);
        m.put("winRate",       winRate);
        m.put("netProfitPct",  netProfitPct);
        m.put("avgWin",        avgWin);
        m.put("avgLoss",       avgLoss);
        m.put("rewardRisk",    rr);
        m.put("expectancy",    expectancy);
        m.put("maxDrawdown",   round2(maxDD));
        m.put("sharpeRatio",   round2(sharpe));
        return m;
    }

    public Map<String, Object> monteCarlo(Long strategyId, int simulations) {
        List<?> backtestReturns;
        try {
            backtestReturns = em.createNativeQuery(
                "SELECT (net_pnl / entry_price * 100) FROM paper_trades " +
                "WHERE strategy_id=:sid AND status='closed' ORDER BY created_at DESC LIMIT 200")
                .setParameter("sid", strategyId).getResultList();
        } catch (Exception e) {
            backtestReturns = new ArrayList<>();
        }

        if (backtestReturns.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "No trade returns for Monte Carlo");
            return err;
        }

        List<Double> returns = backtestReturns.stream()
            .map(r -> r instanceof Number ? ((Number) r).doubleValue() : 0.0)
            .collect(Collectors.toList());

        int n = simulations > 0 ? simulations : 1000;
        Random rnd = new Random(42);
        List<Double> finalCapitals = new ArrayList<>();
        int posCount = 0;

        for (int i = 0; i < n; i++) {
            double cap = 100000.0;
            for (int j = 0; j < 50; j++) {
                double ret = returns.get(rnd.nextInt(returns.size()));
                cap *= (1 + ret / 100);
            }
            finalCapitals.add(cap);
            if (cap > 100000) posCount++;
        }
        Collections.sort(finalCapitals);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("simulations",  n);
        result.put("percentile5",  round2(finalCapitals.get((int)(n * 0.05))));
        result.put("percentile25", round2(finalCapitals.get((int)(n * 0.25))));
        result.put("percentile50", round2(finalCapitals.get((int)(n * 0.50))));
        result.put("percentile75", round2(finalCapitals.get((int)(n * 0.75))));
        result.put("percentile95", round2(finalCapitals.get((int)(n * 0.95))));
        result.put("probPositive", round2((double) posCount / n * 100));
        result.put("worstCase",    round2(finalCapitals.get(0)));
        result.put("bestCase",     round2(finalCapitals.get(n - 1)));
        return result;
    }

    private double computeSharpe(Map<String, Map<String, Object>> equityCurve, double years) {
        if (equityCurve.size() < 2) return 0;
        List<Double> dailyReturns = new ArrayList<>();
        List<Double> equities = equityCurve.values().stream()
            .map(e -> ((Number) e.get("equity")).doubleValue()).collect(Collectors.toList());
        for (int i = 1; i < equities.size(); i++) {
            double prev = equities.get(i - 1);
            if (prev > 0) dailyReturns.add((equities.get(i) - prev) / prev);
        }
        if (dailyReturns.isEmpty()) return 0;
        double mean = dailyReturns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = dailyReturns.stream().mapToDouble(r -> Math.pow(r - mean, 2)).average().orElse(0);
        double stdDev = Math.sqrt(variance);
        return stdDev > 0 ? round2(mean / stdDev * Math.sqrt(252)) : 0;
    }

    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
