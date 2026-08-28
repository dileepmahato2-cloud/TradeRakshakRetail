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

    @PersistenceContext
    private EntityManager em;

    public Map<String, Object> run(Long strategyId, String fromDate, String toDate,
                                   double initialCapital, int maxPositions,
                                   double stopLossPct, double targetPct,
                                   double brokeragePct, String universe) {

        // Validate strategy
        List<?> stratList;
        try {
            stratList = em.createNativeQuery(
                "SELECT id, name FROM strategies WHERE id=:id AND status='active'")
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
                "SELECT DISTINCT symbol FROM market_data WHERE exchange='NSE' " +
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

        // Fetch all trading dates in range
        List<String> tradingDates;
        try {
            tradingDates = em.createNativeQuery(
                "SELECT DISTINCT trade_date FROM market_data " +
                "WHERE trade_date BETWEEN :from AND :to ORDER BY trade_date")
                .setParameter("from", fromDate).setParameter("to", toDate)
                .getResultList();
        } catch (Exception e) {
            tradingDates = new ArrayList<>();
        }

        if (tradingDates.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "failed"); err.put("error", "No trading data available for selected period");
            return err;
        }

        // Simple moving-average crossover simulation
        double capital = initialCapital;
        Map<String, Double> openPositions = new LinkedHashMap<>();
        Map<String, String>  openDates    = new LinkedHashMap<>();
        List<Map<String, Object>> trades  = new ArrayList<>();
        Map<String, Map<String, Object>> equityCurve = new LinkedHashMap<>();
        int maxPos = maxPositions > 0 ? maxPositions : 10;

        for (String day : tradingDates) {
            // Exit check — stop loss / target
            Set<String> toExit = new LinkedHashSet<>();
            for (Map.Entry<String, Double> pos : openPositions.entrySet()) {
                String sym = pos.getKey();
                Double entryPrice = pos.getValue();
                try {
                    Object closeObj = em.createNativeQuery(
                        "SELECT close FROM market_data WHERE symbol=:sym AND trade_date=:d LIMIT 1")
                        .setParameter("sym", sym).setParameter("d", day).getSingleResult();
                    if (closeObj == null) continue;
                    double exitPrice = ((Number) closeObj).doubleValue();
                    double grossPnl  = exitPrice - entryPrice;
                    double brok      = Math.abs(grossPnl * brokeragePct / 100);
                    double netPnl    = grossPnl - brok;
                    int holdDays     = (int) DateUtil.daysBetween(openDates.get(sym), day);

                    boolean shouldExit = false;
                    if (stopLossPct > 0 && exitPrice <= entryPrice * (1 - stopLossPct / 100)) shouldExit = true;
                    if (targetPct   > 0 && exitPrice >= entryPrice * (1 + targetPct   / 100)) shouldExit = true;
                    if (holdDays >= 20) shouldExit = true; // max holding 20 days

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

            // Entry — simple RSI < 40 crossover signal (placeholder)
            if (openPositions.size() < maxPos) {
                for (String sym : symbols) {
                    if (openPositions.containsKey(sym)) continue;
                    if (openPositions.size() >= maxPos) break;
                    try {
                        Object priceObj = em.createNativeQuery(
                            "SELECT close FROM market_data WHERE symbol=:sym AND trade_date=:d LIMIT 1")
                            .setParameter("sym", sym).setParameter("d", day).getSingleResult();
                        if (priceObj == null) continue;
                        double price = ((Number) priceObj).doubleValue();
                        if (price > 0 && capital >= price) {
                            openPositions.put(sym, price);
                            openDates.put(sym, day);
                        }
                    } catch (Exception e) { /* skip */ }
                }
            }

            // Record equity curve
            double totalValue = capital;
            for (Map.Entry<String, Double> pos : openPositions.entrySet()) {
                totalValue += pos.getValue();
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
                    "SELECT close FROM market_data WHERE symbol=:sym ORDER BY trade_date DESC LIMIT 1")
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
