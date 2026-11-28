package com.traderakshak.service;

import com.traderakshak.util.DateUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Watches open paper positions and closes them when a rule fires.
 *
 * <p>The columns this reads - {@code stop_loss}, {@code target}, {@code trailing_sl_level} - were
 * on {@code paper_trades} before this change with nothing evaluating them, so a configured
 * stop-loss did precisely nothing. Section 7 requires stop-loss and trailing stop-loss to be
 * tracked per position, which needs something running on a clock; a rule only checked when someone
 * opens a screen is not a stop.
 *
 * <p><b>Paper only, deliberately.</b> A real position's stop belongs at the broker, where it
 * survives this process dying, the network dropping and the machine rebooting. An application-side
 * stop on a real position is a promise this service cannot keep: if the JVM is down between 09:15
 * and the moment price breaches the level, nothing fires and the user believes they were protected.
 * Real stop-loss orders go to Dhan as STOP_LOSS orders through the existing order path.
 *
 * <p><b>Gated off by default.</b> {@code positionmonitor.enabled} ships false. Nothing closes a
 * position until it is turned on.
 */
@Service
public class PositionMonitorService {

    private static final Logger log = LoggerFactory.getLogger(PositionMonitorService.class);

    @PersistenceContext
    private EntityManager em;

    private final MarketCalendarService marketCalendar;

    private final AppConfigService appConfig;
    private final StockTrackerService tracker;
    private final BrokerageCalculator charges;
    private final TransactionTemplate tx;

    public PositionMonitorService(AppConfigService appConfig, StockTrackerService tracker,
                                  BrokerageCalculator charges, TransactionTemplate tx,
                                  MarketCalendarService marketCalendar) {
        this.appConfig = appConfig;
        this.tracker = tracker;
        this.charges = charges;
        this.tx = tx;
        this.marketCalendar = marketCalendar;
    }

    public boolean isEnabled() {
        return appConfig.getBoolean("positionmonitor.enabled", false);
    }

    /** Default risk settings, used where a position carries none of its own. */
    private double defaultStopPct()   { return appConfig.getDouble("trading.default_stop_loss_pct", 0); }
    private double defaultTrailPct()  { return appConfig.getDouble("trading.default_trailing_stop_loss_pct", 0); }
    private double defaultTargetPct() { return appConfig.getDouble("trading.default_target_pct", 0); }

    /**
     * One pass over every open paper position.
     *
     * <p>Advances each high-water mark first and then evaluates, so a position that makes a new
     * high and immediately retraces within the same pass is measured against the high it actually
     * reached rather than the one from the previous pass.
     */
    public Map<String, Object> sweep(Long userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> closed = new ArrayList<>();
        int examined = 0, trailsRaised = 0;
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> open = em.createNativeQuery(
                "SELECT id,symbol,qty,entry_price,entry_date,stop_loss_pct,trailing_sl_level,"
              + "target_pct,high_water_mark,strategy_key,exchange,user_id "
              + "FROM paper_trades WHERE status='open'"
              + (userId == null ? "" : " AND user_id=" + userId)
              + " ORDER BY id ASC")
                .getResultList();
            if (open.isEmpty()) {
                out.put("success", true);
                out.put("examined", 0);
                out.put("closed", List.of());
                return out;
            }

            List<String> symbols = open.stream().map(r -> str(r[1])).distinct().toList();
            Map<String, Double> prices = tracker.executablePrices(symbols);

            for (Object[] r : open) {
                examined++;
                long id = num(r[0]) > 0 ? (long) num(r[0]) : 0;
                String symbol = str(r[1]);
                int qty = (int) num(r[2]);
                double entry = num(r[3]);
                String entryDate = str(r[4]);
                double stopPct = num(r[5]) > 0 ? num(r[5]) : defaultStopPct();
                double trailPct = num(r[6]) > 0 ? num(r[6]) : defaultTrailPct();
                double targetPct = num(r[7]) > 0 ? num(r[7]) : defaultTargetPct();
                double highWater = num(r[8]);
                String strategy = str(r[9]);
                String exchange = str(r[10]).isBlank() ? "NSE" : str(r[10]);
                long owner = (long) num(r[11]);

                double price = prices.getOrDefault(tracker.normalizeSymbol(symbol), 0.0);
                if (price <= 0 || entry <= 0) continue;

                double newHigh = PositionExitRules.advanceHighWater(highWater, entry, price);
                if (newHigh > highWater) {
                    trailsRaised++;
                    final long fid = id; final double fh = newHigh;
                    tx.executeWithoutResult(s -> em.createNativeQuery(
                        "UPDATE paper_trades SET high_water_mark=? WHERE id=?")
                        .setParameter(1, fh).setParameter(2, fid).executeUpdate());
                }

                boolean timeExitDue = isTimeExitDue(strategy, entryDate);
                var d = PositionExitRules.evaluate(entry, price, newHigh,
                                                   stopPct, trailPct, targetPct, timeExitDue);
                if (!d.exit()) continue;

                Map<String, Object> result = closePosition(id, owner, symbol, qty, entry,
                                                           d.triggerPrice(), d.reason(), exchange);
                closed.add(result);
            }
            out.put("success", true);
        } catch (Exception e) {
            log.warn("[PositionMonitor] Sweep failed: {}", e.getMessage());
            out.put("success", false);
            out.put("message", e.getMessage());
        }
        out.put("examined", examined);
        out.put("trails_raised", trailsRaised);
        out.put("closed", closed);
        return out;
    }

    /**
     * Whether the holding period has run out.
     *
     * <p>For the Volume Breakout strategy the rule is one session: in at the finalization day's
     * close, out at the next trading day's close. So the time exit is due once the entry date is
     * no longer today AND a later session's close exists - which is what makes it "the next
     * TRADING day" rather than "tomorrow", skipping weekends, holidays and suspensions without
     * needing a calendar lookup here.
     *
     * <p>Other strategies hold until their own configured sell time and are left alone; returning
     * false for them means this monitor only ever applies price rules to them, never a clock.
     */
    private boolean isTimeExitDue(String strategyKey, String entryDate) {
        if (!"volbreakout".equalsIgnoreCase(strategyKey == null ? "" : strategyKey)) return false;
        if (entryDate == null || entryDate.isBlank()) return false;
        if (entryDate.compareTo(DateUtil.today()) >= 0) return false;
        try {
            Object later = em.createNativeQuery(
                "SELECT COUNT(*) FROM broker_market_candles WHERE UPPER(COALESCE(candle_mode,''))='DAILY' "
              + "AND trade_date > ? AND close > 0 LIMIT 1")
                .setParameter(1, entryDate).getSingleResult();
            return later instanceof Number n && n.longValue() > 0;
        } catch (Exception e) {
            // Unknown means not due. Closing a position because a query failed would be the worst
            // possible reading of an error.
            return false;
        }
    }

    /**
     * Close one position and record the outcome, net of charges.
     *
     * <p>Net P&L is computed here rather than left to a report, because gross minus nothing was
     * what the journal stored before: a one-session round trip costs roughly 0.30% of the position,
     * so a trade that made 0.2% was being recorded as a winner.
     */
    private Map<String, Object> closePosition(long id, long userId, String symbol, int qty,
                                              double entry, double exitPrice,
                                              PositionExitRules.Reason reason, String exchange) {
        double gross = Math.round((exitPrice - entry) * qty * 100.0) / 100.0;
        var c = charges.roundTrip(BrokerageCalculator.Product.DELIVERY, exchange,
                                  entry, exitPrice, qty);
        double net = Math.round((gross - c.total()) * 100.0) / 100.0;
        double netPct = entry * qty > 0
            ? Math.round((net / (entry * qty)) * 10000.0) / 100.0 : 0;
        String now = DateUtil.now();

        tx.executeWithoutResult(s -> em.createNativeQuery(
            "UPDATE paper_trades SET exit_price=?, exit_date=?, exit_type=?, exit_reason=?, "
          + "gross_pnl=?, total_charges=?, pnl=?, pnl_pct=?, status='closed' WHERE id=?")
            .setParameter(1, exitPrice)
            .setParameter(2, DateUtil.today())
            .setParameter(3, reason.name())
            .setParameter(4, reason.name())
            .setParameter(5, gross)
            .setParameter(6, c.total())
            .setParameter(7, net)
            .setParameter(8, netPct)
            .setParameter(9, id)
            .executeUpdate());

        log.info("[PositionMonitor] Closed {} #{} at {} on {} - gross {} charges {} net {} ({}%)",
            symbol, id, exitPrice, reason, gross, c.total(), net, netPct);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("symbol", symbol);
        m.put("quantity", qty);
        m.put("entryPrice", entry);
        m.put("exitPrice", exitPrice);
        m.put("exitReason", reason.name());
        m.put("grossPnl", gross);
        m.put("charges", c.total());
        m.put("netPnl", net);
        m.put("netPct", netPct);
        m.put("closedAt", now);
        return m;
    }

    /**
     * Every minute during market hours.
     *
     * <p>Not on every tick. A sweep reads each open position and prices it, and on a handful of
     * positions that is cheap, but running it per tick on a 2 GB box with a live feed would mean
     * thousands of passes a minute for a level that moves in paise. A minute is fine for a
     * percentage stop; anything tighter belongs at the broker, where it is a resting order rather
     * than a poll.
     */
    @Scheduled(cron = "0 * 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledSweep() {
        // Outbound calls are confined to the automation window: a trading day between 08:00
        // and 16:00 IST. Outside it - overnight, weekends, exchange holidays - this stands down.
        if (!marketCalendar.isAutomationWindowOpen()) {
            log.debug("[{}] Skipped: {}.", "PositionMonitor", marketCalendar.automationWindowReason());
            return;
        }
        if (!isEnabled()) return;
        if (!tracker.isTradingSessionLive()) return;
        try {
            Map<String, Object> r = sweep(null);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> closed =
                (List<Map<String, Object>>) r.getOrDefault("closed", List.of());
            if (!closed.isEmpty()) {
                log.info("[PositionMonitor] Sweep closed {} position(s)", closed.size());
            }
        } catch (Exception e) {
            log.warn("[PositionMonitor] Scheduled sweep failed: {}", e.getMessage());
        }
    }

    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
}
