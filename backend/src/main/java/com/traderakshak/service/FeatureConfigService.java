package com.traderakshak.service;

// =============================================================================
// FeatureConfigService
// Admin-managed feature toggles — enable/disable any feature,
// set schedule restrictions (e.g. backtesting blocked 09:00–16:00 IST),
// set per-plan and per-role access.
//
// Used by: FeatureGuard (HTTP interceptor) + Admin UI
// =============================================================================

import com.traderakshak.entity.User;
import com.traderakshak.exception.TradeRakshakException;
import com.traderakshak.util.DateUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class FeatureConfigService {

    @PersistenceContext
    private EntityManager em;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    // ── Check if a feature is allowed for the current user & time ─────────────

    public FeatureCheckResult checkFeature(String featureKey, User user) {
        Object[] config = getConfig(featureKey);
        if (config == null) {
            // Feature not found in config — allow by default
            return FeatureCheckResult.allowed();
        }

        int isEnabled = config[3] != null ? ((Number) config[3]).intValue() : 1;

        // 1. Master ON/OFF
        if (isEnabled == 0) {
            String msg = config[10] != null ? config[10].toString()
                : "Feature '" + config[1] + "' is currently disabled by administrator.";
            int code = config[14] != null ? ((Number) config[14]).intValue() : 403;
            return FeatureCheckResult.blocked(msg, code);
        }

        // 2. Role check
        String allowedRoles = config[12] != null ? config[12].toString() : "ALL";
        if (!"ALL".equals(allowedRoles) && user != null) {
            String userType = user.getUserType() != null ? user.getUserType() : "";
            boolean roleAllowed = Arrays.stream(allowedRoles.split(","))
                .map(String::trim)
                .anyMatch(r -> r.equalsIgnoreCase(userType) || userType.contains("admin") || user.isSuperAdmin());
            if (!roleAllowed) {
                return FeatureCheckResult.blocked(
                    "You do not have the required role to use '" + config[1] + "'.", 403);
            }
        }

        // 3. Plan check
        String minPlan = config[11] != null ? config[11].toString() : "Free Trial";
        if (user != null && !planSufficient(user.getPlan(), minPlan)) {
            return FeatureCheckResult.blocked(
                "'" + config[1] + "' requires " + minPlan + " plan or higher. " +
                "Your current plan: " + user.getPlan() + ".", 403);
        }

        // 4. Schedule check
        int scheduleRestricted = config[4] != null ? ((Number) config[4]).intValue() : 0;
        if (scheduleRestricted == 1) {
            String startTime  = config[5] != null ? config[5].toString() : "16:01";
            String endTime    = config[6] != null ? config[6].toString() : "08:59";
            String allowedDays = config[7] != null ? config[7].toString() : "Mon,Tue,Wed,Thu,Fri,Sat,Sun";
            String tz         = config[8] != null ? config[8].toString() : "Asia/Kolkata";

            ZonedDateTime nowZoned = ZonedDateTime.now(ZoneId.of(tz));
            String todayRaw = nowZoned.getDayOfWeek().toString().substring(0, 3);
            // Effectively-final for lambda use — MON -> Mon
            final String todayAbbr = todayRaw.charAt(0) + todayRaw.substring(1).toLowerCase();

            boolean dayAllowed = Arrays.stream(allowedDays.split(","))
                .map(String::trim).anyMatch(d -> d.equalsIgnoreCase(todayAbbr));

            LocalTime nowTime   = nowZoned.toLocalTime();
            LocalTime tStart    = LocalTime.parse(startTime, TIME_FMT);
            LocalTime tEnd      = LocalTime.parse(endTime, TIME_FMT);

            boolean timeAllowed;
            if (tStart.isBefore(tEnd)) {
                // Normal range: e.g. 16:01 – 08:59 the NEXT day — means: allowed after 16:01 OR before 08:59
                timeAllowed = !nowTime.isBefore(tStart) || nowTime.isBefore(tEnd);
            } else {
                // Same-day range: e.g. 09:00 – 16:00 — allowed if inside
                timeAllowed = !nowTime.isBefore(tStart) && nowTime.isBefore(tEnd);
            }

            // Compute blocked window times for message
            // Backtesting: allowed 16:01–08:59, BLOCKED 09:00–16:00
            // We need the BLOCKED window = opposite of allowed
            boolean currentlyBlocked = dayAllowed && !timeAllowed;

            if (currentlyBlocked) {
                String blockedMsg = config[10] != null ? config[10].toString()
                    : "'" + config[1] + "' is not available at this time.";

                // Calculate next available time
                LocalTime blockedUntil = tEnd.isAfter(nowTime) ? tEnd : tStart;
                String availableAt = nowZoned.toLocalDate() + " " + blockedUntil + " " + tz;

                return FeatureCheckResult.scheduledBlock(blockedMsg, 423, availableAt);
            }
        }

        return FeatureCheckResult.allowed();
    }

    // ── Admin: Get all feature configs ────────────────────────────────────────

    public List<?> getAllConfigs() {
        return em.createNativeQuery("SELECT * FROM feature_configs ORDER BY feature_group, feature_name")
            .getResultList();
    }

    public Map<String, Object> getConfigMap(String featureKey) {
        Object[] cfg = getConfig(featureKey);
        if (cfg == null) return new java.util.LinkedHashMap<>();
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id",                 cfg[0]);
        m.put("featureKey",         cfg[1]);
        m.put("featureName",        cfg[2]);
        m.put("featureGroup",       cfg.length > 17 && cfg[17] != null ? cfg[17] : cfg[2]);
        m.put("isEnabled",          cfg[3]);
        m.put("scheduleRestricted", cfg[4]);
        m.put("allowedStartTime",   cfg[5]);
        m.put("allowedEndTime",     cfg[6]);
        m.put("allowedDays",        cfg[7]);
        m.put("timezone",           cfg[8]);
        m.put("blockedMessage",     cfg[10]);
        m.put("minPlan",            cfg[11]);
        m.put("allowedRoles",       cfg[12]);
        m.put("httpStatus",         cfg[14]);
        m.put("description",        cfg[15]);
        m.put("updatedAt",          cfg.length > 17 ? cfg[17] : null);
        return m;
    }

    // ── Admin: Update a feature config ────────────────────────────────────────

    @Transactional
    public void updateConfig(String featureKey, Map<String, Object> req, User admin) {
        Object[] existing = getConfig(featureKey);
        if (existing == null)
            throw new TradeRakshakException("CFG-404", "Feature config not found: " + featureKey);

        em.createNativeQuery(
            "UPDATE feature_configs SET " +
            "is_enabled=:enabled, " +
            "schedule_restricted=:schedRestr, " +
            "allowed_start_time=:startTime, " +
            "allowed_end_time=:endTime, " +
            "allowed_days=:days, " +
            "timezone=:tz, " +
            "blocked_message=:msg, " +
            "min_plan=:plan, " +
            "allowed_roles=:roles, " +
            "http_status=:status, " +
            "updated_by=:by, " +
            "updated_at=:at " +
            "WHERE feature_key=:key")
            .setParameter("enabled",   getInt(req, "isEnabled", ((Number)existing[3]).intValue()))
            .setParameter("schedRestr",getInt(req, "scheduleRestricted", existing[4] != null ? ((Number)existing[4]).intValue() : 0))
            .setParameter("startTime", req.getOrDefault("allowedStartTime", existing[5]))
            .setParameter("endTime",   req.getOrDefault("allowedEndTime",   existing[6]))
            .setParameter("days",      req.getOrDefault("allowedDays",      existing[7]))
            .setParameter("tz",        req.getOrDefault("timezone",         existing[8] != null ? existing[8] : "Asia/Kolkata"))
            .setParameter("msg",       req.getOrDefault("blockedMessage",   existing[10]))
            .setParameter("plan",      req.getOrDefault("minPlan",          existing[11]))
            .setParameter("roles",     req.getOrDefault("allowedRoles",     existing[12]))
            .setParameter("status",    getInt(req, "httpStatus", existing[14] != null ? ((Number)existing[14]).intValue() : 403))
            .setParameter("by",        admin.getId())
            .setParameter("at",        DateUtil.now())
            .setParameter("key",       featureKey)
            .executeUpdate();

        log.info("Feature config updated: {} by admin {}", featureKey, admin.getTrUserId());

        // Write audit log
        try {
            em.createNativeQuery(
                "INSERT INTO activity_logs(user_id,module,action,status,detail,created_at) VALUES(?,?,?,?,?,?)")
                .setParameter(1, admin.getId()).setParameter(2, "FeatureConfig")
                .setParameter(3, "Update").setParameter(4, "success")
                .setParameter(5, featureKey + " updated: enabled=" + getInt(req,"isEnabled",1))
                .setParameter(6, DateUtil.now()).executeUpdate();
        } catch (Exception e) { log.debug("Audit error: {}", e.getMessage()); }
    }

    @Transactional
    public void toggleFeature(String featureKey, boolean enabled, User admin) {
        em.createNativeQuery(
            "UPDATE feature_configs SET is_enabled=:e, updated_by=:by, updated_at=:at WHERE feature_key=:key")
            .setParameter("e",   enabled ? 1 : 0)
            .setParameter("by",  admin.getId())
            .setParameter("at",  DateUtil.now())
            .setParameter("key", featureKey)
            .executeUpdate();
        log.info("Feature {} {} by admin {}", featureKey, enabled ? "ENABLED" : "DISABLED", admin.getTrUserId());
    }

    // ── Check what features are available for current user (summary) ──────────

    public Map<String, Object> getUserFeatureSummary(User user) {
        List<?> configs = em.createNativeQuery(
            "SELECT feature_key, feature_name, is_enabled, schedule_restricted, " +
            "allowed_start_time, allowed_end_time, allowed_days, timezone, " +
            "blocked_message, min_plan, http_status FROM feature_configs ORDER BY feature_key")
            .getResultList();

        Map<String, Object> result = new LinkedHashMap<>();
        for (Object o : configs) {
            Object[] cfg = (Object[]) o;
            String key = cfg[0].toString();
            FeatureCheckResult check = checkFeature(key, user);
            result.put(key, Map.of(
                "name",      cfg[1],
                "allowed",   check.isAllowed(),
                "reason",    check.getMessage() != null ? check.getMessage() : "",
                "httpCode",  check.getHttpStatus(),
                "availableAt", check.getAvailableAt() != null ? check.getAvailableAt() : ""
            ));
        }
        return result;
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private Object[] getConfig(String featureKey) {
        try {
            return (Object[]) em.createNativeQuery("SELECT * FROM feature_configs WHERE feature_key=:k")
                .setParameter("k", featureKey).getSingleResult();
        } catch (Exception e) { return null; }
    }

    private boolean planSufficient(String userPlan, String minPlan) {
        List<String> planOrder = List.of("Free Trial","Basic","Starter","Premium","Pro","Elite","Institutional","Enterprise");
        int userIdx = planOrder.indexOf(userPlan);
        int minIdx  = planOrder.indexOf(minPlan);
        return userIdx >= minIdx;
    }

    private int getInt(Map<?,?> m, String k, int def) {
        Object v = m.get(k); return v == null ? def : ((Number) v).intValue();
    }

    // ── Result class ──────────────────────────────────────────────────────────

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class FeatureCheckResult {
        private boolean allowed;
        private String  message;
        private int     httpStatus;
        private String  availableAt;

        public static FeatureCheckResult allowed() { return new FeatureCheckResult(true, null, 200, null); }
        public static FeatureCheckResult blocked(String msg, int code) { return new FeatureCheckResult(false, msg, code, null); }
        public static FeatureCheckResult scheduledBlock(String msg, int code, String availAt) { return new FeatureCheckResult(false, msg, code, availAt); }
    }
}
