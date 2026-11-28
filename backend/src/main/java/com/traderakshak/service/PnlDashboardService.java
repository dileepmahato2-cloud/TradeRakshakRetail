package com.traderakshak.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Profit and loss across open and closed positions, filterable by date, symbol, strategy and mode.
 *
 * <p>Built on {@code paper_trades}, which already holds every simulated position, rather than on a
 * new table. What it adds is the figures the existing {@code /trading/dashboard} never computed:
 * gross against net, maximum drawdown, the capital curve, and the strategy-wise and stock-wise
 * splits.
 *
 * <p><b>Gross and net are reported separately throughout</b>, because the difference is the whole
 * question for a short-hold strategy. A one-session round trip costs about 0.30% of the position,
 * so a basket averaging 0.25% gross is a loss-maker that a gross-only dashboard shows as
 * profitable. Where a closed trade has no stored charge figure - every trade predating the charges
 * engine - it is counted at gross and the response says how many such trades there are, rather
 * than quietly treating "no charge recorded" as "no charge incurred".
 */
@Service
public class PnlDashboardService {

    private static final Logger log = LoggerFactory.getLogger(PnlDashboardService.class);

    @PersistenceContext
    private EntityManager em;

    private final StockTrackerService tracker;

    public PnlDashboardService(StockTrackerService tracker) {
        this.tracker = tracker;
    }

    /**
     * @param from,to  trade dates, inclusive. Null means unbounded.
     * @param symbol   one stock, or null for all.
     * @param strategy one strategy key, or null for all.
     * @param mode     PAPER or REAL, or null for both. Section 10 requires the two to be reported
     *                 separately as well as together, so both splits are always returned.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> dashboard(Long userId, String from, String to,
                                         String symbol, String strategy, String mode) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            StringBuilder where = new StringBuilder(" WHERE 1=1");
            List<Object> params = new ArrayList<>();
            if (userId != null)                 { where.append(" AND user_id=?"); params.add(userId); }
            if (notBlank(from))                 { where.append(" AND COALESCE(exit_date, entry_date) >= ?"); params.add(from); }
            if (notBlank(to))                   { where.append(" AND COALESCE(exit_date, entry_date) <= ?"); params.add(to); }
            if (notBlank(symbol))               { where.append(" AND UPPER(symbol)=?"); params.add(symbol.toUpperCase()); }
            if (notBlank(strategy))             { where.append(" AND strategy_key=?"); params.add(strategy); }
            if (notBlank(mode))                 { where.append(" AND UPPER(COALESCE(trade_type,'PAPER'))=?"); params.add(mode.toUpperCase()); }
            String filter = where.toString();

            List<Object[]> rows = (List<Object[]>) bind(em.createNativeQuery(
                "SELECT symbol,strategy_key,COALESCE(trade_type,'PAPER'),qty,entry_price,exit_price,"
              + "entry_date,exit_date,status,COALESCE(gross_pnl,0),COALESCE(total_charges,0),"
              + "COALESCE(pnl,0),exit_reason FROM paper_trades" + filter
              + " ORDER BY COALESCE(exit_date, entry_date) ASC, id ASC"), params).getResultList();

            // Open positions are marked to market, so unrealised P&L moves with the screen.
            List<String> openSymbols = rows.stream()
                .filter(r -> "open".equalsIgnoreCase(str(r[8])))
                .map(r -> str(r[0])).distinct().toList();
            Map<String, Double> live = openSymbols.isEmpty()
                ? Map.of() : tracker.executablePrices(openSymbols);

            double realizedGross = 0, realizedNet = 0, totalCharges = 0;
            double unrealized = 0, openInvested = 0, closedInvested = 0;
            int wins = 0, losses = 0, scratches = 0, openCount = 0, closedCount = 0;
            int closedWithoutCharges = 0;
            double sumWin = 0, sumLoss = 0;

            Map<String, double[]> byStrategy = new LinkedHashMap<>();   // [net, trades, wins]
            Map<String, double[]> bySymbol = new LinkedHashMap<>();
            Map<String, double[]> byMode = new LinkedHashMap<>();
            Map<String, Double> byDay = new LinkedHashMap<>();
            Map<String, Integer> byExitReason = new LinkedHashMap<>();

            for (Object[] r : rows) {
                String sym = str(r[0]);
                String strat = str(r[1]).isBlank() ? "(unassigned)" : str(r[1]);
                String tradeMode = str(r[2]);
                int qty = (int) num(r[3]);
                double entry = num(r[4]);
                double exit = num(r[5]);
                String exitDate = str(r[7]);
                boolean closed = !"open".equalsIgnoreCase(str(r[8]));
                double storedGross = num(r[9]);
                double storedCharges = num(r[10]);
                double storedNet = num(r[11]);
                double invested = entry * qty;

                if (!closed) {
                    openCount++;
                    openInvested += invested;
                    double price = live.getOrDefault(tracker.normalizeSymbol(sym), 0.0);
                    if (price > 0 && entry > 0) unrealized += (price - entry) * qty;
                    continue;
                }

                closedCount++;
                closedInvested += invested;
                double gross = storedGross != 0 ? storedGross : (exit - entry) * qty;
                // A trade with no stored charge figure predates the charges engine. Counted at
                // gross and tallied, rather than treated as a zero-cost trade - which would
                // flatter every historical number on this page.
                if (storedCharges <= 0) closedWithoutCharges++;
                double net = storedNet != 0 ? storedNet : gross - storedCharges;

                realizedGross += gross;
                realizedNet += net;
                totalCharges += storedCharges;

                if (net > 0)      { wins++;   sumWin += net; }
                else if (net < 0) { losses++; sumLoss += Math.abs(net); }
                else               scratches++;

                accumulate(byStrategy, strat, net);
                accumulate(bySymbol, sym, net);
                accumulate(byMode, tradeMode, net);
                if (notBlank(exitDate)) byDay.merge(exitDate, net, Double::sum);
                String reason = str(r[12]).isBlank() ? "(unspecified)" : str(r[12]);
                byExitReason.merge(reason, 1, Integer::sum);
            }

            // Capital curve and maximum drawdown, walked in date order over realised net P&L.
            // Drawdown is measured peak-to-trough on the cumulative curve, which is the figure
            // that answers "how bad did this get" - not the single worst trade.
            List<Map<String, Object>> curve = new ArrayList<>();
            double cumulative = 0, peak = 0, maxDrawdown = 0, maxDrawdownPct = 0;
            for (Map.Entry<String, Double> e : byDay.entrySet()) {
                cumulative = round(cumulative + e.getValue());
                peak = Math.max(peak, cumulative);
                double dd = peak - cumulative;
                if (dd > maxDrawdown) {
                    maxDrawdown = round(dd);
                    maxDrawdownPct = peak > 0 ? round(dd / peak * 100) : 0;
                }
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("date", e.getKey());
                point.put("dayPnl", round(e.getValue()));
                point.put("cumulativePnl", cumulative);
                curve.add(point);
            }

            out.put("success", true);
            out.put("totalInvestment", round(openInvested + closedInvested));
            out.put("capitalDeployed", round(openInvested));
            out.put("openPositions", openCount);
            out.put("closedPositions", closedCount);
            out.put("realizedGrossPnl", round(realizedGross));
            out.put("realizedNetPnl", round(realizedNet));
            out.put("unrealizedPnl", round(unrealized));
            out.put("totalCharges", round(totalCharges));
            // The headline. Net, because that is the money.
            out.put("netPnl", round(realizedNet + unrealized));
            out.put("winningTrades", wins);
            out.put("losingTrades", losses);
            out.put("scratchTrades", scratches);
            out.put("winRatePct", closedCount > 0 ? round((double) wins / closedCount * 100) : 0);
            out.put("avgProfitPerWin", wins > 0 ? round(sumWin / wins) : 0);
            out.put("avgLossPerLoss", losses > 0 ? round(sumLoss / losses) : 0);
            // Null rather than infinity when nothing lost. A profit factor of "Infinity" on three
            // winning trades reads as a result; it is an absence of losses.
            out.put("profitFactor", sumLoss > 0 ? round(sumWin / sumLoss) : null);
            out.put("expectancyPerTrade", closedCount > 0 ? round(realizedNet / closedCount) : 0);
            out.put("maxDrawdown", maxDrawdown);
            out.put("maxDrawdownPct", maxDrawdownPct);
            out.put("capitalCurve", curve);
            out.put("byStrategy", split(byStrategy));
            out.put("byStock", split(bySymbol));
            out.put("byMode", split(byMode));
            out.put("byExitReason", byExitReason);
            out.put("closedTradesWithoutChargeData", closedWithoutCharges);
            if (closedWithoutCharges > 0) {
                out.put("chargeDataNote", closedWithoutCharges + " closed trade"
                    + (closedWithoutCharges == 1 ? "" : "s") + " have no recorded charges - they "
                    + "predate the charges engine - so " + (closedWithoutCharges == 1 ? "it is" : "they are")
                    + " counted at gross. Net P&L is therefore better than reality by their cost, "
                    + "roughly 0.3% of each position for a delivery round trip.");
            }
            out.put("filters", Map.of(
                "from", from == null ? "" : from, "to", to == null ? "" : to,
                "symbol", symbol == null ? "" : symbol,
                "strategy", strategy == null ? "" : strategy,
                "mode", mode == null ? "" : mode));
        } catch (Exception e) {
            log.warn("[PnL] Dashboard failed: {}", e.getMessage());
            out.put("success", false);
            out.put("message", "Could not build the P&L dashboard: " + e.getMessage());
        }
        return out;
    }

    private static void accumulate(Map<String, double[]> into, String key, double net) {
        double[] agg = into.computeIfAbsent(key, k -> new double[3]);
        agg[0] += net;
        agg[1] += 1;
        if (net > 0) agg[2] += 1;
    }

    /** Biggest contribution first, so the list answers "what is driving this" at a glance. */
    private static List<Map<String, Object>> split(Map<String, double[]> agg) {
        List<Map<String, Object>> out = new ArrayList<>();
        agg.forEach((k, v) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", k);
            m.put("netPnl", round(v[0]));
            m.put("trades", (int) v[1]);
            m.put("wins", (int) v[2]);
            m.put("winRatePct", v[1] > 0 ? round(v[2] / v[1] * 100) : 0);
            out.add(m);
        });
        out.sort((a, b) -> Double.compare((Double) b.get("netPnl"), (Double) a.get("netPnl")));
        return out;
    }

    private static jakarta.persistence.Query bind(jakarta.persistence.Query q, List<Object> params) {
        for (int i = 0; i < params.size(); i++) q.setParameter(i + 1, params.get(i));
        return q;
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
