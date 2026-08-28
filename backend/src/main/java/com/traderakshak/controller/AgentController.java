package com.traderakshak.controller;

import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.entity.User;
import com.traderakshak.service.AuditService;
import com.traderakshak.util.DateUtil;
import com.traderakshak.util.UserMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/agents")
@RequiredArgsConstructor
@Tag(name = "Agent Management", description = "3-level agent hierarchy, commissions, referrals")
public class AgentController {

    @PersistenceContext private EntityManager em;
    private final AuditService auditService;

    // ── Agent listing ─────────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "List all agents (admin) or my downline agents")
    public ResponseEntity<ApiResponse<List<?>>> list(@AuthenticationPrincipal User actor) {
        String query = actor.isAdmin()
            ? "SELECT a.*,u.name,u.email,u.mobile FROM agents a LEFT JOIN users u ON u.id=a.user_id ORDER BY a.agent_level,a.id DESC"
            : "SELECT a.*,u.name,u.email,u.mobile FROM agents a LEFT JOIN users u ON u.id=a.user_id WHERE a.master_agent_id IN (SELECT id FROM agents WHERE user_id=:uid) OR a.parent_agent_id IN (SELECT id FROM agents WHERE user_id=:uid) ORDER BY a.id DESC";
        try {
            var q = em.createNativeQuery(query);
            if (!actor.isAdmin()) q.setParameter("uid", actor.getId());
            return ResponseEntity.ok(ApiResponse.ok(q.getResultList()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.ok(List.of()));
        }
    }

    @GetMapping("/my-dashboard")
    @Operation(summary = "My agent dashboard — referrals, commissions, earnings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> myDashboard(
            @AuthenticationPrincipal User actor) {
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            // My agent record
            Object agentRow = em.createNativeQuery(
                "SELECT * FROM agents WHERE user_id=:uid ORDER BY id DESC LIMIT 1")
                .setParameter("uid", actor.getId()).getSingleResult();
            data.put("agent", agentRow);
        } catch (Exception e) { data.put("agent", null); }

        try {
            Object referrals = em.createNativeQuery(
                "SELECT COUNT(*) FROM referrals WHERE referrer_user_id=:uid")
                .setParameter("uid", actor.getId()).getSingleResult();
            data.put("totalReferrals", referrals);
        } catch (Exception e) { data.put("totalReferrals", 0); }

        try {
            Object earnings = em.createNativeQuery(
                "SELECT COALESCE(SUM(commission_amount),0) FROM agent_commissions ac " +
                "JOIN agents a ON a.id=ac.agent_id WHERE a.user_id=:uid")
                .setParameter("uid", actor.getId()).getSingleResult();
            data.put("totalEarnings", earnings);
        } catch (Exception e) { data.put("totalEarnings", 0); }

        try {
            List<?> recent = em.createNativeQuery(
                "SELECT r.*,u.name referred_name FROM referrals r " +
                "LEFT JOIN users u ON u.id=r.referred_user_id " +
                "WHERE r.referrer_user_id=:uid ORDER BY r.id DESC LIMIT 20")
                .setParameter("uid", actor.getId()).getResultList();
            data.put("recentReferrals", recent);
        } catch (Exception e) { data.put("recentReferrals", List.of()); }

        try {
            List<?> commissions = em.createNativeQuery(
                "SELECT ac.* FROM agent_commissions ac " +
                "JOIN agents a ON a.id=ac.agent_id WHERE a.user_id=:uid ORDER BY ac.id DESC LIMIT 20")
                .setParameter("uid", actor.getId()).getResultList();
            data.put("commissions", commissions);
        } catch (Exception e) { data.put("commissions", List.of()); }

        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    @GetMapping("/commission-slabs")
    @Operation(summary = "Get commission slab for each agent level and plan")
    public ResponseEntity<ApiResponse<List<?>>> commissionSlabs(@AuthenticationPrincipal User actor) {
        try {
            return ResponseEntity.ok(ApiResponse.ok(
                em.createNativeQuery("SELECT * FROM commission_slabs WHERE status='active' ORDER BY agent_level,plan_name")
                    .getResultList()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.ok(List.of()));
        }
    }

    @GetMapping("/downline")
    @Operation(summary = "My complete downline tree")
    public ResponseEntity<ApiResponse<Map<String, Object>>> myDownline(
            @AuthenticationPrincipal User actor) {
        Map<String, Object> tree = new LinkedHashMap<>();
        try {
            // Direct referrals
            tree.put("direct", em.createNativeQuery(
                "SELECT u.tr_user_id,u.name,u.email,u.plan,u.plan_status,u.created_at " +
                "FROM referrals r JOIN users u ON u.id=r.referred_user_id " +
                "WHERE r.referrer_user_id=:uid ORDER BY r.referred_at DESC")
                .setParameter("uid", actor.getId()).getResultList());

            // Sub-agents
            tree.put("subAgents", em.createNativeQuery(
                "SELECT a.*,u.name,u.email FROM agents a " +
                "JOIN users u ON u.id=a.user_id WHERE a.master_agent_id=(" +
                "SELECT id FROM agents WHERE user_id=:uid LIMIT 1)")
                .setParameter("uid", actor.getId()).getResultList());
        } catch (Exception e) {
            tree.put("direct", List.of()); tree.put("subAgents", List.of());
        }
        return ResponseEntity.ok(ApiResponse.ok(tree));
    }

    // ── Agent registration ────────────────────────────────────────────────────

    @PostMapping
    @Operation(summary = "Register as agent or create sub-agent")
    public ResponseEntity<ApiResponse<Map<String, Object>>> register(
            @RequestBody AgentRegRequest req,
            @AuthenticationPrincipal User actor) {

        // Determine agent level
        int level = 1;
        if ("agent_l2".equals(actor.getUserType())) level = 2;
        else if ("agent_l3".equals(actor.getUserType())) level = 3;

        // Check existing
        try {
            em.createNativeQuery("SELECT id FROM agents WHERE user_id=:uid")
                .setParameter("uid", actor.getId()).getSingleResult();
            return ResponseEntity.badRequest().body(
                ApiResponse.error("AGT-001", "Already registered as agent."));
        } catch (Exception ignored) {}

        // Generate agent code
        String code = "AG-" + DateUtil.today().replace("-","") + "-" +
                      (int)(Math.random() * 9000 + 1000);
        String link = "https://traderakshak.in/register?ref=" + code;

        try {
            em.createNativeQuery(
                "INSERT INTO agents(institution_id,user_id,agent_type,agent_level," +
                "master_agent_id,parent_agent_id,agent_code,referral_link," +
                "commission_rate,territory,status,created_at) VALUES(1,?,?,?,?,?,?,?,?,?,'active',?)")
                .setParameter(1, actor.getId())
                .setParameter(2, "agent_l" + level)
                .setParameter(3, level)
                .setParameter(4, req.getMasterAgentId())
                .setParameter(5, req.getParentAgentId())
                .setParameter(6, code).setParameter(7, link)
                .setParameter(8, req.getCommissionRate() > 0 ? req.getCommissionRate() : 10.0)
                .setParameter(9, req.getTerritory()).setParameter(10, DateUtil.now())
                .executeUpdate();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                ApiResponse.error("AGT-500", UserMessage.of(e)));
        }

        auditService.logActivity(actor.getId(), "Agent", "Register", "success", "Code: " + code, "", "");
        return ResponseEntity.ok(ApiResponse.ok("Agent registered.",
            Map.of("agentCode", code, "referralLink", link, "level", level)));
    }

    // ── Commission management (admin) ─────────────────────────────────────────

    @PostMapping("/commissions/calculate")
    @Operation(summary = "Admin: calculate commissions for a payment")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> calculateCommissions(
            @RequestBody CommissionCalcRequest req,
            @AuthenticationPrincipal User actor) {
        if (!actor.isAdmin()) return ResponseEntity.status(403).build();

        List<Map<String, Object>> commissions = new ArrayList<>();
        try {
            // Find agent chain for the subscriber
            Object[] agentRow = (Object[]) em.createNativeQuery(
                "SELECT a.id,a.agent_level,a.master_agent_id,a.parent_agent_id FROM agents a " +
                "JOIN users u ON u.id=a.user_id WHERE u.referred_by_code=(" +
                "SELECT referral_code FROM users WHERE id=:uid)")
                .setParameter("uid", req.getSubscriberId()).getSingleResult();

            // L1 commission
            double l1Pct = getCommissionPct((int)(long)(Number)agentRow[1], req.getPlanName());
            double l1Amount = req.getAmount() * l1Pct / 100;
            commissions.add(Map.of("agentId", agentRow[0], "level", agentRow[1],
                                   "pct", l1Pct, "amount", l1Amount));

            // L2 override
            if (agentRow[2] != null) {
                double l2Pct = getCommissionPct(2, req.getPlanName());
                commissions.add(Map.of("agentId", agentRow[2], "level", 2,
                                       "pct", l2Pct, "amount", req.getAmount() * l2Pct / 100));
            }
        } catch (Exception e) {
            // No agent chain found — no commissions
        }
        return ResponseEntity.ok(ApiResponse.ok("Commission calculated.", commissions));
    }

    @PostMapping("/commissions/pay/{agentId}")
    @Operation(summary = "Admin: mark commission as paid")
    public ResponseEntity<ApiResponse<Void>> payCommission(
            @PathVariable Long agentId,
            @AuthenticationPrincipal User actor) {
        if (!actor.isAdmin()) return ResponseEntity.status(403).build();
        try {
            em.createNativeQuery(
                "UPDATE agent_commissions SET status='paid',paid_at=? WHERE agent_id=? AND status='pending'")
                .setParameter(1, DateUtil.now()).setParameter(2, agentId).executeUpdate();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
        return ResponseEntity.ok(ApiResponse.ok("Commission marked paid.", null));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private double getCommissionPct(int level, String planName) {
        try {
            Object pct = em.createNativeQuery(
                "SELECT commission_pct FROM commission_slabs WHERE agent_level=:l AND plan_name=:p AND status='active' LIMIT 1")
                .setParameter("l", level).setParameter("p", planName).getSingleResult();
            return ((Number) pct).doubleValue();
        } catch (Exception e) { return 10.0; }
    }

    @Data static class AgentRegRequest {
        private Long masterAgentId, parentAgentId;
        private double commissionRate = 10.0;
        private String territory;
    }
    @Data static class CommissionCalcRequest {
        private Long subscriberId; private String planName; private double amount;
    }
}
