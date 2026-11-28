package com.traderakshak.service;

import com.traderakshak.entity.Strategy;
import com.traderakshak.entity.User;
import com.traderakshak.repository.StrategyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every strategy in this system that automation can be pointed at, and the stocks each one
 * currently identifies.
 *
 * <h3>Why a registry rather than more switches</h3>
 *
 * <p>Automation was welded to one strategy. {@code VolumeBreakoutAutoTradeService.autoBuy} reads
 * {@code volbreakout_shortlist} directly, so the only thing it could ever trade was the RSI 45
 * volume breakout - and every other strategy in the platform, including anything built in the
 * Strategy Builder, had no route to execution at all. Adding a second one the same way would
 * have meant a second service, a second set of config keys and a second place for the two to
 * drift apart.
 *
 * <p>So a source is just two things: a name, and a way to produce today's symbols. Execution is
 * unchanged - {@code placeManual} and {@code sellManual} already take an arbitrary symbol list,
 * and they are the tested path that charges, sizes and records a trade. This class resolves the
 * list; it does not re-implement any of that.
 *
 * <h3>Settings</h3>
 *
 * <p>Per source and per mode, under {@code autotrade.<source>.<mode>.<name>}, falling back to the
 * shared {@code volbreakout.<name>} defaults that already exist. Per source because a stop loss
 * that suits a five-stock overnight basket is not the one for a screen returning thirty names,
 * and arming one strategy must never arm another.
 */
@Service
@Slf4j
public class AutoStrategySourceService {

    /** A source's stable identifier. Stored in config keys, so it must not change. */
    public static final String RSI45_CURRENT   = "RSI45_CURRENT_DAY";
    public static final String RSI45_PREVIOUS  = "RSI45_PREVIOUS_DAY";
    public static final String RSI45_VOLUME    = "RSI45_WITH_VOLUME";
    /** Prefix for a strategy built in the Strategy Builder: {@code BUILDER:<id>}. */
    public static final String BUILDER_PREFIX  = "BUILDER:";

    private final StockTrackerService tracker;
    private final StrategyBuilderService builderSvc;
    private final StrategyRepository stratRepo;
    private final AppConfigService appConfig;
    private final VolumeBreakoutAutoTradeService autoTrade;

    public AutoStrategySourceService(StockTrackerService tracker,
                                     StrategyBuilderService builderSvc,
                                     StrategyRepository stratRepo,
                                     AppConfigService appConfig,
                                     VolumeBreakoutAutoTradeService autoTrade) {
        this.tracker = tracker;
        this.builderSvc = builderSvc;
        this.stratRepo = stratRepo;
        this.appConfig = appConfig;
        this.autoTrade = autoTrade;
    }

    // ───────────────────────────────────────────────────────────── the registry

    /** Every source this user can point automation at, built-ins first. */
    public List<Map<String, Object>> sources(User user) {
        List<Map<String, Object>> out = new ArrayList<>();

        out.add(builtIn(RSI45_CURRENT, "RSI 45 — Current Day Stocks",
            "Previous close below the base price, current price at or above it, today's volume "
          + "above the 20-session average, and optionally a stochastic %K/%D cross. The five "
          + "highest volume ratios, re-screened on the newest session.",
            "Screened live"));

        out.add(builtIn(RSI45_PREVIOUS, "RSI 45 — Heading into tomorrow (Previous Day)",
            "The five frozen at the previous session's cut-off. Identities cannot change during "
          + "the session, which is what makes this the list an overnight position was opened "
          + "from.",
            "Frozen snapshot"));

        out.add(builtIn(RSI45_VOLUME, "RSI 45 Previous Close Less the Base Price with Volume",
            "The same entry test, unranked and uncapped: every stock that qualifies, heaviest "
          + "traded first. Use it to see what the five-stock shortlist left out.",
            "Screened live"));

        // Anything saved in the Strategy Builder. Listed even when it has no conditions yet, so
        // a half-finished strategy is visibly present rather than mysteriously absent.
        try {
            for (Strategy s : stratRepo.findByUserIdAndStatusOrderByIdDesc(user.getId(), "active")) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", BUILDER_PREFIX + s.getId());
                m.put("name", s.getName());
                m.put("description", s.getDescription() == null || s.getDescription().isBlank()
                    ? "Built in the Strategy Builder." : s.getDescription());
                m.put("basis", "Strategy Builder");
                m.put("builtIn", false);
                m.put("universe", s.getUniverse());
                m.put("timeframe", s.getTimeframe());
                m.put("stopLossPct", s.getStopLossPct());
                m.put("targetPct", s.getTargetPct());
                m.put("workflowStatus", s.getWorkflowStatus());
                out.add(m);
            }
        } catch (Exception e) {
            log.warn("[AutoSource] Could not list builder strategies: {}", e.getMessage());
        }
        return out;
    }

    private static Map<String, Object> builtIn(String key, String name, String desc, String basis) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("name", name);
        m.put("description", desc);
        m.put("basis", basis);
        m.put("builtIn", true);
        return m;
    }

    // ──────────────────────────────────────────────────────── resolving to stocks

    /**
     * The stocks a source identifies right now.
     *
     * <p>Each built-in calls the same method its own screen calls, with the same arguments. That
     * is the point: if this screen and that screen disagreed about which stocks a strategy had
     * selected, one of them would be lying, and there would be no way to tell which.
     */
    @SuppressWarnings("unchecked")
    /**
     * The ceiling the Stock Upload sections apply, repeated here so the two agree.
     *
     * <p>This service drives Auto Buy &amp; Sell, and it was calling the 13-argument overload of
     * {@code getBelowPriceTop10} - which ends at {@code useNewestSession} and therefore left
     * {@code requireRsiLevel} and this ceiling at their defaults. The page that PLACES ORDERS was
     * screening on weaker conditions than the page that reports: for the 2026-11-26 session it
     * listed 13 stocks against the section's 2, because the mandatory RSI 45 crossover was never
     * applied to it.
     *
     * <p>A literal rather than a shared constant because the figure belongs to the strategy, and
     * the frontend holds its own copy; if it ever becomes configurable both should read the same
     * setting. What must not happen again is the two silently differing.
     */
    private static final double MAX_PCT_ABOVE_BASE = 10.0;

    public Map<String, Object> resolve(User user, String key) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key);
        String k = key == null ? "" : key.trim();

        try {
            if (RSI45_CURRENT.equals(k)) {
                Map<String, Object> r = tracker.getBelowPriceTop10(
                    user.getId(), user.getInstitutionId(), null,
                    true,   // requireCrossing
                    true,   // requireVolume
                    false,  // applyRanking - it is a screen
                    true,   // priceAtClose
                    5,      // the shortlist size the section shows
                    false,  // requireFullVolumeWindow - the section does not demand a full window
                    true,   // sortByVolumeRatio
                    tracker.isStochCrossRequired(),
                    true,   // useNewestSession - "current day" means the current session
                    false,  // sortByReturn - this section ranks by volume ratio
                    true,   // requireRsiLevel - daily RSI(14) at or above 45, as the sections apply
                    MAX_PCT_ABOVE_BASE);
                return rowsFrom(out, r, "top10");
            }
            if (RSI45_VOLUME.equals(k)) {
                Map<String, Object> r = tracker.getBelowPriceTop10(
                    user.getId(), user.getInstitutionId(), null,
                    true,   // requireCrossing
                    true,   // requireVolume
                    false,  // applyRanking
                    true,   // priceAtClose
                    0,      // uncapped: every qualifying stock
                    false,  // requireFullVolumeWindow
                    false,  // sortByVolumeRatio
                    false,  // requireStochCross - not part of this section
                    true,   // useNewestSession
                    true,   // sortByReturn - strongest move first, as the section states
                    true,   // requireRsiLevel - daily RSI(14) at or above 45, as the sections apply
                    MAX_PCT_ABOVE_BASE);
                return rowsFrom(out, r, "top10");
            }
            if (RSI45_PREVIOUS.equals(k)) {
                Map<String, Object> r = tracker.getVolumeBreakoutPrevious(
                    user.getId(), user.getInstitutionId());
                return rowsFrom(out, r, "top10");
            }
            if (k.startsWith(BUILDER_PREFIX)) {
                Long id = Long.parseLong(k.substring(BUILDER_PREFIX.length()).trim());
                Strategy s = stratRepo.findById(id).orElse(null);
                if (s == null) {
                    out.put("success", false);
                    out.put("rows", List.of());
                    out.put("message", "That strategy no longer exists.");
                    return out;
                }
                Map<String, Object> r = builderSvc.runScreener(s, 100);
                Map<String, Object> res = rowsFrom(out, r, "results");
                // The screener names its columns differently from the tracker sections; one
                // shape reaches the screen so the table does not need to know which source it
                // is drawing.
                List<Map<String, Object>> rows = (List<Map<String, Object>>) res.get("rows");
                for (Map<String, Object> row : rows) {
                    row.putIfAbsent("current_price", row.get("cmp"));
                    row.putIfAbsent("return_pct", row.get("changePct"));
                }
                res.put("skippedConditions", r.get("skippedConditions"));
                return res;
            }

            out.put("success", false);
            out.put("rows", List.of());
            out.put("message", "Unknown strategy \"" + key + "\".");
            return out;

        } catch (Exception e) {
            log.warn("[AutoSource] resolve({}) failed: {}", key, e.getMessage());
            out.put("success", false);
            out.put("rows", List.of());
            out.put("message", "Could not read that strategy: " + e.getMessage());
            return out;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rowsFrom(Map<String, Object> out, Map<String, Object> r,
                                         String listKey) {
        List<Map<String, Object>> rows = r == null ? List.of()
            : (List<Map<String, Object>>) r.getOrDefault(listKey, List.of());
        List<String> symbols = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object sym = row.get("symbol");
            if (sym != null && !String.valueOf(sym).isBlank()) symbols.add(String.valueOf(sym));
        }
        out.put("success", true);
        out.put("rows", rows);
        out.put("symbols", symbols);
        out.put("count", rows.size());
        if (r != null) {
            // Carried through so the screen can state which session it is showing and how far
            // behind today that is - the same line both tracker sections already carry.
            for (String pass : new String[]{"as_of", "newest_stored_session", "days_behind_today",
                                            "sessions_behind", "qualifying_count", "message",
                                            "trade_date", "finalized_at"}) {
                if (r.containsKey(pass)) out.put(pass, r.get(pass));
            }
        }
        if (rows.isEmpty() && !out.containsKey("message")) {
            out.put("message", "No stock met this strategy's conditions.");
        }
        return out;
    }

    // ────────────────────────────────────────────────────────────────── settings

    private static final String INHERIT = "inherit";

    private String prefix(String key, String mode) {
        return "autotrade." + slug(key) + "." + (("REAL".equalsIgnoreCase(mode)) ? "real" : "paper") + ".";
    }

    /** Config keys allow a narrow charset, so a source key becomes a slug. */
    private static String slug(String key) {
        return key == null ? "unknown"
            : key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    private String raw(String p, String name) {
        String v = appConfig.getString(p + name, "");
        return v == null || v.isBlank() || INHERIT.equalsIgnoreCase(v.trim()) ? null : v.trim();
    }
    private double d(String p, String n, double fb) {
        String o = raw(p, n);
        if (o != null) { try { return Double.parseDouble(o); } catch (NumberFormatException ignored) { } }
        return appConfig.getDouble("volbreakout." + n, fb);
    }
    private int i(String p, String n, int fb) {
        String o = raw(p, n);
        if (o != null) { try { return (int) Double.parseDouble(o); } catch (NumberFormatException ignored) { } }
        return appConfig.getInt("volbreakout." + n, fb);
    }
    private boolean b(String p, String n, boolean fb) {
        String o = raw(p, n);
        if (o != null) return "true".equalsIgnoreCase(o) || "1".equals(o);
        // Never inherit the arming flags. A strategy must be armed deliberately, and falling
        // back to another one's "on" would arm this one the moment it was first opened.
        return fb;
    }
    private String st(String p, String n, String fb) {
        String o = raw(p, n);
        return o != null ? o : appConfig.getString("volbreakout." + n, fb);
    }

    /** One source's settings for one mode, with the shared defaults where none is set. */
    public Map<String, Object> settings(String key, String mode) {
        String p = prefix(key, mode);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("mode", "REAL".equalsIgnoreCase(mode) ? "REAL" : "PAPER");
        m.put("autoBuy",            b(p, "auto.buy", false));
        m.put("autoSell",           b(p, "auto.sell", false));
        m.put("armed",              b(p, "armed", false));
        m.put("investmentAmount",   d(p, "investment.amount", 100000));
        m.put("maxCapitalPerStock", d(p, "max.capital.per.stock", 0));
        m.put("maxOpenPositions",   i(p, "max.open.positions", 5));
        m.put("maxDailyTrades",     i(p, "max.daily.trades", 0));
        m.put("maxDailyLoss",       d(p, "max.daily.loss", 0));
        m.put("stopLossPct",        d(p, "stop.loss.pct", 0));
        m.put("trailingStopPct",    d(p, "trailing.stop.pct", 0));
        m.put("targetPct",          d(p, "target.pct", 0));
        m.put("tradingStartTime",   st(p, "trading.start.time", "09:15"));
        m.put("tradingEndTime",     st(p, "trading.end.time", "15:30"));
        // The trigger times, defaulted from the keys the built-in engine has always read so the
        // screen shows the time really in force rather than an empty box.
        m.put("buyTime",  st(p, "buy.time",
                              appConfig.getString("volbreakout.real.order.time", "15:25")));
        m.put("sellTime", st(p, "sell.time", "15:25"));
        m.put("maxCapitalUtilizationPct", d(p, "max.capital.utilization.pct", 100));
        // One allocation method exists: an equal split across the selected stocks, in whole
        // shares. Reported rather than offered as a choice - a dropdown with one entry implies
        // the others exist.
        m.put("allocationMethod", "EQUAL_SPLIT");
        m.put("allocationMethodLabel", "Equal split across selected stocks (whole shares)");
        // Stated plainly, because it decides whether these settings actually do anything.
        //
        // The per-minute scheduler trades the finalized volume-breakout shortlist, which is the
        // data THIS source resolves to - so for RSI45_PREVIOUS_DAY the Automation Status, Auto
        // Buy/Sell and Buy/Sell Time saved here are read by the scheduler. For every other
        // source they are stored and applied by this screen's own actions, but no scheduled job
        // trades that source, so a buy time on it will not fire by itself.
        boolean scheduled = RSI45_PREVIOUS.equals(key);
        m.put("scheduledExecutionHonoursTheseTimes", scheduled);
        m.put("scheduledExecutionNote", scheduled
            ? "The scheduler reads these settings: this is the strategy whose finalized shortlist "
            + "automation trades."
            : "These settings apply to actions taken from this screen. No scheduled job trades "
            + "this strategy, so a Buy Time set here will not fire on its own.");
        // Automated REAL entry is refused by the engine on purpose - the pre-trade gate belongs
        // immediately before the broker call, so real orders go through the submit endpoint
        // instead. Saying so here stops "Enable + Real" being read as armed real automation.
        m.put("automatedRealEntrySupported", false);
        m.put("keyPrefix", p);
        return m;
    }

    /**
     * Save one source's settings for one mode.
     *
     * <p>Validated, never clamped, and the result names anything refused - a stop loss quietly
     * changed from -3 to 0 is worse than one that was refused out loud.
     */
    public Map<String, Object> saveSettings(Long userId, String key, String mode,
                                            Map<String, Object> req) {
        String p = prefix(key, mode);
        List<String> saved = new ArrayList<>(), rejected = new ArrayList<>();

        putBool(req, "autoBuy",  p + "auto.buy",  userId, saved);
        putBool(req, "autoSell", p + "auto.sell", userId, saved);
        putBool(req, "armed",    p + "armed",     userId, saved);

        putNum(req, "investmentAmount",   p + "investment.amount",     userId, 0.01, 1e9, saved,
               rejected, "Capital to deploy must be above zero.");
        putNum(req, "maxCapitalPerStock", p + "max.capital.per.stock", userId, 0, 1e9, saved,
               rejected, "Max capital per stock cannot be negative. Use 0 for no cap.");
        putNum(req, "maxDailyLoss",       p + "max.daily.loss",        userId, 0, 1e9, saved,
               rejected, "Max daily loss cannot be negative. Use 0 for no limit.");
        putNum(req, "maxOpenPositions",   p + "max.open.positions",    userId, 0, 500, saved,
               rejected, "Max open positions must be between 0 and 500.");
        putNum(req, "maxDailyTrades",     p + "max.daily.trades",      userId, 0, 500, saved,
               rejected, "Max daily trades must be between 0 and 500.");
        putNum(req, "stopLossPct",        p + "stop.loss.pct",         userId, 0, 100, saved,
               rejected, "Stop loss % must be between 0 and 100. Use 0 to disable it.");
        putNum(req, "trailingStopPct",    p + "trailing.stop.pct",     userId, 0, 100, saved,
               rejected, "Trailing stop % must be between 0 and 100. Use 0 to disable it.");
        putNum(req, "targetPct",          p + "target.pct",            userId, 0, 1000, saved,
               rejected, "Target % must be between 0 and 1000. Use 0 to disable it.");

        putNum(req, "maxCapitalUtilizationPct", p + "max.capital.utilization.pct", userId,
               1, 100, saved, rejected,
               "Max capital utilization must be between 1 and 100 percent.");

        // The two trigger times. The trading window below is a gate - it says when automation is
        // ALLOWED to act; these say when it actually fires.
        String buy  = putTime(req, "buyTime",  p + "buy.time",  userId, saved, rejected);
        String sell = putTime(req, "sellTime", p + "sell.time", userId, saved, rejected);

        String a = putTime(req, "tradingStartTime", p + "trading.start.time", userId, saved, rejected);
        String z = putTime(req, "tradingEndTime",   p + "trading.end.time",   userId, saved, rejected);

        // A trigger outside the window can never fire, and silently storing one would leave
        // automation looking configured and doing nothing.
        String winStart = a != null ? a : st(p, "trading.start.time", "09:15");
        String winEnd   = z != null ? z : st(p, "trading.end.time",   "15:30");
        for (String[] t : new String[][] {{"Buy", buy}, {"Sell", sell}}) {
            if (t[1] == null) continue;
            if (t[1].compareTo(winStart) < 0 || t[1].compareTo(winEnd) > 0) {
                rejected.add(t[0] + " time " + t[1] + " is outside the trading window "
                           + winStart + "-" + winEnd + ", so it would never fire.");
            }
        }
        if (a != null && z != null && a.compareTo(z) >= 0) {
            rejected.add("Trading start must be before the end time. Automation would never act "
                       + "inside that window.");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", rejected.isEmpty());
        out.put("saved", saved);
        out.put("rejected", rejected);
        out.put("settings", settings(key, mode));
        out.put("message", rejected.isEmpty()
            ? saved.size() + " setting(s) saved."
            : saved.size() + " saved, " + rejected.size() + " rejected. " + String.join(" ", rejected));
        return out;
    }

    private void putBool(Map<String, Object> req, String f, String k, Long uid, List<String> saved) {
        if (!req.containsKey(f)) return;
        Object v = req.get(f);
        boolean on = v instanceof Boolean bb ? bb
                   : "true".equalsIgnoreCase(String.valueOf(v)) || "1".equals(String.valueOf(v));
        appConfig.setValue(k, on ? "true" : "false", "AUTOTRADE", "BOOLEAN", uid);
        saved.add(k);
    }

    private void putNum(Map<String, Object> req, String f, String k, Long uid, double min,
                        double max, List<String> saved, List<String> rejected, String complaint) {
        if (!req.containsKey(f)) return;
        Object v = req.get(f);
        if (v == null || String.valueOf(v).isBlank()) return;
        double n;
        try { n = Double.parseDouble(String.valueOf(v).trim()); }
        catch (NumberFormatException e) { rejected.add(complaint); return; }
        if (Double.isNaN(n) || n < min || n > max) { rejected.add(complaint); return; }
        String str = n == Math.rint(n) && Math.abs(n) < 1e15
            ? String.valueOf((long) n) : String.valueOf(n);
        appConfig.setValue(k, str, "AUTOTRADE", "NUMBER", uid);
        saved.add(k);
    }

    private String putTime(Map<String, Object> req, String f, String k, Long uid,
                           List<String> saved, List<String> rejected) {
        if (!req.containsKey(f)) return null;
        String v = req.get(f) == null ? "" : String.valueOf(req.get(f)).trim();
        if (v.isBlank()) return null;
        if (!v.matches("^([01]\\d|2[0-3]):[0-5]\\d$")) {
            rejected.add("\"" + v + "\" is not a time in HH:MM.");
            return null;
        }
        appConfig.setValue(k, v, "AUTOTRADE", "STRING", uid);
        saved.add(k);
        return v;
    }

    // ───────────────────────────────────────────────────────────────── execution

    /**
     * Buy or sell this source's current stocks.
     *
     * <p>Resolves the list, then hands it to the existing manual paths, which are what charge,
     * size and record a trade. Nothing about execution is re-implemented here - a second
     * implementation is a second set of rounding, a second idempotency key and a second place
     * for the two to disagree about what a position cost.
     */
    public Map<String, Object> run(User user, String key, String side, String mode,
                                   boolean dryRun, boolean confirmed) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> resolved = resolve(user, key);
        @SuppressWarnings("unchecked")
        List<String> symbols = (List<String>) resolved.getOrDefault("symbols", List.of());

        out.put("key", key);
        out.put("side", "SELL".equalsIgnoreCase(side) ? "SELL" : "BUY");
        out.put("symbols", symbols);
        out.put("as_of", resolved.get("as_of"));

        if (symbols.isEmpty()) {
            out.put("success", false);
            out.put("message", "This strategy identifies no stock right now, so there is nothing "
                + "to " + ("SELL".equalsIgnoreCase(side) ? "sell" : "buy") + ". "
                + String.valueOf(resolved.getOrDefault("message", "")).trim());
            return out;
        }

        if (dryRun) {
            out.put("success", true);
            out.put("dryRun", true);
            out.put("message", "Would " + ("SELL".equalsIgnoreCase(side) ? "close" : "open")
                + " " + symbols.size() + " position(s): " + String.join(", ", symbols)
                + ". Nothing was placed.");
            return out;
        }

        Map<String, Object> result = "SELL".equalsIgnoreCase(side)
            ? autoTrade.sellManual(user.getId(), symbols, mode, confirmed)
            : autoTrade.placeManual(user.getId(), user.getInstitutionId(), symbols,
                  settings(key, mode).get("investmentAmount") instanceof Number n
                      ? n.doubleValue() : 100000,
                  mode, confirmed);
        out.putAll(result);
        out.put("key", key);
        return out;
    }
}
