package com.traderakshak.service;

import com.traderakshak.entity.ApplicationSetting;
import com.traderakshak.entity.User;
import com.traderakshak.repository.ApplicationSettingRepository;
import com.traderakshak.util.DateUtil;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AppConfigService {

    private final ApplicationSettingRepository settingRepo;

    @PersistenceContext
    private EntityManager em;

    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    private final AtomicInteger configVersion = new AtomicInteger(0);
    private volatile long lastCacheRefresh = 0;

    @PostConstruct
    public void init() {
        // No seeding here: @PostConstruct runs outside any transaction, so the
        // inserts in seedDefaults() would fail with "Executing an update/delete
        // query". Seeding happens in deferredInit(), which the schema guard calls
        // through the proxy once the table exists.
        try {
            refreshCache();
            log.info("AppConfigService initialized — {} settings cached, version {}", cache.size(), configVersion.get());
        } catch (Exception e) {
            log.info("AppConfigService init deferred — table not yet available (will retry after schema guard)");
        }
    }

    // Transactional because seedDefaults() issues native executeUpdate() calls and is
    // reached by self-invocation from here, which bypasses its own @Transactional.
    @Transactional
    public void deferredInit() {
        try {
            seedDefaults();
            refreshCache();
            log.info("AppConfigService deferred init — {} settings cached, version {}", cache.size(), configVersion.get());
        } catch (Exception e) {
            log.warn("AppConfigService deferred init failed: {}", e.getMessage());
        }
    }

    // ── Cache Operations ─────────────────────────────────────────────────────

    public void refreshCache() {
        List<ApplicationSetting> all = settingRepo.findByIsActiveOrderByCategoryAscDisplayOrderAsc(1);
        cache.clear();
        for (ApplicationSetting s : all) {
            if (s.getSettingValue() != null) {
                cache.put(s.getSettingKey(), s.getSettingValue());
            } else if (s.getDefaultValue() != null) {
                cache.put(s.getSettingKey(), s.getDefaultValue());
            }
        }
        lastCacheRefresh = System.currentTimeMillis();
    }

    public int getCacheSize() { return cache.size(); }
    public long getLastCacheRefresh() { return lastCacheRefresh; }
    public int getConfigVersion() { return configVersion.get(); }

    // ── Read Operations (from cache) ─────────────────────────────────────────

    public String getString(String key, String defaultVal) {
        return cache.getOrDefault(key, defaultVal);
    }

    public String getString(String key) {
        return cache.getOrDefault(key, "");
    }

    public int getInt(String key, int defaultVal) {
        String v = cache.get(key);
        if (v == null || v.isBlank()) return defaultVal;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return defaultVal; }
    }

    public double getDouble(String key, double defaultVal) {
        String v = cache.get(key);
        if (v == null || v.isBlank()) return defaultVal;
        try { return Double.parseDouble(v.trim()); } catch (NumberFormatException e) { return defaultVal; }
    }

    public long getLong(String key, long defaultVal) {
        String v = cache.get(key);
        if (v == null || v.isBlank()) return defaultVal;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return defaultVal; }
    }

    public boolean getBoolean(String key, boolean defaultVal) {
        String v = cache.get(key);
        if (v == null || v.isBlank()) return defaultVal;
        return "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim()) || "yes".equalsIgnoreCase(v.trim());
    }

    // ── Admin: Get all settings ──────────────────────────────────────────────

    public List<Map<String, Object>> getAllSettings() {
        return settingRepo.findByIsActiveOrderByCategoryAscDisplayOrderAsc(1)
            .stream().map(this::toMap).collect(Collectors.toList());
    }

    public List<Map<String, Object>> getSettingsByCategory(String category) {
        return settingRepo.findByCategoryAndIsActiveOrderByDisplayOrder(category, 1)
            .stream().map(this::toMap).collect(Collectors.toList());
    }

    public List<String> getCategories() {
        try {
            return em.createNativeQuery(
                "SELECT DISTINCT category FROM application_settings WHERE is_active=1 ORDER BY category")
                .getResultList();
        } catch (Exception e) { return List.of(); }
    }

    public Map<String, Object> getSettingByKey(String key) {
        return settingRepo.findBySettingKey(key).map(this::toMap).orElse(Map.of());
    }

    // ── Admin: Update setting ────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> updateSetting(String key, String newValue, User admin, String reason, String ip, String browser) {
        ApplicationSetting setting = settingRepo.findBySettingKey(key)
            .orElseThrow(() -> new RuntimeException("Setting not found: " + key));

        if (setting.getIsEditable() == 0) {
            throw new RuntimeException("Setting '" + key + "' is read-only and cannot be modified.");
        }

        String validated = validateValue(setting, newValue);

        String oldValue = setting.getSettingValue();
        int newVersion = configVersion.incrementAndGet();

        setting.setSettingValue(validated);
        setting.setConfigVersion(newVersion);
        setting.setUpdatedBy(admin.getId());
        setting.setUpdatedAt(DateUtil.now());
        settingRepo.save(setting);

        logConfigChange(key, setting.getCategory(), oldValue, validated, admin, ip, browser, reason, newVersion);

        cache.put(key, validated);

        log.info("Config updated: {}={} (was {}) by {} v{}", key, validated, oldValue, admin.getTrUserId(), newVersion);
        return toMap(setting);
    }

    /**
     * Upsert a setting by key without requiring it to pre-exist or be flagged editable.
     * Creates the row (in the given category) if missing, otherwise updates the value.
     * Refreshes the in-memory cache so getString/getBoolean see the change immediately.
     * Used by feature modules (e.g. Stock Upload automation) to persist their own flags.
     */
    @Transactional
    public void setValue(String key, String value, String category, String dataType, Long updatedBy) {
        if (key == null || key.isBlank()) return;
        String val = value == null ? "" : value;
        ApplicationSetting s = settingRepo.findBySettingKey(key).orElse(null);
        if (s == null) {
            s = new ApplicationSetting();
            s.setSettingKey(key);
            s.setCategory(category == null ? "GENERAL" : category);
            s.setSettingName(key);
            s.setDataType(dataType == null ? "STRING" : dataType);
            s.setDefaultValue(val);
            s.setIsEditable(1);
            s.setIsActive(1);
            s.setDisplayOrder(0);
            s.setCreatedBy(updatedBy);
            s.setCreatedAt(DateUtil.now());
        }
        s.setSettingValue(val);
        s.setConfigVersion(configVersion.incrementAndGet());
        s.setUpdatedBy(updatedBy);
        s.setUpdatedAt(DateUtil.now());
        settingRepo.save(s);
        cache.put(key, val);
    }

    @Transactional
    public List<Map<String, Object>> bulkUpdate(List<Map<String, Object>> updates, User admin, String ip, String browser) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> upd : updates) {
            String key = String.valueOf(upd.get("setting_key"));
            String val = String.valueOf(upd.get("setting_value"));
            String reason = upd.containsKey("reason") ? String.valueOf(upd.get("reason")) : "Bulk update";
            try {
                results.add(updateSetting(key, val, admin, reason, ip, browser));
            } catch (Exception e) {
                results.add(Map.of("setting_key", key, "error", e.getMessage()));
            }
        }
        return results;
    }

    // ── Admin: Reset ─────────────────────────────────────────────────────────

    @Transactional
    public int resetCategory(String category, User admin, String ip) {
        List<ApplicationSetting> settings = settingRepo.findByCategoryAndIsActiveOrderByDisplayOrder(category, 1);
        int count = 0;
        for (ApplicationSetting s : settings) {
            if (s.getDefaultValue() != null && !s.getDefaultValue().equals(s.getSettingValue())) {
                String old = s.getSettingValue();
                s.setSettingValue(s.getDefaultValue());
                s.setConfigVersion(configVersion.incrementAndGet());
                s.setUpdatedBy(admin.getId());
                s.setUpdatedAt(DateUtil.now());
                settingRepo.save(s);
                logConfigChange(s.getSettingKey(), category, old, s.getDefaultValue(), admin, ip, null, "Reset to default", s.getConfigVersion());
                count++;
            }
        }
        refreshCache();
        return count;
    }

    @Transactional
    public int resetAll(User admin, String ip) {
        List<ApplicationSetting> all = settingRepo.findByIsActiveOrderByCategoryAscDisplayOrderAsc(1);
        int count = 0;
        for (ApplicationSetting s : all) {
            if (s.getDefaultValue() != null && !s.getDefaultValue().equals(s.getSettingValue())) {
                String old = s.getSettingValue();
                s.setSettingValue(s.getDefaultValue());
                s.setConfigVersion(configVersion.incrementAndGet());
                s.setUpdatedBy(admin.getId());
                s.setUpdatedAt(DateUtil.now());
                settingRepo.save(s);
                logConfigChange(s.getSettingKey(), s.getCategory(), old, s.getDefaultValue(), admin, ip, null, "Reset all to defaults", s.getConfigVersion());
                count++;
            }
        }
        refreshCache();
        return count;
    }

    // ── Export / Import ──────────────────────────────────────────────────────

    public List<Map<String, Object>> exportSettings() {
        return settingRepo.findByIsActiveOrderByCategoryAscDisplayOrderAsc(1)
            .stream().map(s -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("category", s.getCategory());
                m.put("setting_key", s.getSettingKey());
                m.put("setting_name", s.getSettingName());
                m.put("setting_value", s.getSettingValue());
                m.put("data_type", s.getDataType());
                m.put("default_value", s.getDefaultValue());
                m.put("description", s.getDescription());
                return m;
            }).collect(Collectors.toList());
    }

    @Transactional
    public int importSettings(List<Map<String, Object>> settings, User admin, String ip) {
        int count = 0;
        for (Map<String, Object> s : settings) {
            String key = String.valueOf(s.get("setting_key"));
            String val = String.valueOf(s.get("setting_value"));
            try {
                Optional<ApplicationSetting> existing = settingRepo.findBySettingKey(key);
                if (existing.isPresent()) {
                    updateSetting(key, val, admin, "Import", ip, null);
                    count++;
                }
            } catch (Exception e) {
                log.warn("Import skip {}: {}", key, e.getMessage());
            }
        }
        refreshCache();
        return count;
    }

    // ── Audit Log ────────────────────────────────────────────────────────────

    public List<Map<String, Object>> getAuditLog(String settingKey, int limit) {
        try {
            String sql = "SELECT setting_key, category, old_value, new_value, changed_by, " +
                "changed_by_name, ip_address, browser, reason, config_version, created_at " +
                "FROM config_audit_log ";
            if (settingKey != null && !settingKey.isBlank()) {
                sql += "WHERE setting_key=:key ";
            }
            sql += "ORDER BY created_at DESC LIMIT " + Math.min(limit, 500);

            var q = em.createNativeQuery(sql);
            if (settingKey != null && !settingKey.isBlank()) q.setParameter("key", settingKey);

            List<?> rows = q.getResultList();
            return rows.stream().map(r -> {
                Object[] row = (Object[]) r;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("setting_key", row[0]);
                m.put("category", row[1]);
                m.put("old_value", row[2]);
                m.put("new_value", row[3]);
                m.put("changed_by", row[4]);
                m.put("changed_by_name", row[5]);
                m.put("ip_address", row[6]);
                m.put("browser", row[7]);
                m.put("reason", row[8]);
                m.put("config_version", row[9]);
                m.put("created_at", row[10]);
                return m;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    // ── Feature Toggle ───────────────────────────────────────────────────────

    public boolean isFeatureEnabled(String featureKey) {
        return getBoolean("feature." + featureKey, true);
    }

    public Map<String, Boolean> getAllFeatureToggles() {
        Map<String, Boolean> toggles = new LinkedHashMap<>();
        cache.forEach((k, v) -> {
            if (k.startsWith("feature.")) {
                toggles.put(k.substring(8), getBoolean(k, true));
            }
        });
        return toggles;
    }

    // ── User-Level Feature Access ────────────────────────────────────────────

    public Map<String, Object> getUserFeatureAccess(User user) {
        Map<String, Object> access = new LinkedHashMap<>();
        Map<String, Boolean> toggles = getAllFeatureToggles();

        for (Map.Entry<String, Boolean> e : toggles.entrySet()) {
            boolean allowed = e.getValue();
            String reason = "";
            if (!allowed) {
                reason = "Feature disabled by administrator";
            } else {
                String requiredPlan = getString("feature." + e.getKey() + ".min_plan", "");
                if (!requiredPlan.isEmpty() && user != null) {
                    if (!planSufficient(user.getPlan(), requiredPlan)) {
                        allowed = false;
                        reason = "Requires " + requiredPlan + " plan or higher";
                    }
                }
                String requiredRoles = getString("feature." + e.getKey() + ".roles", "ALL");
                if (!"ALL".equals(requiredRoles) && user != null) {
                    String userType = user.getUserType() != null ? user.getUserType() : "";
                    boolean roleOk = Arrays.stream(requiredRoles.split(","))
                        .map(String::trim)
                        .anyMatch(r -> r.equalsIgnoreCase(userType) || userType.contains("admin") || user.isSuperAdmin());
                    if (!roleOk) {
                        allowed = false;
                        reason = "Requires role: " + requiredRoles;
                    }
                }
            }
            access.put(e.getKey(), Map.of("allowed", allowed, "reason", reason));
        }

        try {
            List<?> overrides = em.createNativeQuery(
                "SELECT feature_key, allowed, reason FROM user_feature_access WHERE user_id=:uid")
                .setParameter("uid", user.getId()).getResultList();
            for (Object o : overrides) {
                Object[] r = (Object[]) o;
                String fk = r[0].toString();
                boolean userAllowed = ((Number) r[1]).intValue() == 1;
                access.put(fk, Map.of("allowed", userAllowed, "reason", r[2] != null ? r[2].toString() : "User override"));
            }
        } catch (Exception e) { /* table may not have data yet */ }

        return access;
    }

    // ── Private Helpers ──────────────────────────────────────────────────────

    private String validateValue(ApplicationSetting setting, String newValue) {
        if (newValue == null) return "";
        String val = newValue.trim();
        String type = setting.getDataType() != null ? setting.getDataType().toUpperCase() : "STRING";
        switch (type) {
            case "INTEGER" -> {
                try {
                    int iv = Integer.parseInt(val);
                    if (setting.getMinValue() != null && iv < Integer.parseInt(setting.getMinValue()))
                        throw new RuntimeException("Value " + iv + " is below minimum " + setting.getMinValue());
                    if (setting.getMaxValue() != null && iv > Integer.parseInt(setting.getMaxValue()))
                        throw new RuntimeException("Value " + iv + " exceeds maximum " + setting.getMaxValue());
                } catch (NumberFormatException e) { throw new RuntimeException("Invalid integer: " + val); }
            }
            case "DOUBLE", "DECIMAL" -> {
                try {
                    double dv = Double.parseDouble(val);
                    if (setting.getMinValue() != null && dv < Double.parseDouble(setting.getMinValue()))
                        throw new RuntimeException("Value " + dv + " is below minimum " + setting.getMinValue());
                    if (setting.getMaxValue() != null && dv > Double.parseDouble(setting.getMaxValue()))
                        throw new RuntimeException("Value " + dv + " exceeds maximum " + setting.getMaxValue());
                } catch (NumberFormatException e) { throw new RuntimeException("Invalid number: " + val); }
            }
            case "BOOLEAN" -> {
                if (!Set.of("true", "false", "1", "0", "yes", "no").contains(val.toLowerCase()))
                    throw new RuntimeException("Invalid boolean: " + val);
            }
        }
        return val;
    }

    private void logConfigChange(String key, String category, String oldVal, String newVal,
                                  User admin, String ip, String browser, String reason, int version) {
        try {
            em.createNativeQuery(
                "INSERT INTO config_audit_log(setting_key,category,old_value,new_value," +
                "changed_by,changed_by_name,ip_address,browser,reason,config_version,created_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?)")
                .setParameter(1, key)
                .setParameter(2, category)
                .setParameter(3, oldVal)
                .setParameter(4, newVal)
                .setParameter(5, admin.getId())
                .setParameter(6, admin.getTrUserId())
                .setParameter(7, ip)
                .setParameter(8, browser)
                .setParameter(9, reason)
                .setParameter(10, version)
                .setParameter(11, DateUtil.now())
                .executeUpdate();
        } catch (Exception e) { log.debug("Config audit log error: {}", e.getMessage()); }
    }

    private boolean planSufficient(String userPlan, String minPlan) {
        List<String> planOrder = List.of("Free Trial","Basic","Starter","Premium","Pro","Elite","Institutional","Enterprise");
        int userIdx = planOrder.indexOf(userPlan);
        int minIdx = planOrder.indexOf(minPlan);
        return userIdx >= minIdx;
    }

    private Map<String, Object> toMap(ApplicationSetting s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("category", s.getCategory());
        m.put("setting_key", s.getSettingKey());
        m.put("setting_name", s.getSettingName());
        m.put("setting_value", s.getSettingValue());
        m.put("data_type", s.getDataType());
        m.put("default_value", s.getDefaultValue());
        m.put("min_value", s.getMinValue());
        m.put("max_value", s.getMaxValue());
        m.put("description", s.getDescription());
        m.put("is_editable", s.getIsEditable());
        m.put("is_active", s.getIsActive());
        m.put("display_order", s.getDisplayOrder());
        m.put("config_version", s.getConfigVersion());
        m.put("updated_at", s.getUpdatedAt());
        return m;
    }

    // ── Seed Defaults ────────────────────────────────────────────────────────

    @Transactional
    public void seedDefaults() {
        // Probe the table: on a fresh database this runs before the schema guard
        // creates it, and init() retries the whole thing via deferredInit().
        try {
            em.createNativeQuery("SELECT COUNT(*) FROM application_settings").getSingleResult();
        } catch (Exception e) { return; }

        // Do not bail out when the table already holds rows. Every seed() below is
        // ON CONFLICT(setting_key) DO NOTHING, so re-running is free - and the schema
        // guard seeds its own STOCK_UPLOAD_AUTOMATION keys first, which used to make
        // a row-count guard suppress every default below on a fresh database.
        log.info("Seeding default application settings...");
        String now = DateUtil.now();
        int order = 0;

        // ── Stock Upload Settings ────────────────────────────────────────────
        seed("STOCK_UPLOAD", "stock_upload.eligible_min_return", "Eligible Min Return %", "0.0", "DOUBLE", "0.0", "-100", "100", "Minimum return percentage for stock eligibility", ++order, now);
        seed("STOCK_UPLOAD", "stock_upload.eligible_max_return", "Eligible Max Return %", "5.0", "DOUBLE", "5.0", "-100", "100", "Maximum return percentage for stock eligibility", ++order, now);
        seed("STOCK_UPLOAD", "stock_upload.priority_change_pct", "Priority Change %", "3.0", "DOUBLE", "3.0", "0", "50", "Priority range for stocks with 0-N% return", ++order, now);
        seed("STOCK_UPLOAD", "stock_upload.min_volume_ratio", "Min Volume Ratio", "1.0", "DOUBLE", "1.0", "0", "100", "Minimum today/avg volume ratio for eligibility", ++order, now);
        seed("STOCK_UPLOAD", "stock_upload.volume_avg_days", "Volume Average Days", "20", "INTEGER", "20", "5", "100", "Days for calculating average volume", ++order, now);

        // ── Current Top 10 Settings ──────────────────────────────────────────
        seed("CURRENT_TOP10", "top10.count", "Top Stock Count", "10", "INTEGER", "10", "1", "50", "Number of stocks in Top N ranking", ++order, now);
        seed("CURRENT_TOP10", "top10.max_per_sector", "Max Stocks Per Sector", "2", "INTEGER", "2", "1", "10", "Maximum stocks from same sector in Top N", ++order, now);
        seed("CURRENT_TOP10", "top10.refresh_interval_sec", "Refresh Interval (sec)", "300", "INTEGER", "300", "30", "3600", "Auto-refresh interval in seconds", ++order, now);
        seed("CURRENT_TOP10", "top10.auto_refresh", "Auto Refresh Enabled", "true", "BOOLEAN", "true", null, null, "Enable automatic price refresh for Top N", ++order, now);

        // ── Trading Settings ─────────────────────────────────────────────────
        seed("TRADING", "trading.market_open_time", "Market Open Time", "09:15", "STRING", "09:15", null, null, "NSE market open time (HH:MM IST)", ++order, now);
        seed("TRADING", "sr.indices", "Support/Resistance Indices", "NIFTY,BANKNIFTY,FINNIFTY,NIFTY 500", "STRING", "NIFTY,BANKNIFTY,FINNIFTY,NIFTY 500", null, null, "Comma-separated broker index symbols to publish S&R for, named as Dhan's instrument master names them (NIFTY = Nifty 50). Display names are read from the master.", ++order, now);
        seed("TRADING", "trading.market_close_time", "Market Close Time", "15:30", "STRING", "15:30", null, null, "NSE market close time (HH:MM IST)", ++order, now);
        seed("TRADING", "trading.paper_trading_allowed", "Paper Trading Allowed", "true", "BOOLEAN", "true", null, null, "Allow paper trading simulation", ++order, now);
        seed("TRADING", "trading.live_trading_allowed", "Live Trading Allowed", "false", "BOOLEAN", "false", null, null, "Allow live trading (requires broker)", ++order, now);
        seed("TRADING", "trading.testing_mode", "Testing Mode", "true", "BOOLEAN", "true", null, null, "Enable testing mode (bypasses market hours)", ++order, now);
        seed("TRADING", "trading.auto_buy", "Auto Buy Enabled", "false", "BOOLEAN", "false", null, null, "Enable automatic buy orders", ++order, now);
        seed("TRADING", "trading.auto_sell", "Auto Sell Enabled", "false", "BOOLEAN", "false", null, null, "Enable automatic sell orders", ++order, now);
        seed("TRADING", "trading.default_risk_pct", "Default Risk %", "2.0", "DOUBLE", "2.0", "0.1", "50", "Default risk percentage per trade", ++order, now);
        seed("TRADING", "trading.default_stop_loss_pct", "Default Stop Loss %", "5.0", "DOUBLE", "5.0", "0.1", "50", "Default stop loss percentage", ++order, now);
        seed("TRADING", "trading.default_target_pct", "Default Target %", "10.0", "DOUBLE", "10.0", "0.1", "100", "Default profit target percentage", ++order, now);
        seed("TRADING", "trading.default_trailing_stop_loss_pct", "Default Trailing Stop Loss %", "2.0", "DOUBLE", "2.0", "0.1", "50", "Default trailing stop loss percentage", ++order, now);

        // ── Technical Indicator Settings ──────────────────────────────────────
        seed("INDICATOR", "indicator.rsi.period", "RSI Period", "14", "INTEGER", "14", "2", "100", "Relative Strength Index calculation period", ++order, now);
        seed("INDICATOR", "indicator.rsi.overbought", "RSI Overbought", "70", "INTEGER", "70", "50", "100", "RSI overbought threshold", ++order, now);
        seed("INDICATOR", "indicator.rsi.oversold", "RSI Oversold", "30", "INTEGER", "30", "0", "50", "RSI oversold threshold", ++order, now);
        seed("INDICATOR", "indicator.ema.short_period", "EMA Short Period", "20", "INTEGER", "20", "2", "200", "Short-term EMA period", ++order, now);
        seed("INDICATOR", "indicator.ema.long_period", "EMA Long Period", "50", "INTEGER", "50", "10", "500", "Long-term EMA period", ++order, now);
        seed("INDICATOR", "indicator.ema.very_long_period", "EMA Very Long Period", "200", "INTEGER", "200", "50", "500", "Very long-term EMA/SMA period", ++order, now);
        seed("INDICATOR", "indicator.macd.fast_period", "MACD Fast Period", "12", "INTEGER", "12", "2", "100", "MACD fast EMA period", ++order, now);
        seed("INDICATOR", "indicator.macd.slow_period", "MACD Slow Period", "26", "INTEGER", "26", "5", "200", "MACD slow EMA period", ++order, now);
        seed("INDICATOR", "indicator.macd.signal_period", "MACD Signal Period", "9", "INTEGER", "9", "2", "50", "MACD signal line period", ++order, now);
        seed("INDICATOR", "indicator.adx.period", "ADX Period", "14", "INTEGER", "14", "5", "50", "Average Directional Index period", ++order, now);
        seed("INDICATOR", "indicator.adx.trend_threshold", "ADX Trend Threshold", "25", "INTEGER", "25", "10", "50", "ADX threshold for trend strength", ++order, now);
        seed("INDICATOR", "indicator.atr.period", "ATR Period", "14", "INTEGER", "14", "5", "50", "Average True Range period", ++order, now);
        seed("INDICATOR", "indicator.supertrend.period", "SuperTrend Period", "10", "INTEGER", "10", "3", "50", "SuperTrend ATR period", ++order, now);
        seed("INDICATOR", "indicator.supertrend.multiplier", "SuperTrend Multiplier", "3.0", "DOUBLE", "3.0", "1", "10", "SuperTrend ATR multiplier", ++order, now);
        seed("INDICATOR", "indicator.stochastic.k_period", "Stochastic K Period", "14", "INTEGER", "14", "5", "50", "Stochastic %K lookback period", ++order, now);
        seed("INDICATOR", "indicator.stochastic.d_period", "Stochastic D Period", "3", "INTEGER", "3", "1", "20", "Stochastic %D smoothing period", ++order, now);
        seed("INDICATOR", "indicator.volume.spike_multiplier", "Volume Spike Multiplier", "1.5", "DOUBLE", "1.5", "1.0", "10.0", "Multiplier to detect volume spikes", ++order, now);

        // ── Strategy Settings ────────────────────────────────────────────────
        seed("STRATEGY", "strategy.default_stop_loss_pct", "Default Stop Loss %", "5.0", "DOUBLE", "5.0", "0.1", "50", "Strategy default stop loss", ++order, now);
        seed("STRATEGY", "strategy.default_target_pct", "Default Target %", "10.0", "DOUBLE", "10.0", "0.1", "100", "Strategy default target", ++order, now);
        seed("STRATEGY", "strategy.default_rr_min", "Min Risk-Reward Ratio", "2.0", "DOUBLE", "2.0", "0.5", "10", "Minimum acceptable risk-reward ratio", ++order, now);
        seed("STRATEGY", "strategy.default_max_positions", "Max Positions", "5", "INTEGER", "5", "1", "50", "Maximum concurrent positions", ++order, now);
        seed("STRATEGY", "strategy.default_max_per_sector", "Max Per Sector", "2", "INTEGER", "2", "1", "10", "Maximum positions per sector", ++order, now);
        seed("STRATEGY", "strategy.default_risk_per_trade_pct", "Risk Per Trade %", "2.0", "DOUBLE", "2.0", "0.1", "20", "Risk percentage per individual trade", ++order, now);
        seed("STRATEGY", "strategy.default_holding_days", "Default Holding Days", "22", "INTEGER", "22", "1", "365", "Default holding period in trading days", ++order, now);
        seed("STRATEGY", "strategy.screener_max_results", "Screener Max Results", "500", "INTEGER", "500", "10", "5000", "Maximum results in strategy screener", ++order, now);

        // ── Backtest Settings ────────────────────────────────────────────────
        seed("BACKTEST", "backtest.symbol_limit", "Symbol Limit", "500", "INTEGER", "500", "10", "5000", "Maximum symbols in backtest", ++order, now);
        seed("BACKTEST", "backtest.default_max_positions", "Default Max Positions", "10", "INTEGER", "10", "1", "100", "Default max concurrent positions in backtest", ++order, now);
        seed("BACKTEST", "backtest.max_holding_days", "Max Holding Days", "20", "INTEGER", "20", "1", "365", "Maximum holding days for backtest trades", ++order, now);
        seed("BACKTEST", "backtest.initial_capital", "Initial Capital", "100000.0", "DOUBLE", "100000.0", "1000", "100000000", "Default starting capital for backtest", ++order, now);
        seed("BACKTEST", "backtest.trading_days_per_year", "Trading Days/Year", "252", "INTEGER", "252", "200", "365", "Trading days per year (for Sharpe ratio)", ++order, now);
        seed("BACKTEST", "backtest.monte_carlo_simulations", "Monte Carlo Simulations", "1000", "INTEGER", "1000", "100", "10000", "Default Monte Carlo simulation count", ++order, now);

        // ── Subscription Settings ────────────────────────────────────────────
        seed("SUBSCRIPTION", "subscription.monthly_price", "Monthly Price (INR)", "0", "DOUBLE", "0", "0", "100000", "Monthly subscription price", ++order, now);
        seed("SUBSCRIPTION", "subscription.quarterly_price", "Quarterly Price (INR)", "0", "DOUBLE", "0", "0", "300000", "Quarterly subscription price", ++order, now);
        seed("SUBSCRIPTION", "subscription.half_yearly_price", "Half Yearly Price (INR)", "0", "DOUBLE", "0", "0", "600000", "Half-yearly subscription price", ++order, now);
        seed("SUBSCRIPTION", "subscription.yearly_price", "Yearly Price (INR)", "0", "DOUBLE", "0", "0", "1200000", "Yearly subscription price", ++order, now);
        seed("SUBSCRIPTION", "subscription.trial_days", "Trial Days", "7", "INTEGER", "7", "0", "90", "Free trial period in days", ++order, now);
        seed("SUBSCRIPTION", "subscription.gst_pct", "GST %", "18", "DOUBLE", "18", "0", "50", "GST percentage on subscription", ++order, now);

        // ── Notification Settings ────────────────────────────────────────────
        seed("NOTIFICATION", "notification.email_enabled", "Email Notifications", "true", "BOOLEAN", "true", null, null, "Enable email notifications", ++order, now);
        seed("NOTIFICATION", "notification.sms_enabled", "SMS Notifications", "true", "BOOLEAN", "true", null, null, "Enable SMS notifications", ++order, now);
        seed("NOTIFICATION", "notification.whatsapp_enabled", "WhatsApp Notifications", "true", "BOOLEAN", "true", null, null, "Enable WhatsApp notifications", ++order, now);
        seed("NOTIFICATION", "notification.telegram_enabled", "Telegram Notifications", "true", "BOOLEAN", "true", null, null, "Enable Telegram bot notifications", ++order, now);
        seed("NOTIFICATION", "notification.push_enabled", "Push Notifications", "true", "BOOLEAN", "true", null, null, "Enable browser push notifications", ++order, now);

        // ── API Settings ─────────────────────────────────────────────────────
        seed("API", "api.rate_limit_per_minute", "API Rate Limit/min", "100", "INTEGER", "100", "10", "10000", "API requests allowed per minute", ++order, now);
        seed("API", "api.upload_rate_limit_per_minute", "Upload Rate Limit/min", "20", "INTEGER", "20", "1", "100", "File uploads allowed per minute", ++order, now);
        seed("API", "api.login_rate_limit_per_minute", "Login Rate Limit/min", "5", "INTEGER", "5", "1", "50", "Login attempts allowed per minute", ++order, now);

        // ── Broker Settings ──────────────────────────────────────────────────
        seed("BROKER", "broker.dhan.api_endpoint", "Dhan API Endpoint", "https://api.dhan.co/v2", "STRING", "https://api.dhan.co/v2", null, null, "Dhan broker API base URL", ++order, now);
        seed("BROKER", "broker.dhan.max_instruments", "Dhan Max Instruments", "5000", "INTEGER", "5000", "100", "50000", "Maximum instruments for live WebSocket feed", ++order, now);
        seed("BROKER", "broker.dhan.stale_seconds", "Dhan Stale Seconds", "45", "INTEGER", "45", "10", "600", "Seconds before price data is considered stale", ++order, now);

        // ── Data Warehouse Settings ──────────────────────────────────────────
        seed("DATA_WAREHOUSE", "data.retention_years", "Data Retention Years", "5", "INTEGER", "5", "1", "20", "Years to retain historical data", ++order, now);
        seed("DATA_WAREHOUSE", "data.max_batch_files", "Max Batch Files", "250", "INTEGER", "250", "10", "1000", "Maximum files in a batch upload", ++order, now);
        seed("DATA_WAREHOUSE", "data.backfill_stale_minutes", "Backfill Stale Minutes", "10", "INTEGER", "10", "5", "60", "Minutes after which a backfill job is stale", ++order, now);
        seed("DATA_WAREHOUSE", "data.backfill_max_retries", "Backfill Max Retries", "5", "INTEGER", "5", "1", "20", "Maximum retries for failed backfill jobs", ++order, now);

        // ── Data Fetch Engine Performance Settings ───────────────────────────
        seed("DATA_FETCH_ENGINE", "data.fetch_thread_count", "Parallel Fetch Threads", "4", "INTEGER", "4", "1", "16", "Number of parallel threads for data fetching (1=sequential)", ++order, now);
        seed("DATA_FETCH_ENGINE", "data.fetch_max_retries", "Max Retries Per URL", "3", "INTEGER", "3", "1", "10", "Maximum retry attempts per download URL", ++order, now);
        seed("DATA_FETCH_ENGINE", "data.retry_base_delay_ms", "Retry Base Delay (ms)", "2000", "INTEGER", "2000", "500", "10000", "Base delay between retries in milliseconds", ++order, now);
        seed("DATA_FETCH_ENGINE", "data.fetch_timeout_sec", "Fetch Timeout (sec)", "300", "INTEGER", "300", "60", "900", "Maximum total time for parallel fetch in seconds", ++order, now);
        seed("DATA_FETCH_ENGINE", "data.skip_existing_records", "Skip Existing Records", "true", "BOOLEAN", "true", null, null, "Skip dates with existing data (incremental fetch)", ++order, now);
        seed("DATA_FETCH_ENGINE", "data.cache_ttl_ms", "Cache TTL (ms)", "300000", "INTEGER", "300000", "60000", "1800000", "API response cache time-to-live in milliseconds", ++order, now);
        seed("DATA_FETCH_ENGINE", "data.cache_max_entries", "Cache Max Entries", "100", "INTEGER", "100", "10", "500", "Maximum cached API responses", ++order, now);
        seed("DATA_FETCH_ENGINE", "data.batch_insert_size", "Batch Insert Size", "100", "INTEGER", "100", "10", "1000", "Rows to accumulate before flushing to database", ++order, now);
        seed("DATA_FETCH_ENGINE", "data.connection_timeout_sec", "Connection Timeout (sec)", "15", "INTEGER", "15", "5", "60", "HTTP connection timeout in seconds", ++order, now);
        seed("DATA_FETCH_ENGINE", "data.rate_limit_concurrent", "Rate Limit Concurrent", "3", "INTEGER", "3", "1", "10", "Maximum concurrent HTTP requests to exchange", ++order, now);

        // ── Dhan API Rate Limit Settings ────────────────────────────────────
        seed("DHAN_API", "dhan.api_min_gap_ms", "Min Gap Between Calls (ms)", "1200", "INTEGER", "1200", "500", "5000", "Minimum milliseconds between consecutive Dhan API calls", ++order, now);
        seed("DHAN_API", "dhan.api_429_backoff_ms", "429 Backoff Base (ms)", "30000", "INTEGER", "30000", "10000", "120000", "Base backoff delay when Dhan returns 429 Too Many Requests", ++order, now);
        seed("DHAN_API", "dhan.api_max_backoff_multiplier", "Max Backoff Multiplier", "4", "INTEGER", "4", "1", "10", "Maximum multiplier for exponential backoff on repeated 429 errors", ++order, now);

        // ── User Access Settings ─────────────────────────────────────────────
        seed("USER_ACCESS", "user.max_failed_logins", "Max Failed Logins", "5", "INTEGER", "5", "1", "20", "Login failures before account lockout", ++order, now);
        seed("USER_ACCESS", "user.lockout_minutes", "Lockout Duration (min)", "30", "INTEGER", "30", "5", "1440", "Account lockout duration in minutes", ++order, now);
        seed("USER_ACCESS", "user.session_timeout_ms", "Session Timeout (ms)", "1800000", "INTEGER", "1800000", "60000", "86400000", "JWT session expiry in milliseconds", ++order, now);

        // ── System Settings ──────────────────────────────────────────────────
        seed("SYSTEM", "system.timezone", "System Timezone", "Asia/Kolkata", "STRING", "Asia/Kolkata", null, null, "Default timezone for the application", ++order, now);
        seed("SYSTEM", "system.currency", "Currency", "INR", "STRING", "INR", null, null, "Default currency code", ++order, now);
        seed("SYSTEM", "system.sebi_disclaimer_enabled", "SEBI Disclaimer Enabled", "true", "BOOLEAN", "true", null, null, "Show SEBI disclaimer on all responses", ++order, now);
        seed("SYSTEM", "system.screen_refresh_gap_ms", "Screen Refresh Gap (ms)", "45000", "INTEGER", "45000", "5000", "300000", "Minimum gap between screen refreshes", ++order, now);
        seed("SYSTEM", "system.market_open_stale_sec", "Market Open Stale (sec)", "60", "INTEGER", "60", "10", "600", "Seconds before data is stale during market hours", ++order, now);
        seed("SYSTEM", "system.market_closed_stale_sec", "Market Closed Stale (sec)", "900", "INTEGER", "900", "60", "7200", "Seconds before data is stale after market close", ++order, now);

        // ── Ranking Weights ──────────────────────────────────────────────────
        seed("RANKING", "ranking.weight.price_action", "Price Action Weight", "12.0", "DOUBLE", "12.0", "0", "100", "Weight for price action scoring", ++order, now);
        seed("RANKING", "ranking.weight.volume_strength", "Volume Strength Weight", "10.0", "DOUBLE", "10.0", "0", "100", "Weight for volume strength scoring", ++order, now);
        seed("RANKING", "ranking.weight.delivery_pct", "Delivery % Weight", "8.0", "DOUBLE", "8.0", "0", "100", "Weight for delivery percentage scoring", ++order, now);
        seed("RANKING", "ranking.weight.rsi", "RSI Weight", "10.0", "DOUBLE", "10.0", "0", "100", "Weight for RSI scoring", ++order, now);
        seed("RANKING", "ranking.weight.ema_trend", "EMA Trend Weight", "12.0", "DOUBLE", "12.0", "0", "100", "Weight for EMA trend scoring", ++order, now);
        seed("RANKING", "ranking.weight.breakout", "Breakout Weight", "10.0", "DOUBLE", "10.0", "0", "100", "Weight for breakout detection scoring", ++order, now);
        seed("RANKING", "ranking.weight.sr_proximity", "S&R Proximity Weight", "8.0", "DOUBLE", "8.0", "0", "100", "Weight for support/resistance proximity", ++order, now);
        seed("RANKING", "ranking.weight.risk_reward", "Risk-Reward Weight", "10.0", "DOUBLE", "10.0", "0", "100", "Weight for risk-reward ratio scoring", ++order, now);
        seed("RANKING", "ranking.weight.backtest_win_rate", "Backtest Win Rate Weight", "12.0", "DOUBLE", "12.0", "0", "100", "Weight for backtest win rate scoring", ++order, now);
        seed("RANKING", "ranking.weight.sector_strength", "Sector Strength Weight", "8.0", "DOUBLE", "8.0", "0", "100", "Weight for sector strength scoring", ++order, now);
        seed("RANKING", "ranking.weight.market_trend", "Market Trend Weight", "10.0", "DOUBLE", "10.0", "0", "100", "Weight for overall market trend", ++order, now);

        // ── Feature Toggles ──────────────────────────────────────────────────
        seed("FEATURE_TOGGLE", "feature.stock_upload", "Stock Upload", "true", "BOOLEAN", "true", null, null, "Enable Stock Upload module", ++order, now);
        seed("FEATURE_TOGGLE", "feature.strategy_builder", "Strategy Builder", "true", "BOOLEAN", "true", null, null, "Enable Strategy Builder module", ++order, now);
        seed("FEATURE_TOGGLE", "feature.backtesting", "Backtesting", "true", "BOOLEAN", "true", null, null, "Enable Backtesting module", ++order, now);
        seed("FEATURE_TOGGLE", "feature.paper_trading", "Paper Trading", "true", "BOOLEAN", "true", null, null, "Enable Paper Trading module", ++order, now);
        seed("FEATURE_TOGGLE", "feature.live_trading", "Live Trading", "false", "BOOLEAN", "false", null, null, "Enable Live Trading module", ++order, now);
        seed("FEATURE_TOGGLE", "feature.auto_buy", "Auto Buy", "false", "BOOLEAN", "false", null, null, "Enable automated buy orders", ++order, now);
        seed("FEATURE_TOGGLE", "feature.auto_sell", "Auto Sell", "false", "BOOLEAN", "false", null, null, "Enable automated sell orders", ++order, now);
        seed("FEATURE_TOGGLE", "feature.notifications", "Notifications", "true", "BOOLEAN", "true", null, null, "Enable notification system", ++order, now);
        seed("FEATURE_TOGGLE", "feature.data_warehouse", "Data Warehouse", "true", "BOOLEAN", "true", null, null, "Enable Data Warehouse module", ++order, now);
        seed("FEATURE_TOGGLE", "feature.ml_engine", "ML Engine", "true", "BOOLEAN", "true", null, null, "Enable ML prediction engine", ++order, now);
        seed("FEATURE_TOGGLE", "feature.sector_intelligence", "Sector Intelligence", "true", "BOOLEAN", "true", null, null, "Enable Sector AI module", ++order, now);
        seed("FEATURE_TOGGLE", "feature.stock_ranking", "Stock Ranking", "true", "BOOLEAN", "true", null, null, "Enable Stock Ranking Engine", ++order, now);

        // ── Backup Settings ──────────────────────────────────────────────────
        seed("BACKUP", "backup.auto_backup_enabled", "Auto Backup Enabled", "false", "BOOLEAN", "false", null, null, "Enable scheduled database backups", ++order, now);
        seed("BACKUP", "backup.retention_days", "Backup Retention Days", "30", "INTEGER", "30", "7", "365", "Days to retain backup files", ++order, now);

        log.info("Seeded {} default application settings.", order);
    }

    private void seed(String category, String key, String name, String value, String dataType,
                      String defaultValue, String minValue, String maxValue, String description, int order, String now) {
        try {
            em.createNativeQuery(
                "INSERT INTO application_settings(category,setting_key,setting_name,setting_value,data_type," +
                "default_value,min_value,max_value,description,is_editable,is_active,display_order,config_version,created_at,updated_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?,1,1,?,1,?,?) ON CONFLICT(setting_key) DO NOTHING")
                .setParameter(1, category)
                .setParameter(2, key)
                .setParameter(3, name)
                .setParameter(4, value)
                .setParameter(5, dataType)
                .setParameter(6, defaultValue)
                .setParameter(7, minValue)
                .setParameter(8, maxValue)
                .setParameter(9, description)
                .setParameter(10, order)
                .setParameter(11, now)
                .setParameter(12, now)
                .executeUpdate();
        } catch (Exception e) {
            log.debug("Seed skip {}: {}", key, e.getMessage());
        }
    }
}
