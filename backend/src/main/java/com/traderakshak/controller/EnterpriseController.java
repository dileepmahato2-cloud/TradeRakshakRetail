package com.traderakshak.controller;

// =============================================================================
// EnterpriseController — Phase 1 MVP enterprise modules per 7 Master Documents
// Covers: Portfolio, Enhanced Scanner, Alert Rules, Competition, Loyalty,
//         Reports, Compliance, Market Breadth, Unusual Activity, GST Invoices
// =============================================================================

import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.entity.User;
import com.traderakshak.service.PortfolioService;
import com.traderakshak.util.DateUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/enterprise")
@RequiredArgsConstructor
@Tag(name = "Enterprise Modules", description = "Portfolio, Scanner, Alerts, Competitions, Reports, Compliance")
public class EnterpriseController {

    private final PortfolioService portfolioService;
    @PersistenceContext private EntityManager em;

    // ════════════════════════════════════════════════════════════════
    // PORTFOLIO MODULE (Volume 8)
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/portfolios")
    @Operation(summary = "List my portfolios")
    public ResponseEntity<ApiResponse<List<?>>> listPortfolios(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(portfolioService.listPortfolios(user)));
    }

    @PostMapping("/portfolios")
    @Operation(summary = "Create portfolio")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createPortfolio(
            @RequestBody Map<String, Object> req, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(portfolioService.createPortfolio(user, req)));
    }

    @GetMapping("/portfolios/{id}")
    @Operation(summary = "Portfolio detail — positions, trades, P&L")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPortfolio(
            @PathVariable Long id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(portfolioService.getPortfolioDetail(id, user.getId())));
    }

    @PostMapping("/portfolios/{id}/trade")
    @Operation(summary = "Add trade to portfolio — BUY/SELL")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addTrade(
            @PathVariable Long id, @RequestBody Map<String, Object> req,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok("Trade added.", portfolioService.addTrade(user, id, req)));
    }

    @GetMapping("/portfolios/{id}/rebalance")
    @Operation(summary = "Get rebalancing suggestions — sector diversification")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rebalance(
            @PathVariable Long id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(portfolioService.getRebalanceSuggestions(id, user.getId())));
    }

    @GetMapping("/portfolios/{id}/risk")
    @Operation(summary = "Portfolio risk analysis")
    public ResponseEntity<ApiResponse<Map<String, Object>>> riskAnalysis(
            @PathVariable Long id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(portfolioService.getRiskAnalysis(id, user.getId())));
    }

    // ════════════════════════════════════════════════════════════════
    // SCANNER ENGINE (Volume 6) — Enhanced
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/scanners")
    @Operation(summary = "List my scanners + public templates")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listScanners(
            @AuthenticationPrincipal User user) {
        List<?> mine = em.createNativeQuery(
            "SELECT * FROM scanners WHERE user_id=:uid AND status='active' ORDER BY id DESC")
            .setParameter("uid", user.getId()).getResultList();
        List<?> templates = em.createNativeQuery(
            "SELECT * FROM scanners WHERE is_template=1 AND status='active' ORDER BY id LIMIT 20")
            .getResultList();
        return ResponseEntity.ok(ApiResponse.ok(Map.of("mine", mine, "templates", templates)));
    }

    @PostMapping("/scanners")
    @Operation(summary = "Create scanner")
    public ResponseEntity<ApiResponse<Void>> createScanner(
            @RequestBody ScannerRequest req, @AuthenticationPrincipal User user) {
        em.createNativeQuery(
            "INSERT INTO scanners(user_id,institution_id,name,description,scanner_type,conditions_json," +
            "logic_text,universe,timeframe,schedule,is_public,tags,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
            .setParameter(1,user.getId()).setParameter(2,user.getInstitutionId())
            .setParameter(3,req.getName()).setParameter(4,req.getDescription())
            .setParameter(5,req.getScannerType()).setParameter(6,req.getConditionsJson())
            .setParameter(7,req.getLogicText()).setParameter(8,req.getUniverse())
            .setParameter(9,req.getTimeframe()).setParameter(10,req.getSchedule())
            .setParameter(11,req.isPublic()?1:0).setParameter(12,req.getTags())
            .setParameter(13,"active").setParameter(14,DateUtil.now()).setParameter(15,DateUtil.now())
            .executeUpdate();
        return ResponseEntity.ok(ApiResponse.ok("Scanner created.", null));
    }

    @PostMapping("/scanners/{id}/run")
    @Operation(summary = "Execute scanner — returns matching symbols")
    public ResponseEntity<ApiResponse<Map<String, Object>>> runScanner(
            @PathVariable Long id, @AuthenticationPrincipal User user) {
        // Get scanner
        Object[] scanner = (Object[]) em.createNativeQuery("SELECT * FROM scanners WHERE id=:id")
            .setParameter("id", id).getSingleResult();
        // Get latest scan results from scanner_results table
        List<?> results = em.createNativeQuery(
            "SELECT sr.symbol, sr.cmp, sr.change_pct, sr.volume, sr.delivery_pct, " +
            "sr.stop_loss_price, sr.target_price, sr.rr_ratio, sr.trade_score, sr.scan_date " +
            "FROM scanner_results sr WHERE sr.strategy_id=:id ORDER BY sr.trade_score DESC LIMIT 30")
            .setParameter("id", id).getResultList();
        // Update scanner last_run
        em.createNativeQuery("UPDATE scanners SET last_run=:t, total_runs=total_runs+1 WHERE id=:id")
            .setParameter("t", DateUtil.now()).setParameter("id", id).executeUpdate();
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "scanner", scanner[2], "results", results,
            "matched", results.size(), "scanDate", DateUtil.today())));
    }

    // ════════════════════════════════════════════════════════════════
    // ALERT ENGINE (Volume 9)
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/alerts")
    @Operation(summary = "My alert rules")
    public ResponseEntity<ApiResponse<List<?>>> listAlerts(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            em.createNativeQuery("SELECT * FROM alert_rules WHERE user_id=:uid ORDER BY id DESC")
                .setParameter("uid", user.getId()).getResultList()));
    }

    @PostMapping("/alerts")
    @Operation(summary = "Create alert rule")
    public ResponseEntity<ApiResponse<Void>> createAlert(
            @RequestBody AlertRequest req, @AuthenticationPrincipal User user) {
        em.createNativeQuery(
            "INSERT INTO alert_rules(user_id,institution_id,scanner_id,strategy_id,alert_name,alert_type," +
            "priority,condition_json,universe,timeframe,channels_json,frequency,cooldown_mins,status,created_at,updated_at) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
            .setParameter(1,user.getId()).setParameter(2,user.getInstitutionId())
            .setParameter(3,req.getScannerId()).setParameter(4,req.getStrategyId())
            .setParameter(5,req.getAlertName()).setParameter(6,req.getAlertType())
            .setParameter(7,req.getPriority()).setParameter(8,req.getConditionJson())
            .setParameter(9,req.getUniverse()).setParameter(10,req.getTimeframe())
            .setParameter(11,req.getChannelsJson()).setParameter(12,req.getFrequency())
            .setParameter(13,req.getCooldownMins()).setParameter(14,"active")
            .setParameter(15,DateUtil.now()).setParameter(16,DateUtil.now()).executeUpdate();
        return ResponseEntity.ok(ApiResponse.ok("Alert rule created.", null));
    }

    @GetMapping("/alerts/notifications")
    @Operation(summary = "Alert notification history")
    public ResponseEntity<ApiResponse<List<?>>> alertNotifications(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            em.createNativeQuery(
                "SELECT an.*, ar.alert_name, ar.priority FROM alert_notifications an " +
                "JOIN alert_rules ar ON ar.id=an.alert_id WHERE an.user_id=:uid ORDER BY an.triggered_at DESC LIMIT 50")
                .setParameter("uid", user.getId()).getResultList()));
    }

    // ════════════════════════════════════════════════════════════════
    // COMPETITION MODULE (Volume 11)
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/competitions")
    @Operation(summary = "List active competitions")
    public ResponseEntity<ApiResponse<List<?>>> listCompetitions(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            em.createNativeQuery("SELECT * FROM competitions WHERE status IN ('active','upcoming') ORDER BY start_date")
                .getResultList()));
    }

    @PostMapping("/competitions/{id}/register")
    @Operation(summary = "Register for competition")
    public ResponseEntity<ApiResponse<Void>> registerCompetition(
            @PathVariable Long id, @AuthenticationPrincipal User user) {
        // Check paid subscription (per BRD rule)
        if ("free_trial".equals(user.getUserType()))
            return ResponseEntity.badRequest().body(ApiResponse.error("COMP-001",
                "Active paid subscription required to enter competitions."));

        try {
            em.createNativeQuery(
                "INSERT INTO competition_registrations(competition_id,user_id,institution_id," +
                "registered_at,entry_capital,current_value,status) " +
                "SELECT ?,?,?,?,entry_capital,entry_capital,'active' FROM competitions WHERE id=? " +
                "ON CONFLICT (competition_id, user_id) DO NOTHING")
                .setParameter(1,id).setParameter(2,user.getId()).setParameter(3,user.getInstitutionId())
                .setParameter(4,DateUtil.now()).setParameter(5,id).executeUpdate();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("COMP-002","Already registered."));
        }
        return ResponseEntity.ok(ApiResponse.ok("Registered successfully!", null));
    }

    @GetMapping("/competitions/{id}/leaderboard")
    @Operation(summary = "Competition leaderboard — live rankings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> leaderboard(
            @PathVariable Long id, @AuthenticationPrincipal User user) {
        List<?> board = em.createNativeQuery(
            "SELECT cr.rank, u.name, u.tr_user_id, cr.score, cr.current_value, " +
            "cr.win_rate, cr.max_drawdown, cr.total_trades " +
            "FROM competition_registrations cr JOIN users u ON u.id=cr.user_id " +
            "WHERE cr.competition_id=:id ORDER BY cr.score DESC, cr.current_value DESC LIMIT 50")
            .setParameter("id", id).getResultList();
        List<?> rewards = em.createNativeQuery(
            "SELECT * FROM competition_rewards WHERE competition_id=:id ORDER BY rank")
            .setParameter("id", id).getResultList();
        Object[] comp = (Object[]) em.createNativeQuery("SELECT * FROM competitions WHERE id=:id")
            .setParameter("id", id).getSingleResult();
        return ResponseEntity.ok(ApiResponse.ok(Map.of("competition", comp, "board", board, "rewards", rewards)));
    }

    // ════════════════════════════════════════════════════════════════
    // LOYALTY & GAMIFICATION (Volume 11)
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/loyalty/my")
    @Operation(summary = "My loyalty points, tier, and badges")
    public ResponseEntity<ApiResponse<Map<String, Object>>> myLoyalty(@AuthenticationPrincipal User user) {
        long totalPoints = 0;
        try {
            Object pts = em.createNativeQuery("SELECT COALESCE(SUM(points),0) FROM loyalty_points WHERE user_id=:uid")
                .setParameter("uid", user.getId()).getSingleResult();
            totalPoints = pts != null ? ((Number)pts).longValue() : 0;
        } catch (Exception ignored) {}

        List<?> history = em.createNativeQuery(
            "SELECT * FROM loyalty_points WHERE user_id=:uid ORDER BY id DESC LIMIT 20")
            .setParameter("uid", user.getId()).getResultList();
        List<?> badges = em.createNativeQuery("SELECT * FROM user_badges WHERE user_id=:uid ORDER BY earned_at DESC")
            .setParameter("uid", user.getId()).getResultList();
        List<?> tiers = em.createNativeQuery("SELECT * FROM loyalty_tiers ORDER BY min_points").getResultList();

        // Determine current tier
        String currentTier = "Bronze";
        for (Object t : tiers) {
            Object[] r = (Object[]) t;
            if (totalPoints >= ((Number)r[2]).longValue()) currentTier = (String)r[1];
        }

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "totalPoints", totalPoints, "currentTier", currentTier,
            "history", history, "badges", badges, "tiers", tiers)));
    }

    // ════════════════════════════════════════════════════════════════
    // COMPLIANCE & AUDIT (Volume 19)
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/compliance/audit-log")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PLATFORM_ADMIN','ADMIN','COMPLIANCE_OFFICER')")
    @Operation(summary = "Audit log viewer — compliance module")
    public ResponseEntity<ApiResponse<Map<String, Object>>> auditLog(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String userId,
            @AuthenticationPrincipal User actor) {
        String where = "1=1";
        if (module != null && !module.isBlank()) where += " AND module='" + module + "'";
        if (action != null && !action.isBlank()) where += " AND action='" + action + "'";
        if (userId != null && !userId.isBlank()) where += " AND user_id=" + userId;
        List<?> logs = em.createNativeQuery(
            "SELECT a.*, u.name user_name, u.email FROM activity_logs a " +
            "LEFT JOIN users u ON u.id=a.user_id WHERE " + where +
            " ORDER BY a.created_at DESC LIMIT " + size + " OFFSET " + (page * size))
            .getResultList();
        long total = ((Number) em.createNativeQuery(
            "SELECT COUNT(*) FROM activity_logs WHERE " + where).getSingleResult()).longValue();
        return ResponseEntity.ok(ApiResponse.ok(Map.of("logs", logs, "total", total, "page", page)));
    }

    @GetMapping("/compliance/login-history")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PLATFORM_ADMIN','ADMIN','COMPLIANCE_OFFICER')")
    @Operation(summary = "Login history for compliance")
    public ResponseEntity<ApiResponse<List<?>>> loginHistory(
            @RequestParam(required = false) Long userId,
            @AuthenticationPrincipal User actor) {
        String where = userId != null ? "WHERE user_id=:uid" : "WHERE 1=1";
        var q = em.createNativeQuery(
            "SELECT lh.*, u.name user_name, u.email FROM login_history lh " +
            "LEFT JOIN users u ON u.id=lh.user_id " + where + " ORDER BY lh.login_at DESC LIMIT 100");
        if (userId != null) q.setParameter("uid", userId);
        return ResponseEntity.ok(ApiResponse.ok(q.getResultList()));
    }

    @GetMapping("/compliance/unusual-activity")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PLATFORM_ADMIN','ADMIN','COMPLIANCE_OFFICER','RESEARCH_ANALYST')")
    @Operation(summary = "Unusual market activity — 18 detection types")
    public ResponseEntity<ApiResponse<Map<String, Object>>> unusualActivity(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String type,
            @AuthenticationPrincipal User actor) {
        // trade_date is stored as 'yyyy-MM-dd' text, so the cut-off is computed in Java
        // and bound as a parameter (SQLite's date('now','-N days') does not exist in PostgreSQL).
        int window = Math.min(Math.max(days, 1), 3650);
        String fromDate = LocalDate.now().minusDays(window).toString();

        String where = "trade_date >= :fromDate";
        if (severity != null) where += " AND severity = :severity";
        if (type != null) where += " AND detection_type = :type";

        var aq = em.createNativeQuery(
            "SELECT * FROM unusual_activity WHERE " + where + " ORDER BY detected_at DESC LIMIT 100");
        aq.setParameter("fromDate", fromDate);
        if (severity != null) aq.setParameter("severity", severity);
        if (type != null) aq.setParameter("type", type);
        List<?> activities = aq.getResultList();
        Map<String, Long> byType = new LinkedHashMap<>();
        for (Object o : activities) {
            Object[] r = (Object[]) o;
            String t = r[4] != null ? r[4].toString() : "unknown";
            byType.merge(t, 1L, Long::sum);
        }
        return ResponseEntity.ok(ApiResponse.ok(Map.of("activities", activities, "byType", byType,
            "total", activities.size(), "period", window + " days")));
    }

    // ════════════════════════════════════════════════════════════════
    // REPORTS ENGINE (Volume 18)
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/reports/dashboard")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PLATFORM_ADMIN','ADMIN','INSTITUTION_ADMIN')")
    @Operation(summary = "Reports dashboard — summary counts per report type")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reportsDashboard(
            @AuthenticationPrincipal User actor) {
        java.util.Map<String,Object> _dash = new java.util.LinkedHashMap<>();
        _dash.put("totalUsers", safeCount("SELECT COUNT(*) FROM users"));
        _dash.put("activeUsers", safeCount("SELECT COUNT(*) FROM users WHERE status='active'"));
        _dash.put("totalSubscriptions", safeCount("SELECT COUNT(*) FROM subscriptions WHERE status='active'"));
        _dash.put("totalRevenue", safeSum("SELECT COALESCE(SUM(total_amount_inr),0) FROM payment_history WHERE payment_status='success'"));
        _dash.put("totalTrades", safeCount("SELECT COUNT(*) FROM paper_trades"));
        _dash.put("totalStrategies", safeCount("SELECT COUNT(*) FROM strategies WHERE status='active'"));
        _dash.put("totalBacktests", safeCount("SELECT COUNT(*) FROM backtests"));
        _dash.put("totalInstitutions", safeCount("SELECT COUNT(*) FROM institutions WHERE status='active'"));
        _dash.put("unusualAlerts", safeCount("SELECT COUNT(*) FROM unusual_activity WHERE trade_date='" + DateUtil.today() + "'"));
        _dash.put("generatedAt", DateUtil.now());
        return ResponseEntity.ok(ApiResponse.ok(_dash));
    }

    @GetMapping("/reports/user-report")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PLATFORM_ADMIN','ADMIN','INSTITUTION_ADMIN')")
    @Operation(summary = "User master report with filters")
    public ResponseEntity<ApiResponse<List<?>>> userReport(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String plan,
            @AuthenticationPrincipal User actor) {
        String where = "1=1";
        if (!actor.isSuperAdmin() && !actor.isPlatformAdmin())
            where += " AND institution_id=" + actor.getInstitutionId();
        if (role != null)   where += " AND user_type='" + role + "'";
        if (status != null) where += " AND status='" + status + "'";
        if (plan != null)   where += " AND plan='" + plan + "'";
        List<?> users = em.createNativeQuery(
            "SELECT id,tr_user_id,name,email,mobile,user_type,plan,plan_status,plan_expiry," +
            "status,created_at,last_login FROM users WHERE " + where +
            " ORDER BY created_at DESC LIMIT 1000").getResultList();
        return ResponseEntity.ok(ApiResponse.ok(users));
    }

    @GetMapping("/reports/subscription-report")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PLATFORM_ADMIN','ADMIN','INSTITUTION_ADMIN')")
    @Operation(summary = "Subscription & revenue report")
    public ResponseEntity<ApiResponse<Map<String, Object>>> subscriptionReport(
            @RequestParam(defaultValue = "30") int days,
            @AuthenticationPrincipal User actor) {
        List<?> revenue = em.createNativeQuery(
            "SELECT plan_name, COUNT(*) count, SUM(total_amount_inr) revenue, " +
            "SUM(gst_amount_inr) gst FROM payment_history WHERE payment_status='success' " +
            "AND created_at >= ? GROUP BY plan_name")
            .setParameter(1, LocalDate.now().minusDays(days).toString())
            .getResultList();
        List<?> byDay = em.createNativeQuery(
            "SELECT CAST(created_at AS date) day, COUNT(*) subs, SUM(total_amount_inr) rev " +
            "FROM payment_history WHERE payment_status='success' " +
            "AND created_at >= ? " +
            "GROUP BY CAST(created_at AS date) ORDER BY day")
            .setParameter(1, LocalDate.now().minusDays(days).toString())
            .getResultList();
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "byPlan", revenue, "dailyTrend", byDay, "period", days + " days")));
    }

    // ════════════════════════════════════════════════════════════════
    // MARKET INTELLIGENCE (Volume 20)
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/market/breadth")
    @Operation(summary = "Market breadth — advance/decline, sector health")
    public ResponseEntity<ApiResponse<Object>> marketBreadth(@AuthenticationPrincipal User user) {
        try {
            Object mb = em.createNativeQuery(
                "SELECT * FROM market_breadth WHERE exchange='NSE' ORDER BY trade_date DESC LIMIT 1")
                .getSingleResult();
            return ResponseEntity.ok(ApiResponse.ok(mb));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "message", "No market breadth data available. Import market data first.")));
        }
    }

    @GetMapping("/market/fii-dii")
    @Operation(summary = "FII/DII flow data")
    public ResponseEntity<ApiResponse<List<?>>> fiiDii(@AuthenticationPrincipal User user) {
        try {
            List<?> rows = em.createNativeQuery(
                "SELECT trade_date, fii_net_flow, dii_net_flow, market_score " +
                "FROM market_breadth ORDER BY trade_date DESC LIMIT 30").getResultList();
            return ResponseEntity.ok(ApiResponse.ok(rows));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.ok(List.of()));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // GST INVOICES (Volume 2)
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/invoices")
    @Operation(summary = "My GST invoices")
    public ResponseEntity<ApiResponse<List<?>>> myInvoices(@AuthenticationPrincipal User user) {
        try {
            List<?> invoices = em.createNativeQuery(
                "SELECT invoice_number, invoice_date, description, base_amount, total_tax, " +
                "total_amount, status, pdf_path FROM invoices WHERE user_id=:uid ORDER BY created_at DESC")
                .setParameter("uid", user.getId()).getResultList();
            return ResponseEntity.ok(ApiResponse.ok(invoices));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.ok(List.of()));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // INSTITUTION BRANDING (Volume 3)
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/branding/{institutionId}")
    @Operation(summary = "Get institution branding for white-label rendering")
    public ResponseEntity<ApiResponse<Object>> getBranding(@PathVariable Long institutionId) {
        try {
            Object branding = em.createNativeQuery(
                "SELECT * FROM tenant_branding WHERE institution_id=:id AND is_active=1")
                .setParameter("id", institutionId).getSingleResult();
            return ResponseEntity.ok(ApiResponse.ok(branding));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "primary_color","#1B3A5C","secondary_color","#006B6B","accent_color","#C9961A",
                "font_family","Inter")));
        }
    }

    @PostMapping("/branding")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PLATFORM_ADMIN','INSTITUTION_ADMIN')")
    @Operation(summary = "Update institution branding")
    public ResponseEntity<ApiResponse<Void>> updateBranding(
            @RequestBody BrandingRequest req, @AuthenticationPrincipal User actor) {
        em.createNativeQuery(
            "INSERT INTO tenant_branding(institution_id,primary_color,secondary_color," +
            "accent_color,font_family,logo_url,custom_domain,email_from_name,footer_text," +
            "is_active,updated_by,updated_at) VALUES(?,?,?,?,?,?,?,?,?,1,?,?) " +
            "ON CONFLICT (institution_id) DO UPDATE SET " +
            "primary_color=EXCLUDED.primary_color, secondary_color=EXCLUDED.secondary_color, " +
            "accent_color=EXCLUDED.accent_color, font_family=EXCLUDED.font_family, logo_url=EXCLUDED.logo_url, " +
            "custom_domain=EXCLUDED.custom_domain, email_from_name=EXCLUDED.email_from_name, " +
            "footer_text=EXCLUDED.footer_text, is_active=EXCLUDED.is_active, updated_by=EXCLUDED.updated_by, updated_at=EXCLUDED.updated_at")
            .setParameter(1,actor.getInstitutionId()).setParameter(2,req.getPrimaryColor())
            .setParameter(3,req.getSecondaryColor()).setParameter(4,req.getAccentColor())
            .setParameter(5,req.getFontFamily()).setParameter(6,req.getLogoUrl())
            .setParameter(7,req.getCustomDomain()).setParameter(8,req.getEmailFromName())
            .setParameter(9,req.getFooterText()).setParameter(10,actor.getId())
            .setParameter(11,DateUtil.now()).executeUpdate();
        return ResponseEntity.ok(ApiResponse.ok("Branding updated.", null));
    }

    // ════════════════════════════════════════════════════════════════
    // AGENT MODULE (Volume 12) — Extended
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/agent/leads")
    @Operation(summary = "My leads")
    public ResponseEntity<ApiResponse<List<?>>> myLeads(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            em.createNativeQuery("SELECT * FROM agent_leads WHERE agent_user_id=:uid ORDER BY created_at DESC")
                .setParameter("uid", user.getId()).getResultList()));
    }

    @PostMapping("/agent/leads")
    @Operation(summary = "Add lead")
    public ResponseEntity<ApiResponse<Void>> addLead(
            @RequestBody LeadRequest req, @AuthenticationPrincipal User user) {
        em.createNativeQuery(
            "INSERT INTO agent_leads(agent_user_id,institution_id,lead_name,lead_email," +
            "lead_mobile,source,status,notes,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)")
            .setParameter(1,user.getId()).setParameter(2,user.getInstitutionId())
            .setParameter(3,req.getLeadName()).setParameter(4,req.getLeadEmail())
            .setParameter(5,req.getLeadMobile()).setParameter(6,req.getSource())
            .setParameter(7,"new").setParameter(8,req.getNotes())
            .setParameter(9,DateUtil.now()).setParameter(10,DateUtil.now()).executeUpdate();
        return ResponseEntity.ok(ApiResponse.ok("Lead added.", null));
    }

    @GetMapping("/agent/commissions")
    @Operation(summary = "My commission history")
    public ResponseEntity<ApiResponse<Map<String, Object>>> commissions(@AuthenticationPrincipal User user) {
        List<?> comms = em.createNativeQuery(
            "SELECT ac.*, u.name subscriber_name FROM agent_commissions ac " +
            "LEFT JOIN users u ON u.id=ac.subscriber_user_id WHERE ac.agent_user_id=:uid ORDER BY ac.created_at DESC")
            .setParameter("uid", user.getId()).getResultList();
        double totalPending = 0, totalPaid = 0;
        try {
            Object p = em.createNativeQuery("SELECT COALESCE(SUM(total_commission),0) FROM agent_commissions WHERE agent_user_id=:uid AND status='pending'").setParameter("uid",user.getId()).getSingleResult();
            Object pd = em.createNativeQuery("SELECT COALESCE(SUM(total_commission),0) FROM agent_commissions WHERE agent_user_id=:uid AND status='paid'").setParameter("uid",user.getId()).getSingleResult();
            totalPending = p!=null?((Number)p).doubleValue():0;
            totalPaid = pd!=null?((Number)pd).doubleValue():0;
        } catch (Exception ignored) {}
        return ResponseEntity.ok(ApiResponse.ok(Map.of("commissions",comms,"totalPending",totalPending,"totalPaid",totalPaid)));
    }

    @PostMapping("/agent/payout")
    @Operation(summary = "Request commission payout")
    public ResponseEntity<ApiResponse<Void>> requestPayout(
            @RequestBody PayoutRequest req, @AuthenticationPrincipal User user) {
        em.createNativeQuery(
            "INSERT INTO payout_requests(agent_user_id,institution_id,amount,bank_name," +
            "account_number,ifsc_code,account_holder,status,created_at) VALUES(?,?,?,?,?,?,?,'pending',?)")
            .setParameter(1,user.getId()).setParameter(2,user.getInstitutionId())
            .setParameter(3,req.getAmount()).setParameter(4,req.getBankName())
            .setParameter(5,req.getAccountNumber()).setParameter(6,req.getIfscCode())
            .setParameter(7,req.getAccountHolder()).setParameter(8,DateUtil.now()).executeUpdate();
        return ResponseEntity.ok(ApiResponse.ok("Payout request submitted. Sales Manager will approve within 2 business days.", null));
    }

    // ── Helper methods ─────────────────────────────────────────────────────────
    private long   safeCount(String sql) { try { return ((Number)em.createNativeQuery(sql).getSingleResult()).longValue(); } catch(Exception e){return 0;} }
    private double safeSum(String sql)   { try { Object r=em.createNativeQuery(sql).getSingleResult(); return r!=null?((Number)r).doubleValue():0; } catch(Exception e){return 0;} }

    // ── Inner request classes ──────────────────────────────────────────────────
    @Data static class ScannerRequest { private String name,description,scannerType="equity",conditionsJson,logicText,universe="NSE_500",timeframe="Daily",schedule="manual",tags; private boolean isPublic; }
    @Data static class AlertRequest   { private Long scannerId,strategyId; private String alertName,alertType="price",priority="medium",conditionJson,universe="watchlist",timeframe="Daily",channelsJson,frequency="once"; private int cooldownMins=60; }
    @Data static class BrandingRequest{ private String primaryColor,secondaryColor,accentColor,fontFamily,logoUrl,customDomain,emailFromName,footerText; }
    @Data static class LeadRequest    { private String leadName,leadEmail,leadMobile,source,notes; }
    @Data static class PayoutRequest  { private double amount; private String bankName,accountNumber,ifscCode,accountHolder; }
}
