package com.traderakshak.service;

import com.traderakshak.util.DateUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Automated entry and exit for the Volume Breakout strategy.
 *
 * <p><b>What the existing automation did and did not do.</b>
 * {@code POST /trading/stock-tracker/run} wrote an audit line saying it had "reviewed" the list and
 * placed nothing. {@code prevTop10AutomationTick} wrote simulated batch rows with
 * {@code execution_source = 'SIM'}, which are a record of what would have happened rather than
 * positions. Neither produced an order. This does.
 *
 * <p><b>The timing problem, stated rather than hidden.</b> The strategy is "buy at the 15:30
 * close, sell at the next session's 15:30 close". In paper trading that is exact - the official
 * close is a known number and the simulated fill uses it. In real trading it is not achievable:
 * 15:30 is the bell, and NSE's official close is a weighted average of the final thirty minutes
 * rather than a price anyone can trade at. A real market order must therefore be sent slightly
 * BEFORE the close ({@code volbreakout.real.order.time}, default 15:25) and will fill at whatever
 * is trading then - close to the official close, but not equal to it. The journal records both:
 * the intended basis and the price actually paid, once the reconciler has read it back from the
 * broker. Pretending the two are the same would make every backtest flatter than reality.
 *
 * <p><b>Ships inert.</b> {@code volbreakout.enabled}, {@code volbreakout.auto.buy} and
 * {@code volbreakout.auto.sell} all default false, and {@code volbreakout.mode} defaults to PAPER.
 * Real orders additionally need the user's own permission, a verified trading-enabled broker and
 * the compliance gate - see {@link AutoTradeGuard}.
 *
 * <p>This service places orders through the ordinary order path. It never bypasses the gate.
 */
@Service
public class VolumeBreakoutAutoTradeService {

    private static final Logger log = LoggerFactory.getLogger(VolumeBreakoutAutoTradeService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String STRATEGY = "volbreakout";

    @PersistenceContext
    private EntityManager em;

    private final MarketCalendarService marketCalendar;

    private final AppConfigService appConfig;
    private final StockTrackerService tracker;
    private final BrokerageCalculator charges;
    private final CapitalAllocationService allocation;
    private final TransactionTemplate tx;

    public VolumeBreakoutAutoTradeService(AppConfigService appConfig, StockTrackerService tracker,
                                          BrokerageCalculator charges,
                                          CapitalAllocationService allocation,
                                          TransactionTemplate tx,
                                          MarketCalendarService marketCalendar) {
        this.appConfig = appConfig;
        this.tracker = tracker;
        this.charges = charges;
        this.allocation = allocation;
        this.tx = tx;
        this.marketCalendar = marketCalendar;
    }

    public String  mode()              { return appConfig.getString("volbreakout.mode", "PAPER"); }

    // ── Mode-scoped settings (section 6: "separate configuration settings for Paper and Real") ──
    //
    // Paper and Real want genuinely different numbers. A 5% stop that is fine on a simulated
    // position is not what most people want risking real money the first time they switch over,
    // and a single shared set means flipping the mode silently re-applies whatever was tuned for
    // the other one.
    //
    // Each setting is read as volbreakout.<mode>.<name> and falls back to the shared
    // volbreakout.<name>. The fallback is what makes this safe to add: every existing
    // configuration keeps working untouched, and a mode-specific value only applies once it is
    // deliberately set.

    /**
     * The per-mode value when one is set, otherwise the shared one.
     *
     * <p>Read as a STRING and compared against an explicit {@code inherit} sentinel, which matters
     * more than it looks. The per-mode keys are seeded so they are discoverable in Settings, which
     * means they always exist - so a typed read would return the seeded default and the shared
     * value could never win. And no numeric sentinel works here: 0 is a real value for several of
     * these settings ("no limit"), and false is a real value for the toggles. Only a value outside
     * the type can mean "not set".
     */
    private static final String INHERIT = "inherit";

    private String rawOverride(String name) {
        String v = appConfig.getString(
            "volbreakout." + mode().toLowerCase(Locale.ROOT) + "." + name, "");
        return v == null || v.isBlank() || INHERIT.equalsIgnoreCase(v.trim()) ? null : v.trim();
    }

    /**
     * The Automation screen's own settings for the strategy this engine actually trades.
     *
     * <p>There are two settings stores. The Auto Buy &amp; Sell screen writes
     * {@code autotrade.<strategy>.<mode>.<name>} per strategy; this engine has always read
     * {@code volbreakout.<mode>.<name>}. So a buy time, or an Enable, set on the screen never
     * reached the scheduler - it was stored, shown back, and ignored.
     *
     * <p>{@code autoBuy} trades the finalized volume-breakout shortlist, which is the same data
     * the {@code RSI45_PREVIOUS_DAY} source resolves to, so that source's settings are the ones
     * that belong to this engine. The prefix is written as a literal rather than taken from
     * {@code AutoStrategySourceService}: that class already depends on this one, and injecting it
     * back would be a circular bean reference for the sake of a string.
     *
     * <p>Returns null when the screen has not set a value, so the shared keys still decide.
     */
    private static final String SCHEDULED_SOURCE_PREFIX = "autotrade.rsi45_previous_day.";

    private String screenOverride(String name) {
        String v = appConfig.getString(
            SCHEDULED_SOURCE_PREFIX + mode().toLowerCase(Locale.ROOT) + "." + name, "");
        return v == null || v.isBlank() || INHERIT.equalsIgnoreCase(v.trim()) ? null : v.trim();
    }

    private boolean screenBool(String name, boolean fallback) {
        String o = screenOverride(name);
        if (o != null) return "true".equalsIgnoreCase(o) || "1".equals(o);
        return fallback;
    }

    private String screenTime(String name, String fallback) {
        String o = screenOverride(name);
        return o != null ? o : fallback;
    }

    /**
     * Whether the screen's Automation Status is Enable for the strategy this engine trades.
     *
     * <p>Defaults to false when nothing has been saved, which fails closed: automation that has
     * never been deliberately enabled must not act. The existing shared {@code auto.buy} flag is
     * still required on top of this, so neither switch alone can start placing orders.
     */
    public boolean automationArmed() { return screenBool("armed", false); }

    /**
     * The settings in force right now, as JSON, to be stored with a trade the engine places.
     *
     * <p>Section 5B asks a System Trade to record its automation configuration. Recorded at the
     * moment of the order rather than read back later, because the settings change and a trade
     * has to remain explicable on the terms it was actually placed on.
     *
     * <p>Hand-built rather than serialised from a map so the key names are fixed here and cannot
     * drift with an internal rename, and so a value that cannot be read never throws inside an
     * order path - placing the trade matters more than annotating it.
     */
    String automationConfigJson() {
        try {
            return "{"
                 + "\"accountType\":\"" + mode() + "\","
                 + "\"buyTime\":\"" + realOrderTime() + "\","
                 + "\"sellTime\":\"" + sellTime() + "\","
                 + "\"investmentAmount\":" + investmentAmount() + ","
                 + "\"maxOpenPositions\":" + maxOpenPositions() + ","
                 + "\"maxCapitalUtilizationPct\":" + maxCapitalUtilizationPct() + ","
                 + "\"maxDailyTrades\":" + maxDailyTrades() + ","
                 + "\"maxDailyLoss\":" + maxDailyLoss() + ","
                 + "\"stopLossPct\":" + stopLossPct() + ","
                 + "\"trailingStopPct\":" + trailingStopPct() + ","
                 + "\"targetPct\":" + targetPct() + ","
                 + "\"allocationMethod\":\"EQUAL_SPLIT\","
                 + "\"strategy\":\"" + STRATEGY + "\""
                 + "}";
        } catch (Exception e) {
            log.debug("[VolBreakout] Could not snapshot the automation config: {}", e.getMessage());
            return null;
        }
    }

    private double cfgD(String name, double fallback) {
        String o = rawOverride(name);
        if (o != null) {
            try { return Double.parseDouble(o); } catch (NumberFormatException ignored) { }
        }
        return appConfig.getDouble("volbreakout." + name, fallback);
    }

    private int cfgI(String name, int fallback) {
        String o = rawOverride(name);
        if (o != null) {
            try { return (int) Double.parseDouble(o); } catch (NumberFormatException ignored) { }
        }
        return appConfig.getInt("volbreakout." + name, fallback);
    }

    private boolean cfgB(String name, boolean fallback) {
        String o = rawOverride(name);
        if (o != null) return "true".equalsIgnoreCase(o) || "1".equals(o);
        return appConfig.getBoolean("volbreakout." + name, fallback);
    }

    private String cfgS(String name, String fallback) {
        String o = rawOverride(name);
        return o != null ? o : appConfig.getString("volbreakout." + name, fallback);
    }

    // The screen's own Auto Buy / Auto Sell win where it has set them, falling back to the shared
    // keys. Without this the checkboxes on the Automation card had no effect on the scheduler.
    public boolean isAutoBuyEnabled()  { return screenBool("auto.buy",  cfgB("auto.buy", false)); }
    public boolean isAutoSellEnabled() { return screenBool("auto.sell", cfgB("auto.sell", false)); }
    public double  investmentAmount()  { return cfgD("investment.amount", 100000); }
    public int     maxOpenPositions()  { return cfgI("max.open.positions", 5); }
    public double  maxDailyLoss()      { return cfgD("max.daily.loss", 0); }
    public int     maxDailyTrades()    { return cfgI("max.daily.trades", 0); }
    public double  stopLossPct()       { return cfgD("stop.loss.pct", 0); }
    public double  trailingStopPct()   { return cfgD("trailing.stop.pct", 0); }
    public double  targetPct()         { return cfgD("target.pct", 0); }
    /** 0 = no per-stock cap. Section 7's "maximum capital per stock". */
    public double  maxCapitalPerStock(){ return cfgD("max.capital.per.stock", 0); }
    /** Section 7's trading window. Outside it, automation does not act. */
    public String  tradingStartTime()  { return cfgS("trading.start.time", "09:15"); }
    public String  tradingEndTime()    { return cfgS("trading.end.time", "15:30"); }

    /** Whether the clock is inside the configured trading window for this mode. */
    public boolean withinTradingWindow() {
        String now = String.format("%02d:%02d",
            ZonedDateTime.now(IST).getHour(), ZonedDateTime.now(IST).getMinute());
        return now.compareTo(tradingStartTime()) >= 0 && now.compareTo(tradingEndTime()) <= 0;
    }

    /** Every effective setting for the current mode, and where each value came from. */
    public Map<String, Object> effectiveSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode());
        m.put("autoBuy", isAutoBuyEnabled());
        m.put("autoSell", isAutoSellEnabled());
        m.put("investmentAmount", investmentAmount());
        m.put("maxOpenPositions", maxOpenPositions());
        m.put("maxCapitalPerStock", maxCapitalPerStock());
        m.put("maxDailyLoss", maxDailyLoss());
        m.put("maxDailyTrades", maxDailyTrades());
        m.put("stopLossPct", stopLossPct());
        m.put("trailingStopPct", trailingStopPct());
        m.put("targetPct", targetPct());
        m.put("tradingStartTime", tradingStartTime());
        m.put("tradingEndTime", tradingEndTime());
        m.put("withinTradingWindow", withinTradingWindow());
        // So a user can see WHICH setting is in force rather than guessing why a number differs
        // from the one they edited.
        m.put("keyPrefix", "volbreakout." + mode().toLowerCase(Locale.ROOT) + ".");
        m.put("fallbackPrefix", "volbreakout.");
        return m;
    }

    /**
     * Every effective setting for ONE named mode, whichever mode is live.
     *
     * <p>{@link #effectiveSettings()} reads whatever {@code volbreakout.mode} currently is, which
     * is right for the engine and wrong for a settings screen: a screen showing Paper and Real
     * side by side has to read the mode it is drawing, not the mode that happens to be armed.
     */
    public Map<String, Object> settingsFor(String wantedMode) {
        String m = "REAL".equalsIgnoreCase(wantedMode) ? "real" : "paper";
        String p = "volbreakout." + m + ".";
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("mode", m.toUpperCase(Locale.ROOT));
        o.put("live", m.equalsIgnoreCase(mode()));
        o.put("autoBuy",            readB(p, "auto.buy", false));
        o.put("autoSell",           readB(p, "auto.sell", false));
        o.put("investmentAmount",   readD(p, "investment.amount", 100000));
        o.put("maxCapitalPerStock", readD(p, "max.capital.per.stock", 0));
        o.put("maxOpenPositions",   readI(p, "max.open.positions", 5));
        o.put("maxDailyLoss",       readD(p, "max.daily.loss", 0));
        o.put("maxDailyTrades",     readI(p, "max.daily.trades", 0));
        o.put("stopLossPct",        readD(p, "stop.loss.pct", 0));
        o.put("trailingStopPct",    readD(p, "trailing.stop.pct", 0));
        o.put("targetPct",          readD(p, "target.pct", 0));
        o.put("tradingStartTime",   readS(p, "trading.start.time", "09:15"));
        o.put("tradingEndTime",     readS(p, "trading.end.time", "15:30"));
        // The trigger times, defaulted from the keys the engine has always read so the screen
        // shows the time that is really in force rather than a blank box.
        o.put("buyTime",  readS(p, "buy.time",
                                appConfig.getString("volbreakout.real.order.time", "15:25")));
        o.put("sellTime", readS(p, "sell.time", "15:25"));
        o.put("maxCapitalUtilizationPct", readD(p, "max.capital.utilization.pct", 100));
        // There is one allocation method in the application - an equal split across the selected
        // stocks, in whole shares. It is reported rather than offered as a choice, because
        // offering a list with one entry implies the others exist.
        o.put("allocationMethod", "EQUAL_SPLIT");
        o.put("allocationMethodLabel", "Equal split across selected stocks (whole shares)");
        // Which of these are this mode's own value and which are inherited from the shared key.
        // Shown on screen, because "I set 3% and it used 5%" is otherwise unanswerable.
        Map<String, Object> src = new LinkedHashMap<>();
        for (String k : new String[]{"auto.buy", "auto.sell", "investment.amount",
                "max.capital.per.stock", "max.open.positions", "max.daily.loss",
                "max.daily.trades", "stop.loss.pct", "trailing.stop.pct", "target.pct",
                "trading.start.time", "trading.end.time",
                "buy.time", "sell.time", "max.capital.utilization.pct"}) {
            String v = appConfig.getString(p + k, "");
            boolean own = v != null && !v.isBlank() && !INHERIT.equalsIgnoreCase(v.trim());
            src.put(k, own ? "mode" : "shared");
        }
        o.put("valueSource", src);
        o.put("keyPrefix", p);
        return o;
    }

    private String rawFor(String prefix, String name) {
        String v = appConfig.getString(prefix + name, "");
        return v == null || v.isBlank() || INHERIT.equalsIgnoreCase(v.trim()) ? null : v.trim();
    }
    private double readD(String prefix, String name, double fb) {
        String o = rawFor(prefix, name);
        if (o != null) { try { return Double.parseDouble(o); } catch (NumberFormatException ignored) { } }
        return appConfig.getDouble("volbreakout." + name, fb);
    }
    private int readI(String prefix, String name, int fb) {
        String o = rawFor(prefix, name);
        if (o != null) { try { return (int) Double.parseDouble(o); } catch (NumberFormatException ignored) { } }
        return appConfig.getInt("volbreakout." + name, fb);
    }
    private boolean readB(String prefix, String name, boolean fb) {
        String o = rawFor(prefix, name);
        if (o != null) return "true".equalsIgnoreCase(o) || "1".equals(o);
        return appConfig.getBoolean("volbreakout." + name, fb);
    }
    private String readS(String prefix, String name, String fb) {
        String o = rawFor(prefix, name);
        return o != null ? o : appConfig.getString("volbreakout." + name, fb);
    }

    /**
     * Save one mode's automation settings.
     *
     * <p>Written to {@code volbreakout.<mode>.*}, never to the shared keys, so saving Paper
     * cannot move Real underneath the user. Each field is validated and the result names every
     * value that was rejected rather than silently clamping it - a stop loss quietly changed
     * from -3 to 0 is worse than one that was refused out loud.
     *
     * <p><b>Real trading stays behind its own gate.</b> Turning on Real auto-buy here writes the
     * flag, and the flag is still only half of it: {@link AutoTradeGuard} also requires the
     * account's real-trading authorization and a connected broker, and refuses without them.
     * This method cannot grant either, by design.
     */
    public Map<String, Object> saveSettings(Long userId, String wantedMode,
                                            Map<String, Object> req) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> rejected = new ArrayList<>();
        List<String> saved = new ArrayList<>();
        String m = "REAL".equalsIgnoreCase(wantedMode) ? "real" : "paper";
        String p = "volbreakout." + m + ".";
        String cat = "VOLBREAKOUT";

        putBool(req, "autoBuy",  p + "auto.buy",  cat, userId, saved);
        putBool(req, "autoSell", p + "auto.sell", cat, userId, saved);

        // Money. 0 means "no cap" for the two caps, but not for the amount being deployed.
        putNum(req, "investmentAmount",   p + "investment.amount",     cat, userId,
               0.01, 1e9, saved, rejected, "Investment amount must be above zero.");
        putNum(req, "maxCapitalPerStock", p + "max.capital.per.stock", cat, userId,
               0, 1e9, saved, rejected,
               "Max capital per stock cannot be negative. Use 0 for no cap.");
        putNum(req, "maxDailyLoss",       p + "max.daily.loss",        cat, userId,
               0, 1e9, saved, rejected,
               "Max daily loss cannot be negative. Use 0 for no limit.");

        putNum(req, "maxOpenPositions", p + "max.open.positions", cat, userId,
               0, 500, saved, rejected, "Max open positions must be between 0 and 500.");
        putNum(req, "maxDailyTrades",   p + "max.daily.trades",   cat, userId,
               0, 500, saved, rejected, "Max daily trades must be between 0 and 500.");

        // Percentages. 100 is the ceiling for the two stops: a stop further away than the entry
        // price is not a stop at all.
        putNum(req, "stopLossPct",     p + "stop.loss.pct",     cat, userId,
               0, 100, saved, rejected,
               "Stop loss % must be between 0 and 100. Use 0 to disable it.");
        putNum(req, "trailingStopPct", p + "trailing.stop.pct", cat, userId,
               0, 100, saved, rejected,
               "Trailing stop % must be between 0 and 100. Use 0 to disable it.");
        putNum(req, "targetPct",       p + "target.pct",        cat, userId,
               0, 1000, saved, rejected,
               "Target % must be between 0 and 1000. Use 0 to disable it.");

        // The two trigger times. These are what the scheduler actually fires on, and neither was
        // writable before: the buy time lived only in a shared key nothing saved, and the sell
        // time in another. Without them "Execute the buy logic at the configured time" had no
        // configured time to execute at.
        putTime(req, "buyTime",  p + "buy.time",  cat, userId, saved, rejected);
        putTime(req, "sellTime", p + "sell.time", cat, userId, saved, rejected);

        putNum(req, "maxCapitalUtilizationPct", p + "max.capital.utilization.pct", cat, userId,
               1, 100, saved, rejected,
               "Max capital utilization must be between 1 and 100 percent.");

        String start = putTime(req, "tradingStartTime", p + "trading.start.time", cat, userId,
                               saved, rejected);
        String end   = putTime(req, "tradingEndTime",   p + "trading.end.time",   cat, userId,
                               saved, rejected);
        if (start != null && end != null && start.compareTo(end) >= 0) {
            rejected.add("Trading start must be before the end time. The window was stored as "
                       + "given but automation will never act inside it.");
        }

        out.put("success", rejected.isEmpty());
        out.put("mode", m.toUpperCase(Locale.ROOT));
        out.put("saved", saved);
        out.put("rejected", rejected);
        out.put("settings", settingsFor(m));
        out.put("message", rejected.isEmpty()
            ? saved.size() + " setting(s) saved for " + m.toUpperCase(Locale.ROOT) + "."
            : saved.size() + " saved, " + rejected.size() + " rejected. "
              + String.join(" ", rejected));
        log.info("[VolBreakout] Settings saved for {}: {} ok, {} rejected",
                 m, saved.size(), rejected.size());
        return out;
    }

    /** Arms or disarms the strategy as a whole, and picks which mode is live. */
    public Map<String, Object> setLiveMode(Long userId, String wantedMode, Boolean enabled) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (wantedMode != null && !wantedMode.isBlank()) {
            String m = "REAL".equalsIgnoreCase(wantedMode) ? "REAL" : "PAPER";
            appConfig.setValue("volbreakout.mode", m, "VOLBREAKOUT", "STRING", userId);
            out.put("mode", m);
        }
        if (enabled != null) {
            appConfig.setValue("volbreakout.enabled", enabled ? "true" : "false",
                               "VOLBREAKOUT", "BOOLEAN", userId);
            out.put("enabled", enabled);
        }
        out.put("success", true);
        out.put("message", "Paper is the default and stays the default. Switching to REAL only "
            + "arms the mode: real orders additionally need this account's real-trading "
            + "authorization and a connected broker, which this does not grant.");
        return out;
    }

    private void putBool(Map<String, Object> req, String field, String key, String cat,
                         Long userId, List<String> saved) {
        if (!req.containsKey(field)) return;
        Object v = req.get(field);
        boolean b = v instanceof Boolean bb ? bb
                  : "true".equalsIgnoreCase(String.valueOf(v)) || "1".equals(String.valueOf(v));
        appConfig.setValue(key, b ? "true" : "false", cat, "BOOLEAN", userId);
        saved.add(key);
    }

    private void putNum(Map<String, Object> req, String field, String key, String cat,
                        Long userId, double min, double max, List<String> saved,
                        List<String> rejected, String complaint) {
        if (!req.containsKey(field)) return;
        Object v = req.get(field);
        if (v == null || String.valueOf(v).isBlank()) return;
        double d;
        try { d = Double.parseDouble(String.valueOf(v).trim()); }
        catch (NumberFormatException e) { rejected.add(complaint); return; }
        if (Double.isNaN(d) || d < min || d > max) { rejected.add(complaint); return; }
        // Whole numbers store without a decimal tail, so a position count reads "5", not "5.0".
        String str = d == Math.rint(d) && Math.abs(d) < 1e15
            ? String.valueOf((long) d) : String.valueOf(d);
        appConfig.setValue(key, str, cat, "NUMBER", userId);
        saved.add(key);
    }

    private static final Pattern HHMM = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    private String putTime(Map<String, Object> req, String field, String key, String cat,
                           Long userId, List<String> saved, List<String> rejected) {
        if (!req.containsKey(field)) return null;
        String v = req.get(field) == null ? "" : String.valueOf(req.get(field)).trim();
        if (v.isBlank()) return null;
        if (!HHMM.matcher(v).matches()) {
            rejected.add("\"" + v + "\" is not a time in HH:MM.");
            return null;
        }
        appConfig.setValue(key, v, cat, "STRING", userId);
        saved.add(key);
        return v;
    }

    /**
     * When a REAL order is sent, since it cannot be sent at the close itself.
     *
     * <p>Paper fills use the official close and ignore this entirely.
     */
    /**
     * The time the buy logic fires, per account mode.
     *
     * <p>This read through {@code appConfig} directly, so it saw only the shared
     * {@code volbreakout.real.order.time} key and no per-mode value could reach it. Nothing in
     * {@code saveSettings} wrote that key either, which meant the scheduled buy time could not be
     * configured from the application at all - the Automation screen's "trading start" is a
     * window gate, not the trigger.
     *
     * <p>It now honours {@code volbreakout.<mode>.buy.time} first and falls back to the original
     * key, so an existing deployment keeps the time it already has while a per-mode setting
     * becomes possible.
     */
    public String realOrderTime() {
        // The screen's Buy Time first, then a per-mode volbreakout key, then the original shared
        // key so an existing deployment keeps the time it already has.
        String screen = screenOverride("buy.time");
        if (screen != null) return screen;
        String v = cfgS("buy.time", "");
        if (v != null && !v.isBlank()) return v;
        return appConfig.getString("volbreakout.real.order.time", "15:25");
    }

    /** The time the sell sweep fires, per account mode. */
    public String sellTime() { return screenTime("sell.time", cfgS("sell.time", "15:25")); }

    /**
     * The share of the account's money automation may deploy, as a percentage.
     *
     * <p>100 means the investment amount alone governs it. Below that, the amount is capped at
     * this fraction of the balance, so "deploy 500,000" cannot quietly commit everything in an
     * account that holds 520,000.
     */
    public double maxCapitalUtilizationPct() {
        double v = cfgD("max.capital.utilization.pct", 100);
        return v <= 0 || v > 100 ? 100 : v;
    }

    // ======================================================================== BUY

    /**
     * Open positions in the day's finalized shortlist.
     *
     * <p>Driven off {@code volbreakout_shortlist} rather than off a fresh screen run, so the
     * orders match the list that was frozen and published. Re-running the screen at order time
     * could select different stocks from the ones the user saw, which is the whole thing the
     * snapshot exists to prevent.
     */
    public Map<String, Object> autoBuy(Long userId, Long institutionId, boolean dryRun) {
        Map<String, Object> out = new LinkedHashMap<>();
        long uid = userId == null ? 1L : userId;
        long inst = institutionId == null ? 1L : institutionId;
        String today = DateUtil.today();
        String realMode = "REAL".equalsIgnoreCase(mode()) ? "REAL" : "PAPER";

        var verdict = AutoTradeGuard.evaluate(
            tracker.isVolumeBreakoutEnabled(), isAutoBuyEnabled(), tracker.isTradingSessionLive(),
            "REAL".equals(realMode), realTradingAllowed(uid), false, brokerReady(uid),
            openPositionCount(uid), maxOpenPositions(),
            todayRealizedLoss(uid), maxDailyLoss(),
            ordersPlacedToday(uid, today), maxDailyTrades());

        // The gate is evaluated by the order path itself, immediately before the order reaches the
        // broker, which is where it belongs. So for REAL this guard deliberately passes
        // gatePassed=false and will refuse here - automated real orders are routed through the
        // ordinary submit endpoint rather than placed from this service, and that endpoint runs
        // TradingGateClient. See the note on the response.
        if (!verdict.allowed()) {
            out.put("success", false);
            out.put("placed", 0);
            out.put("orders", List.of());
            out.put("blockedBy", verdict.blockedBy());
            out.put("message", "No orders placed. " + verdict.summary());
            return out;
        }

        List<Object[]> rows = finalizedForDate(uid, inst, today);
        if (rows.isEmpty()) {
            out.put("success", true);
            out.put("placed", 0);
            out.put("orders", List.of());
            out.put("message", "Nothing finalized for " + today + " yet, so there is nothing to buy.");
            return out;
        }

        // Size against the entry price the snapshot recorded where it has one, falling back to the
        // live price. Allocation is recomputed here rather than reused from a screen: a quantity
        // sized against a price from whenever someone last looked can overshoot available capital.
        List<String> symbols = new ArrayList<>();
        for (Object[] r : rows) symbols.add(str(r[1]));
        Map<String, Double> prices = tracker.executablePrices(symbols);
        Map<String, Double> priced = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String sym = str(r[1]);
            double snapshotEntry = num(r[2]);
            double live = prices.getOrDefault(tracker.normalizeSymbol(sym), 0.0);
            priced.put(sym, snapshotEntry > 0 ? snapshotEntry : live);
        }
        // Maximum capital utilisation, applied before sizing rather than after. The investment
        // amount is a figure someone typed; this is the ceiling the account itself imposes, so an
        // amount at or above the whole balance cannot quietly commit all of it.
        double funds = availableFunds(uid);
        double utilPct = maxCapitalUtilizationPct();
        double budget = investmentAmount();
        if (utilPct < 100 && funds > 0) {
            double ceiling = round(funds * utilPct / 100.0);
            if (budget > ceiling) {
                log.info("[VolBreakout] Investment {} capped to {} by the {}% utilisation limit.",
                         budget, ceiling, utilPct);
                budget = ceiling;
            }
        }
        if (budget <= 0) {
            out.put("success", false);
            out.put("placed", 0);
            out.put("orders", List.of());
            out.put("message", "No orders placed. The capital utilisation limit of " + utilPct
                + "% leaves nothing to deploy against an available balance of " + funds + ".");
            return out;
        }

        Map<String, Object> alloc = allocation.allocate(budget, funds,
            priced, BrokerageCalculator.Product.DELIVERY, "NSE");
        if (Boolean.FALSE.equals(alloc.get("success"))) {
            out.put("success", false);
            out.put("placed", 0);
            out.put("orders", List.of());
            out.put("message", "No orders placed. " + alloc.get("message"));
            return out;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> legs =
            (List<Map<String, Object>>) alloc.getOrDefault("legs", List.of());

        List<Map<String, Object>> placed = new ArrayList<>();
        int count = 0;
        for (Map<String, Object> leg : legs) {
            String symbol = String.valueOf(leg.get("symbol"));
            int qty = (int) num(leg.get("quantity"));
            double price = num(leg.get("price"));
            if (qty <= 0 || price <= 0) {
                placed.add(result(symbol, 0, price, "skipped", String.valueOf(leg.get("note"))));
                continue;
            }
            long signalId = recordSignal(uid, inst, today, symbol, "BUY", "ENTRY", price);
            String idem = BrokerOrderStatus.idempotencyKey(uid, STRATEGY, symbol, "BUY", today, signalId);

            if (dryRun) {
                placed.add(result(symbol, qty, price, "dry-run",
                    "Would place a " + realMode + " BUY for " + qty + " at about " + price + "."));
                continue;
            }
            if ("REAL".equals(realMode)) {
                // Not placed from here. A real order must pass TradingGateClient immediately
                // before it reaches the broker, and that lives on the submit endpoint. Automated
                // real trading therefore records the signal and reports it as awaiting submission
                // rather than reaching around the gate - which is the one thing an automated
                // path must never do.
                placed.add(result(symbol, qty, price, "awaiting-submission",
                    "Signal recorded. A REAL order is not placed from the scheduler: it must pass "
                  + "the pre-trade compliance gate on submission. Submit it from the screen, or "
                  + "switch volbreakout.mode to PAPER for fully automated simulation."));
                continue;
            }
            // The scheduled engine placed this one, so it is a System trade.
            boolean ok = openPaperPosition(uid, inst, symbol, str(leg.get("sector")), qty, price,
                                           today, idem, signalId, "AUTO");
            if (ok) {
                count++;
                markSignalActed(signalId);
                placed.add(result(symbol, qty, price, "placed",
                    "Paper position opened at " + price + "."));
            } else {
                placed.add(result(symbol, qty, price, "duplicate",
                    "Already opened for this signal today, so nothing was placed again."));
            }
        }

        out.put("success", true);
        out.put("mode", realMode);
        out.put("placed", count);
        out.put("orders", placed);
        out.put("dryRun", dryRun);
        out.put("message", (dryRun ? "Dry run: " : "") + count + " position(s) opened of "
            + legs.size() + " in the finalized shortlist.");
        if (count > 0) log.info("[VolBreakout] Auto BUY opened {} paper position(s)", count);
        return out;
    }

    /**
     * Open positions in the stocks the user ticked, for the amount they entered.
     *
     * <p>Section 6's manual buy. Distinct from {@link #autoBuy} in what drives it - a person
     * choosing, rather than the finalized shortlist - and identical in everything that matters:
     * the same allocation, the same signal record, the same idempotency key, the same refusal to
     * place a REAL order from anywhere but the gated order path.
     *
     * @param confirmed must be true. An endpoint that opens positions should not do so because
     *                  something issued a bare POST; the caller has to say it meant it.
     */
    public Map<String, Object> placeManual(Long userId, Long institutionId, List<String> symbols,
                                           double totalInvestment, String requestedMode,
                                           boolean confirmed) {
        Map<String, Object> out = new LinkedHashMap<>();
        long uid = userId == null ? 1L : userId;
        long inst = institutionId == null ? 1L : institutionId;
        String mode = "REAL".equalsIgnoreCase(requestedMode) ? "REAL" : "PAPER";
        String today = DateUtil.today();

        if (!confirmed) {
            out.put("success", false);
            out.put("placed", 0);
            out.put("message", "Confirm before placing. Nothing was done.");
            return out;
        }
        if (symbols == null || symbols.isEmpty()) {
            out.put("success", false);
            out.put("placed", 0);
            out.put("message", "Select at least one stock.");
            return out;
        }
        if (totalInvestment <= 0) {
            out.put("success", false);
            out.put("placed", 0);
            out.put("message", "Enter a total investment amount above zero.");
            return out;
        }
        if ("REAL".equals(mode)) {
            // Deliberate and not a limitation to work around: a real order must pass the
            // pre-trade compliance gate immediately before it reaches the broker, and that runs
            // on the submit endpoint. A screener placing real orders directly would be reaching
            // around the one check that exists to stop it.
            out.put("success", false);
            out.put("placed", 0);
            out.put("message", "Real orders are not placed from this screen. They must pass the "
                + "pre-trade compliance gate on submission - use the Trading screen, where the "
                + "broker, margin and risk checks run.");
            return out;
        }

        // Priced and allocated here, at the moment of the order - not reused from whatever the
        // screen last calculated. Between ticking a box and pressing the button the price moves,
        // and a quantity sized against the older one can overshoot the available capital.
        Map<String, Double> priced = new LinkedHashMap<>();
        Map<String, Double> latest = tracker.executablePrices(symbols);
        for (String sym : symbols) {
            String u = sym == null ? "" : sym.trim().toUpperCase(Locale.ROOT);
            if (!u.isBlank()) priced.put(u, latest.getOrDefault(tracker.normalizeSymbol(u), 0.0));
        }
        Map<String, Object> alloc = allocation.allocate(totalInvestment, availableFunds(uid),
            priced, BrokerageCalculator.Product.DELIVERY, "NSE");
        if (Boolean.FALSE.equals(alloc.get("success"))) {
            out.put("success", false);
            out.put("placed", 0);
            out.put("allocation", alloc);
            out.put("message", String.valueOf(alloc.get("message")));
            return out;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> legs =
            (List<Map<String, Object>>) alloc.getOrDefault("legs", List.of());
        List<Map<String, Object>> results = new ArrayList<>();
        int placed = 0;
        for (Map<String, Object> leg : legs) {
            String symbol = String.valueOf(leg.get("symbol"));
            int qty = (int) num(leg.get("quantity"));
            double price = num(leg.get("price"));
            if (qty <= 0 || price <= 0) {
                results.add(result(symbol, 0, price, "skipped", String.valueOf(leg.get("note"))));
                continue;
            }
            long signalId = recordSignal(uid, inst, today, symbol, "BUY", "MANUAL", price);
            String idem = BrokerOrderStatus.idempotencyKey(uid, STRATEGY, symbol, "BUY", today, signalId);
            // A person asked for this one through the application, so it is a Manual trade - the
            // signal above is already recorded as MANUAL and the position must agree with it.
            boolean ok = openPaperPosition(uid, inst, symbol, "", qty, price, today, idem,
                                           signalId, "MANUAL");
            if (ok) {
                placed++;
                markSignalActed(signalId);
                results.add(result(symbol, qty, price, "placed",
                    "Paper position opened: " + qty + " at " + price + "."));
            } else {
                // The unique index refused it, which means this exact order already exists today.
                results.add(result(symbol, qty, price, "duplicate",
                    "Already opened for this signal today. Nothing placed again."));
            }
        }

        out.put("success", true);
        out.put("mode", mode);
        out.put("placed", placed);
        out.put("orders", results);
        out.put("allocation", alloc);
        out.put("message", placed + " paper position(s) opened of " + legs.size()
            + " selected. Deployed " + alloc.get("deployedCapital")
            + ", estimated round-trip charges " + alloc.get("estimatedRoundTripCharges") + ".");
        if (placed > 0) {
            log.info("[VolBreakout] Manual buy opened {} paper position(s) for user {}", placed, uid);
        }
        return out;
    }

    /**
     * Close open paper positions in the named stocks, at the current price.
     *
     * <p>Section 6's manual sell. The exit price is read at the moment of the sell rather than
     * taken from the caller: a price that arrived with the request is a price from whenever that
     * screen last painted, and booking an exit at it would put a figure in the journal that was
     * never available to trade at.
     *
     * <p>Charges and net P&amp;L are computed on the round trip, so what lands in the journal is
     * what the position actually returned rather than its gross move.
     */
    public Map<String, Object> sellManual(Long userId, List<String> symbols, String requestedMode,
                                          boolean confirmed) {
        Map<String, Object> out = new LinkedHashMap<>();
        long uid = userId == null ? 1L : userId;
        List<Map<String, Object>> results = new ArrayList<>();
        if (!confirmed) {
            out.put("success", false);
            out.put("closed", 0);
            out.put("message", "Confirm before selling. Nothing was done.");
            return out;
        }
        if ("REAL".equalsIgnoreCase(requestedMode)) {
            out.put("success", false);
            out.put("closed", 0);
            out.put("message", "Real exits are not placed from this screen. They must pass the "
                + "pre-trade compliance gate on submission - use the Trading screen.");
            return out;
        }

        try {
            // Every open position when nothing is named, so "Sell" with no selection means
            // "close what is open" rather than silently doing nothing.
            List<String> wanted = new ArrayList<>();
            if (symbols != null) {
                for (String s : symbols) {
                    String u = s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
                    if (!u.isBlank() && !wanted.contains(u)) wanted.add(u);
                }
            }
            @SuppressWarnings("unchecked")
            List<Object[]> open = em.createNativeQuery(
                "SELECT id, symbol, qty, entry_price, COALESCE(total_charges,0) "
              + "FROM paper_trades WHERE user_id=? AND status='open' "
              + "AND strategy_key=? ORDER BY id DESC")
                .setParameter(1, uid).setParameter(2, STRATEGY).getResultList();
            if (open.isEmpty()) {
                out.put("success", false);
                out.put("closed", 0);
                out.put("message", "No open positions for this strategy to sell.");
                return out;
            }

            List<String> syms = new ArrayList<>();
            for (Object[] r : open) syms.add(str(r[1]));
            Map<String, Double> prices = tracker.executablePrices(syms);

            int closed = 0;
            for (Object[] r : open) {
                String symbol = str(r[1]);
                if (!wanted.isEmpty() && !wanted.contains(symbol.toUpperCase(Locale.ROOT))) continue;
                long id = (long) num(r[0]);
                int qty = (int) num(r[2]);
                double entry = num(r[3]);
                double price = prices.getOrDefault(tracker.normalizeSymbol(symbol), 0.0);
                if (price <= 0 || entry <= 0 || qty <= 0) {
                    results.add(result(symbol, qty, price, "skipped",
                        "No price available, so the exit was not booked at a guess."));
                    continue;
                }
                double gross = round((price - entry) * qty);
                var c = charges.roundTrip(BrokerageCalculator.Product.DELIVERY, "NSE",
                                          entry, price, qty);
                double net = round(gross - c.total());
                double netPct = entry * qty > 0 ? round(net / (entry * qty) * 100) : 0;

                final long fid = id;
                final double fprice = price, fgross = gross, fnet = net, fpct = netPct;
                final double fcharges = c.total();
                tx.executeWithoutResult(s -> em.createNativeQuery(
                    "UPDATE paper_trades SET exit_price=?, exit_date=?, exit_type='MANUAL', "
                  + "exit_reason='MANUAL', gross_pnl=?, total_charges=?, pnl=?, pnl_pct=?, "
                  + "status='closed' WHERE id=?")
                    .setParameter(1, fprice).setParameter(2, DateUtil.today())
                    .setParameter(3, fgross).setParameter(4, fcharges)
                    .setParameter(5, fnet).setParameter(6, fpct)
                    .setParameter(7, fid).executeUpdate());

                recordSignal(uid, 1L, DateUtil.today(), symbol, "SELL", "MANUAL", price);
                closed++;
                results.add(result(symbol, qty, price, "closed",
                    "Closed at " + price + ": gross " + gross + ", charges " + c.total()
                  + ", net " + net + " (" + netPct + "%)."));
            }

            out.put("success", closed > 0);
            out.put("closed", closed);
            out.put("orders", results);
            out.put("message", closed > 0
                ? closed + " position(s) closed."
                : "Nothing closed - none of the selected stocks has an open position.");
            if (closed > 0) log.info("[VolBreakout] Manual sell closed {} position(s)", closed);
        } catch (Exception e) {
            log.warn("[VolBreakout] Manual sell failed: {}", e.getMessage());
            out.put("success", false);
            out.put("closed", 0);
            out.put("message", "Could not sell: " + e.getMessage());
        }
        return out;
    }

    // ======================================================================= SELL

    /**
     * Close positions whose holding period is up.
     *
     * <p>Exit decisions - target, stop, trailing stop, time - belong to
     * {@link PositionMonitorService}, which sweeps every minute. This is the scheduled nudge that
     * runs the sweep at the configured sell time, so the close-to-close time exit lands at the
     * right moment rather than up to a minute late.
     */
    public Map<String, Object> autoSell(Long userId, PositionMonitorService monitor, boolean dryRun) {
        Map<String, Object> out = new LinkedHashMap<>();
        long uid = userId == null ? 1L : userId;
        String realMode = "REAL".equalsIgnoreCase(mode()) ? "REAL" : "PAPER";

        var verdict = AutoTradeGuard.evaluate(
            tracker.isVolumeBreakoutEnabled(), isAutoSellEnabled(), tracker.isTradingSessionLive(),
            "REAL".equals(realMode), realTradingAllowed(uid), false, brokerReady(uid),
            // Position and trade-count limits cap how much RISK is taken on, so they must not
            // prevent risk being taken off. An exit blocked by "too many open positions" would be
            // the limit trapping the user in exactly the situation it exists to avoid.
            0, 0, todayRealizedLoss(uid), 0, 0, 0);
        if (!verdict.allowed()) {
            out.put("success", false);
            out.put("closed", List.of());
            out.put("blockedBy", verdict.blockedBy());
            out.put("message", "No positions closed. " + verdict.summary());
            return out;
        }
        if (dryRun) {
            out.put("success", true);
            out.put("closed", List.of());
            out.put("dryRun", true);
            out.put("message", "Dry run: the exit sweep would run now.");
            return out;
        }
        if ("REAL".equals(realMode)) {
            out.put("success", false);
            out.put("closed", List.of());
            out.put("message", "Automated REAL exits are not placed from the scheduler. A real "
                + "exit must pass the compliance gate on submission, and a real stop belongs at "
                + "the broker as a resting order rather than in this process.");
            return out;
        }
        Map<String, Object> swept = monitor.sweep(uid);
        out.put("success", true);
        out.put("closed", swept.get("closed"));
        out.put("examined", swept.get("examined"));
        out.put("message", "Exit sweep complete.");
        return out;
    }

    // ==================================================================== helpers

    @SuppressWarnings("unchecked")
    private List<Object[]> finalizedForDate(long userId, long institutionId, String date) {
        try {
            return em.createNativeQuery(
                "SELECT rank,symbol,COALESCE(entry_price,0),COALESCE(sector,'') "
              + "FROM volbreakout_shortlist WHERE user_id=? AND institution_id=? AND trade_date=? "
              + "ORDER BY rank ASC")
                .setParameter(1, userId).setParameter(2, institutionId).setParameter(3, date)
                .getResultList();
        } catch (Exception e) {
            log.debug("[VolBreakout] Finalized lookup skipped: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Record the signal, separately from any order.
     *
     * <p>Section 5 requires a signal and an order to be distinct objects, and the unique index on
     * {@code strategy_signals} makes one signal per strategy, stock, side and rule per day. A
     * second call for the same signal returns the existing id rather than creating another, which
     * is what keeps the idempotency key - built from that id - stable across retries.
     */
    private long recordSignal(long userId, long institutionId, String date, String symbol,
                              String side, String rule, double price) {
        try {
            tx.executeWithoutResult(s -> em.createNativeQuery(
                "INSERT INTO strategy_signals(user_id,institution_id,strategy_key,trade_date,symbol,"
              + "side,trigger_rule,signal_at,reference_price,acted_on,created_at) "
              + "VALUES(?,?,?,?,?,?,?,?,?,0,?) ON CONFLICT DO NOTHING")
                .setParameter(1, userId).setParameter(2, institutionId).setParameter(3, STRATEGY)
                .setParameter(4, date).setParameter(5, symbol).setParameter(6, side)
                .setParameter(7, rule).setParameter(8, DateUtil.now()).setParameter(9, price)
                .setParameter(10, DateUtil.now())
                .executeUpdate());
            Object id = em.createNativeQuery(
                "SELECT id FROM strategy_signals WHERE user_id=? AND strategy_key=? AND trade_date=? "
              + "AND symbol=? AND side=? AND trigger_rule=?")
                .setParameter(1, userId).setParameter(2, STRATEGY).setParameter(3, date)
                .setParameter(4, symbol).setParameter(5, side).setParameter(6, rule)
                .getSingleResult();
            return id instanceof Number n ? n.longValue() : 0;
        } catch (Exception e) {
            log.debug("[VolBreakout] Signal record skipped for {}: {}", symbol, e.getMessage());
            return 0;
        }
    }

    private void markSignalActed(long signalId) {
        if (signalId <= 0) return;
        try {
            tx.executeWithoutResult(s -> em.createNativeQuery(
                "UPDATE strategy_signals SET acted_on=1 WHERE id=?")
                .setParameter(1, signalId).executeUpdate());
        } catch (Exception ignore) { }
    }

    /**
     * @return false when the unique index refused it, which means this position already exists.
     *
     * <p>The duplicate check is the database's, not a prior SELECT. Two scheduler firings a
     * millisecond apart both pass a check-then-insert and both open a position; only the index
     * refuses the second.
     */
    /**
     * Open a simulated position.
     *
     * @param origin who placed it: {@code AUTO} for the scheduled engine, {@code MANUAL} for a
     *        person acting through the application. It was hardcoded to {@code AUTO}, and this
     *        method is called from BOTH paths - so a paper trade a user placed by hand was
     *        recorded as automation's work. The specification is explicit that a manually placed
     *        order must not be classified as a System Trade, and the journal derives exactly that
     *        classification from this column. The signal was already being recorded with the right
     *        origin a few lines earlier, which is what made the mismatch invisible.
     */
    private boolean openPaperPosition(long userId, long institutionId, String symbol, String sector,
                                      int qty, double price, String date, String idemKey,
                                      long signalId, String origin) {
        try {
            String tradeId = "VB-" + userId + "-" + date.replace("-", "") + "-" + symbol;
            double stop = stopLossPct() > 0 ? round(price * (1 - stopLossPct() / 100.0)) : 0;
            double target = targetPct() > 0 ? round(price * (1 + targetPct() / 100.0)) : 0;
            var leg = charges.charges(BrokerageCalculator.Product.DELIVERY,
                                      BrokerageCalculator.Side.BUY, "NSE", price, qty, false);
            final int[] inserted = {0};
            tx.executeWithoutResult(s -> inserted[0] = em.createNativeQuery(
                "INSERT INTO paper_trades(trade_id,user_id,institution_id,trade_type,exchange,symbol,"
              + "sector,segment,qty,entry_price,entry_date,stop_loss,stop_loss_pct,target,target_pct,"
              // source moved to the end of the list rather than renumbering every parameter
              // after it: the column order in an INSERT is free, and shifting five positional
              // parameters by one is exactly the kind of edit that silently writes the signal id
              // into the charges column.
              + "trailing_sl_level,status,strategy_key,signal_id,idempotency_key,"
              + "high_water_mark,total_charges,source,automation_config) "
              + "VALUES(?,?,?,'PAPER','NSE',?,?,'EQ-SWG',?,?,?,?,?,?,?,?,'open',?,?,?,?,?,?,?) "
              // The index predicate has to be repeated here. ux_pt_idem is PARTIAL
              // (WHERE idempotency_key IS NOT NULL), and PostgreSQL will not infer a partial
              // index from a bare ON CONFLICT (col) - it raises "there is no unique or exclusion
              // constraint matching the ON CONFLICT specification". That would have thrown on
              // every auto-buy insert, been swallowed by the catch below, logged as a warning and
              // silently placed nothing. Verified against the live database.
              + "ON CONFLICT (idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING")
                .setParameter(1, tradeId).setParameter(2, userId).setParameter(3, institutionId)
                .setParameter(4, symbol).setParameter(5, sector)
                .setParameter(6, qty).setParameter(7, price).setParameter(8, date)
                .setParameter(9, stop).setParameter(10, stopLossPct())
                .setParameter(11, target).setParameter(12, targetPct())
                .setParameter(13, trailingStopPct())
                .setParameter(14, STRATEGY).setParameter(15, signalId).setParameter(16, idemKey)
                // Seeded at the entry price so the trailing stop has a sane base from the first
                // tick rather than trailing from zero.
                .setParameter(17, price)
                .setParameter(18, leg.total())
                .setParameter(19, "MANUAL".equalsIgnoreCase(origin) ? "MANUAL" : "AUTO")
                // Only for the scheduled engine. A manual simulation has no automation
                // configuration, and NULL says that rather than implying settings that
                // played no part in it.
                .setParameter(20, "MANUAL".equalsIgnoreCase(origin)
                    ? null : automationConfigJson())
                .executeUpdate());
            return inserted[0] > 0;
        } catch (Exception e) {
            log.warn("[VolBreakout] Could not open paper position for {}: {}", symbol, e.getMessage());
            return false;
        }
    }

    public boolean realTradingAllowed(long userId) {
        return count("SELECT COUNT(*) FROM user_trading_permissions WHERE user_id=" + userId
            + " AND real_trading_enabled=1") > 0;
    }

    public boolean brokerReady(long userId) {
        return count("SELECT COUNT(*) FROM broker_credentials WHERE user_id=" + userId
            + " AND is_active=1 AND is_verified=1 AND COALESCE(read_only,1)=0") > 0;
    }

    private int openPositionCount(long userId) {
        return (int) count("SELECT COUNT(*) FROM paper_trades WHERE user_id=" + userId
            + " AND strategy_key='" + STRATEGY + "' AND status='open'");
    }

    private int ordersPlacedToday(long userId, String date) {
        return (int) count("SELECT COUNT(*) FROM paper_trades WHERE user_id=" + userId
            + " AND strategy_key='" + STRATEGY + "' AND entry_date='" + date + "'");
    }

    /** Today's realised loss as a positive number, or zero when flat or up. */
    private double todayRealizedLoss(long userId) {
        double pnl = amount("SELECT COALESCE(SUM(pnl),0) FROM paper_trades WHERE user_id=" + userId
            + " AND status='closed' AND exit_date='" + DateUtil.today() + "'");
        return pnl < 0 ? Math.abs(pnl) : 0;
    }

    private double availableFunds(long userId) {
        double configured = appConfig.getDouble("volbreakout.available.funds", 0);
        if (configured > 0) return configured;
        // Fall back to the investment amount rather than to zero: a zero would block every order
        // for a user who has not configured a funds figure, which is not the same as having no
        // money. Real-money paths read the broker's own margin instead.
        return investmentAmount();
    }

    private long count(String sql) {
        try {
            Object n = em.createNativeQuery(sql).getSingleResult();
            return n instanceof Number num ? num.longValue() : 0;
        } catch (Exception e) { return 0; }
    }

    private double amount(String sql) {
        try {
            Object n = em.createNativeQuery(sql).getSingleResult();
            return n instanceof Number num ? num.doubleValue() : 0;
        } catch (Exception e) { return 0; }
    }

    private static Map<String, Object> result(String symbol, int qty, double price,
                                              String status, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol", symbol);
        m.put("quantity", qty);
        m.put("price", price);
        m.put("status", status);
        m.put("detail", detail);
        return m;
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    // ================================================================== scheduling

    private volatile String lastAutoBuyKey = "";
    private volatile String lastAutoSellKey = "";

    /**
     * Per-minute tick. Config-driven times, so they change without a restart.
     *
     * <p>Idempotent twice over: a run key stops the same minute firing twice in this process, and
     * the unique indexes on {@code strategy_signals} and {@code paper_trades.idempotency_key} stop
     * anything else - including a second instance of the application.
     */
    @Scheduled(cron = "0 * 9-16 * * MON-FRI", zone = "Asia/Kolkata")
    public void autoTradeTick() {
        // Outbound calls are confined to the automation window: a trading day between 08:00
        // and 16:00 IST. Outside it - overnight, weekends, exchange holidays - this stands down.
        if (!marketCalendar.isAutomationWindowOpen()) {
            log.debug("[{}] Skipped: {}.", "AutoTrade", marketCalendar.automationWindowReason());
            return;
        }
        try {
            if (!tracker.isVolumeBreakoutEnabled()) return;
            // Automation Status: Disable means nothing is placed, whatever else is configured.
            // Checked here rather than inside autoBuy so the same switch also stops the sell tick,
            // and so the reason appears in the log once per minute rather than as silence.
            if (!automationArmed()) {
                log.trace("[VolBreakout] Automation is disabled for {} - standing down.", mode());
                return;
            }
            ZonedDateTime now = ZonedDateTime.now(IST);
            if (tracker.isMarketHoliday(now.toLocalDate())) return;
            String hhmm = String.format("%02d:%02d", now.getHour(), now.getMinute());
            String today = DateUtil.today();

            // Entry runs after finalization. In PAPER the fill uses the official close, which the
            // 15:35 price attachment supplies, so a tick at the configured order time picks up
            // whatever is available and the snapshot's own entry price once it lands.
            if (isAutoBuyEnabled() && hhmm.equals(realOrderTime()) && withinTradingWindow()) {
                String key = today + "#VB_BUY#" + hhmm;
                if (!key.equals(lastAutoBuyKey)) {
                    lastAutoBuyKey = key;
                    log.info("[VolBreakout] Auto BUY tick at {}: {}", hhmm,
                        autoBuy(1L, 1L, false).get("message"));
                }
            }
            if (isAutoSellEnabled() && hhmm.equals(sellTime()) && withinTradingWindow()) {
                String key = today + "#VB_SELL#" + hhmm;
                if (!key.equals(lastAutoSellKey)) {
                    lastAutoSellKey = key;
                    log.info("[VolBreakout] Auto SELL tick at {} - the exit sweep handles it", hhmm);
                }
            }
        } catch (Exception e) {
            log.warn("[VolBreakout] Auto trade tick failed: {}", e.getMessage());
        }
    }
}
