package com.traderakshak.controller;

import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.entity.User;
import com.traderakshak.util.DateUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/admin/subscriptions")
public class SubscriptionAdminController {

    @PersistenceContext
    private EntityManager em;

    // ── Dashboard ─────────────────────────────────────────────────────────────

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<Map<String, Object>>> dashboard() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", Map.of(
            "totalUsers", count("SELECT COUNT(*) FROM users"),
            "freeUsers", count("SELECT COUNT(*) FROM users WHERE LOWER(plan) IN ('free trial','free')"),
            "basicUsers", count("SELECT COUNT(*) FROM users WHERE LOWER(plan) IN ('basic','starter')"),
            "proUsers", count("SELECT COUNT(*) FROM users WHERE LOWER(plan) IN ('pro','premium')"),
            "eliteUsers", count("SELECT COUNT(*) FROM users WHERE LOWER(plan) IN ('elite','enterprise','institutional')"),
            "activeSubscriptions", count("SELECT COUNT(*) FROM subscriptions WHERE subscription_status='active'"),
            "totalRevenue", dblQuery("SELECT COALESCE(SUM(net_amount),0) FROM payments WHERE payment_status='success'"),
            "pendingPayments", count("SELECT COUNT(*) FROM payments WHERE payment_status='pending'")
        ));
        data.put("plans", listPlans());
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    // ── Users by Plan Category ────────────────────────────────────────────────

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<Map<String, Object>>> usersByPlan(
            @RequestParam(required = false) String planCategory,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        StringBuilder sql = new StringBuilder(
            "SELECT u.id,u.tr_user_id,u.name,u.email,u.mobile,u.role,u.user_type,u.plan,u.status,u.plan_expiry,u.created_at FROM users u WHERE 1=1");
        if (planCategory != null && !planCategory.isBlank()) {
            String filter = switch (planCategory.toLowerCase()) {
                case "free" -> " AND LOWER(u.plan) IN ('free trial','free')";
                case "basic" -> " AND LOWER(u.plan) IN ('basic','starter')";
                case "pro" -> " AND LOWER(u.plan) IN ('pro','premium')";
                case "elite" -> " AND LOWER(u.plan) IN ('elite','enterprise','institutional')";
                default -> "";
            };
            sql.append(filter);
        }
        if (search != null && !search.isBlank()) {
            sql.append(" AND (LOWER(u.name) LIKE LOWER('%").append(esc(search)).append("%') OR LOWER(u.email) LIKE LOWER('%").append(esc(search)).append("%'))");
        }
        long total = count(sql.toString().replaceFirst("SELECT u\\.id.*? FROM", "SELECT COUNT(*) FROM"));
        sql.append(" ORDER BY u.id DESC LIMIT ").append(size).append(" OFFSET ").append(page * size);
        List<Map<String, Object>> rows = list(sql.toString(),
            "id", "trUserId", "name", "email", "mobile", "role", "userType", "plan", "status", "planExpiry", "createdAt");
        return ResponseEntity.ok(ApiResponse.ok(Map.of("users", rows, "total", total, "page", page, "size", size)));
    }

    // ── Subscription Plans CRUD ───────────────────────────────────────────────

    @GetMapping("/plans")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getPlans() {
        return ResponseEntity.ok(ApiResponse.ok(listPlans()));
    }

    @PostMapping("/plans")
    @Transactional
    public ResponseEntity<ApiResponse<String>> savePlan(@RequestBody Map<String, Object> req,
                                                         @AuthenticationPrincipal User actor) {
        Long id = lng(req.get("id"));
        String name = text(req.get("planName"));
        if (name.isBlank()) return ResponseEntity.badRequest().body(ApiResponse.error("PLAN-REQ", "Plan name is required."));
        if (id == null) {
            em.createNativeQuery("INSERT INTO subscription_plans(plan_name,plan_category,monthly_price,quarterly_price,half_yearly_price,yearly_price,trial_days,description,status,created_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")
                .setParameter(1, name)
                .setParameter(2, textOr(req.get("planCategory"), "basic"))
                .setParameter(3, dbl(req.get("monthlyPrice"), 0))
                .setParameter(4, dbl(req.get("quarterlyPrice"), 0))
                .setParameter(5, dbl(req.get("halfYearlyPrice"), 0))
                .setParameter(6, dbl(req.get("yearlyPrice"), 0))
                .setParameter(7, integer(req.get("trialDays"), 0))
                .setParameter(8, text(req.get("description")))
                .setParameter(9, textOr(req.get("status"), "active"))
                .setParameter(10, actor == null ? null : actor.getId())
                .setParameter(11, DateUtil.now())
                .setParameter(12, DateUtil.now())
                .executeUpdate();
        } else {
            em.createNativeQuery("UPDATE subscription_plans SET plan_name=?,plan_category=?,monthly_price=?,quarterly_price=?,half_yearly_price=?,yearly_price=?,trial_days=?,description=?,status=?,updated_at=? WHERE id=?")
                .setParameter(1, name)
                .setParameter(2, textOr(req.get("planCategory"), "basic"))
                .setParameter(3, dbl(req.get("monthlyPrice"), 0))
                .setParameter(4, dbl(req.get("quarterlyPrice"), 0))
                .setParameter(5, dbl(req.get("halfYearlyPrice"), 0))
                .setParameter(6, dbl(req.get("yearlyPrice"), 0))
                .setParameter(7, integer(req.get("trialDays"), 0))
                .setParameter(8, text(req.get("description")))
                .setParameter(9, textOr(req.get("status"), "active"))
                .setParameter(10, DateUtil.now())
                .setParameter(11, id)
                .executeUpdate();
        }
        return ResponseEntity.ok(ApiResponse.ok("Plan saved."));
    }

    @PutMapping("/plans/{id}/status")
    @Transactional
    public ResponseEntity<ApiResponse<String>> togglePlanStatus(@PathVariable Long id, @RequestBody Map<String, Object> req) {
        em.createNativeQuery("UPDATE subscription_plans SET status=?,updated_at=? WHERE id=?")
            .setParameter(1, textOr(req.get("status"), "inactive"))
            .setParameter(2, DateUtil.now())
            .setParameter(3, id).executeUpdate();
        return ResponseEntity.ok(ApiResponse.ok("Plan status updated."));
    }

    // ── Plan Features ─────────────────────────────────────────────────────────

    @GetMapping("/plans/{planId}/features")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getPlanFeatures(@PathVariable Long planId) {
        return ResponseEntity.ok(ApiResponse.ok(list(
            "SELECT id,plan_id,feature_key,feature_label,allowed FROM plan_features WHERE plan_id=" + planId + " ORDER BY feature_key",
            "id", "planId", "featureKey", "featureLabel", "allowed")));
    }

    @PostMapping("/plans/{planId}/features")
    @Transactional
    public ResponseEntity<ApiResponse<String>> savePlanFeature(@PathVariable Long planId, @RequestBody Map<String, Object> req) {
        String key = text(req.get("featureKey"));
        if (key.isBlank()) return ResponseEntity.badRequest().body(ApiResponse.error("FEAT-REQ", "Feature key is required."));
        int allowed = bool(req.get("allowed")) ? 1 : 0;
        int updated = em.createNativeQuery("UPDATE plan_features SET feature_label=?,allowed=?,updated_at=? WHERE plan_id=? AND feature_key=?")
            .setParameter(1, text(req.get("featureLabel")))
            .setParameter(2, allowed).setParameter(3, DateUtil.now())
            .setParameter(4, planId).setParameter(5, key).executeUpdate();
        if (updated == 0) {
            em.createNativeQuery("INSERT INTO plan_features(plan_id,feature_key,feature_label,allowed,created_at,updated_at) VALUES(?,?,?,?,?,?)")
                .setParameter(1, planId).setParameter(2, key)
                .setParameter(3, text(req.get("featureLabel")))
                .setParameter(4, allowed)
                .setParameter(5, DateUtil.now()).setParameter(6, DateUtil.now()).executeUpdate();
        }
        return ResponseEntity.ok(ApiResponse.ok("Feature saved."));
    }

    // ── Subscriptions ─────────────────────────────────────────────────────────

    @GetMapping("/subscriptions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSubscriptions(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        StringBuilder sql = new StringBuilder(
            "SELECT s.id,s.user_id,u.name,u.email,sp.plan_name,s.billing_cycle,s.price,s.start_date,s.end_date,s.renewal_date,s.subscription_status,s.payment_status,s.auto_renew,s.trial_active,s.created_at " +
            "FROM subscriptions s LEFT JOIN users u ON u.id=s.user_id LEFT JOIN subscription_plans sp ON sp.id=s.plan_id WHERE 1=1");
        if (status != null && !status.isBlank()) sql.append(" AND s.subscription_status='").append(esc(status)).append("'");
        if (search != null && !search.isBlank()) sql.append(" AND (LOWER(u.name) LIKE LOWER('%").append(esc(search)).append("%') OR LOWER(u.email) LIKE LOWER('%").append(esc(search)).append("%'))");
        long total = count(sql.toString().replaceFirst("SELECT s\\.id.*? FROM", "SELECT COUNT(*) FROM"));
        sql.append(" ORDER BY s.id DESC LIMIT ").append(size).append(" OFFSET ").append(page * size);
        List<Map<String, Object>> rows = list(sql.toString(),
            "id", "userId", "userName", "userEmail", "planName", "billingCycle", "price", "startDate", "endDate", "renewalDate", "subscriptionStatus", "paymentStatus", "autoRenew", "trialActive", "createdAt");
        return ResponseEntity.ok(ApiResponse.ok(Map.of("subscriptions", rows, "total", total)));
    }

    @PostMapping("/subscriptions")
    @Transactional
    public ResponseEntity<ApiResponse<String>> createSubscription(@RequestBody Map<String, Object> req,
                                                                    @AuthenticationPrincipal User actor) {
        em.createNativeQuery("INSERT INTO subscriptions(user_id,plan_id,billing_cycle,price,start_date,end_date,renewal_date,trial_active,auto_renew,subscription_status,payment_status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")
            .setParameter(1, lng(req.get("userId")))
            .setParameter(2, lng(req.get("planId")))
            .setParameter(3, textOr(req.get("billingCycle"), "monthly"))
            .setParameter(4, dbl(req.get("price"), 0))
            .setParameter(5, text(req.get("startDate")))
            .setParameter(6, text(req.get("endDate")))
            .setParameter(7, text(req.get("renewalDate")))
            .setParameter(8, bool(req.get("trialActive")) ? 1 : 0)
            .setParameter(9, bool(req.get("autoRenew")) ? 1 : 0)
            .setParameter(10, textOr(req.get("subscriptionStatus"), "active"))
            .setParameter(11, textOr(req.get("paymentStatus"), "pending"))
            .setParameter(12, DateUtil.now())
            .setParameter(13, DateUtil.now())
            .executeUpdate();
        return ResponseEntity.ok(ApiResponse.ok("Subscription created."));
    }

    @PutMapping("/subscriptions/{id}/status")
    @Transactional
    public ResponseEntity<ApiResponse<String>> updateSubscriptionStatus(@PathVariable Long id, @RequestBody Map<String, Object> req) {
        em.createNativeQuery("UPDATE subscriptions SET subscription_status=?,payment_status=?,updated_at=? WHERE id=?")
            .setParameter(1, textOr(req.get("subscriptionStatus"), "active"))
            .setParameter(2, textOr(req.get("paymentStatus"), "pending"))
            .setParameter(3, DateUtil.now())
            .setParameter(4, id).executeUpdate();
        return ResponseEntity.ok(ApiResponse.ok("Subscription status updated."));
    }

    @PostMapping("/subscriptions/{id}/cancel")
    @Transactional
    public ResponseEntity<ApiResponse<String>> cancelSubscription(@PathVariable Long id) {
        em.createNativeQuery("UPDATE subscriptions SET subscription_status='cancelled',cancelled_at=?,updated_at=? WHERE id=?")
            .setParameter(1, DateUtil.now()).setParameter(2, DateUtil.now()).setParameter(3, id).executeUpdate();
        return ResponseEntity.ok(ApiResponse.ok("Subscription cancelled."));
    }

    // ── Payments ──────────────────────────────────────────────────────────────

    @GetMapping("/payments")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPayments(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String gateway,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        StringBuilder sql = new StringBuilder(
            "SELECT p.id,p.user_id,u.name,u.email,p.billing_cycle,p.amount,p.gst,p.discount,p.net_amount,p.gateway_name,p.gateway_mode,p.transaction_id,p.payment_status,p.payment_date,p.invoice_number,p.created_at " +
            "FROM payments p LEFT JOIN users u ON u.id=p.user_id WHERE 1=1");
        if (status != null && !status.isBlank()) sql.append(" AND p.payment_status='").append(esc(status)).append("'");
        if (gateway != null && !gateway.isBlank()) sql.append(" AND p.gateway_name='").append(esc(gateway)).append("'");
        if (mode != null && !mode.isBlank()) sql.append(" AND p.gateway_mode='").append(esc(mode)).append("'");
        if (search != null && !search.isBlank()) sql.append(" AND (LOWER(u.name) LIKE LOWER('%").append(esc(search)).append("%') OR p.transaction_id LIKE '%").append(esc(search)).append("%' OR p.invoice_number LIKE '%").append(esc(search)).append("%')");
        long total = count(sql.toString().replaceFirst("SELECT p\\.id.*? FROM", "SELECT COUNT(*) FROM"));
        sql.append(" ORDER BY p.id DESC LIMIT ").append(size).append(" OFFSET ").append(page * size);
        List<Map<String, Object>> rows = list(sql.toString(),
            "id", "userId", "userName", "userEmail", "billingCycle", "amount", "gst", "discount", "netAmount", "gatewayName", "gatewayMode", "transactionId", "paymentStatus", "paymentDate", "invoiceNumber", "createdAt");
        return ResponseEntity.ok(ApiResponse.ok(Map.of("payments", rows, "total", total)));
    }

    @PostMapping("/payments")
    @Transactional
    public ResponseEntity<ApiResponse<String>> createPayment(@RequestBody Map<String, Object> req,
                                                               @AuthenticationPrincipal User actor) {
        double amount = dbl(req.get("amount"), 0);
        double gst = dbl(req.get("gst"), amount * 0.18);
        double discount = dbl(req.get("discount"), 0);
        double net = amount + gst - discount;
        String invoiceNo = "INV-" + DateUtil.today().replace("-", "") + "-" + (count("SELECT COUNT(*) FROM payments") + 1);

        em.createNativeQuery("INSERT INTO payments(user_id,subscription_id,plan_id,billing_cycle,amount,gst,discount,net_amount,gateway_name,gateway_mode,gateway_order_id,transaction_id,payment_status,payment_date,invoice_number,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
            .setParameter(1, lng(req.get("userId")))
            .setParameter(2, lng(req.get("subscriptionId")))
            .setParameter(3, lng(req.get("planId")))
            .setParameter(4, textOr(req.get("billingCycle"), "monthly"))
            .setParameter(5, amount)
            .setParameter(6, gst)
            .setParameter(7, discount)
            .setParameter(8, net)
            .setParameter(9, textOr(req.get("gatewayName"), "razorpay"))
            .setParameter(10, textOr(req.get("gatewayMode"), "test"))
            .setParameter(11, text(req.get("gatewayOrderId")))
            .setParameter(12, text(req.get("transactionId")))
            .setParameter(13, textOr(req.get("paymentStatus"), "pending"))
            .setParameter(14, textOr(req.get("paymentDate"), DateUtil.now()))
            .setParameter(15, invoiceNo)
            .setParameter(16, DateUtil.now())
            .setParameter(17, DateUtil.now())
            .executeUpdate();

        em.createNativeQuery("INSERT INTO invoices(payment_id,user_id,invoice_number,invoice_date,plan_name,billing_cycle,amount,gst,discount,net_amount,status,created_at) VALUES((SELECT MAX(id) FROM payments),?,?,?,?,?,?,?,?,?,?,?)")
            .setParameter(1, lng(req.get("userId")))
            .setParameter(2, invoiceNo)
            .setParameter(3, DateUtil.now())
            .setParameter(4, text(req.get("planName")))
            .setParameter(5, textOr(req.get("billingCycle"), "monthly"))
            .setParameter(6, amount).setParameter(7, gst).setParameter(8, discount).setParameter(9, net)
            .setParameter(10, "generated")
            .setParameter(11, DateUtil.now())
            .executeUpdate();

        return ResponseEntity.ok(ApiResponse.ok("Payment recorded. Invoice: " + invoiceNo));
    }

    @PutMapping("/payments/{id}/status")
    @Transactional
    public ResponseEntity<ApiResponse<String>> updatePaymentStatus(@PathVariable Long id, @RequestBody Map<String, Object> req) {
        String status = textOr(req.get("paymentStatus"), "success");
        em.createNativeQuery("UPDATE payments SET payment_status=?,updated_at=? WHERE id=?")
            .setParameter(1, status).setParameter(2, DateUtil.now()).setParameter(3, id).executeUpdate();
        if ("success".equals(status)) {
            em.createNativeQuery("UPDATE subscriptions SET subscription_status='active',payment_status='success',updated_at=? WHERE id=(SELECT subscription_id FROM payments WHERE id=?)")
                .setParameter(1, DateUtil.now()).setParameter(2, id).executeUpdate();
        }
        return ResponseEntity.ok(ApiResponse.ok("Payment status updated."));
    }

    // ── Payment Logs ──────────────────────────────────────────────────────────

    @GetMapping("/payments/{paymentId}/logs")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getPaymentLogs(@PathVariable Long paymentId) {
        return ResponseEntity.ok(ApiResponse.ok(list(
            "SELECT id,gateway_name,gateway_mode,request_payload,response_payload,error_message,webhook_status,retry_count,created_at FROM payment_logs WHERE payment_id=" + paymentId + " ORDER BY id DESC",
            "id", "gatewayName", "gatewayMode", "requestPayload", "responsePayload", "errorMessage", "webhookStatus", "retryCount", "createdAt")));
    }

    @PostMapping("/payments/{paymentId}/logs")
    @Transactional
    public ResponseEntity<ApiResponse<String>> addPaymentLog(@PathVariable Long paymentId, @RequestBody Map<String, Object> req) {
        em.createNativeQuery("INSERT INTO payment_logs(payment_id,gateway_name,gateway_mode,request_payload,response_payload,error_message,webhook_status,retry_count,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)")
            .setParameter(1, paymentId)
            .setParameter(2, text(req.get("gatewayName")))
            .setParameter(3, text(req.get("gatewayMode")))
            .setParameter(4, text(req.get("requestPayload")))
            .setParameter(5, text(req.get("responsePayload")))
            .setParameter(6, text(req.get("errorMessage")))
            .setParameter(7, text(req.get("webhookStatus")))
            .setParameter(8, integer(req.get("retryCount"), 0))
            .setParameter(9, DateUtil.now())
            .setParameter(10, DateUtil.now())
            .executeUpdate();
        return ResponseEntity.ok(ApiResponse.ok("Payment log added."));
    }

    // ── Role & Permission Mapping by Plan ─────────────────────────────────────

    @GetMapping("/permissions")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getPermissions(
            @RequestParam(required = false) String planCategory) {
        String sql = "SELECT id,plan_category,billing_cycle,menu_key,action_key,allowed FROM user_plan_permissions";
        if (planCategory != null && !planCategory.isBlank()) sql += " WHERE plan_category='" + esc(planCategory) + "'";
        sql += " ORDER BY plan_category,menu_key,action_key";
        return ResponseEntity.ok(ApiResponse.ok(list(sql, "id", "planCategory", "billingCycle", "menuKey", "actionKey", "allowed")));
    }

    @PostMapping("/permissions")
    @Transactional
    public ResponseEntity<ApiResponse<String>> savePermission(@RequestBody Map<String, Object> req,
                                                               @AuthenticationPrincipal User actor) {
        String category = text(req.get("planCategory"));
        String menuKey = text(req.get("menuKey"));
        if (category.isBlank() || menuKey.isBlank()) return ResponseEntity.badRequest().body(ApiResponse.error("PERM-REQ", "Plan category and menu key required."));
        String cycle = text(req.get("billingCycle"));
        String actionKey = text(req.get("actionKey"));
        int allowed = bool(req.get("allowed")) ? 1 : 0;

        int updated = em.createNativeQuery("UPDATE user_plan_permissions SET allowed=?,updated_at=? WHERE plan_category=? AND COALESCE(billing_cycle,'')=? AND menu_key=? AND COALESCE(action_key,'')=?")
            .setParameter(1, allowed).setParameter(2, DateUtil.now())
            .setParameter(3, category).setParameter(4, cycle).setParameter(5, menuKey).setParameter(6, actionKey).executeUpdate();
        if (updated == 0) {
            em.createNativeQuery("INSERT INTO user_plan_permissions(plan_category,billing_cycle,menu_key,action_key,allowed,created_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)")
                .setParameter(1, category).setParameter(2, cycle.isBlank() ? null : cycle)
                .setParameter(3, menuKey).setParameter(4, actionKey.isBlank() ? null : actionKey)
                .setParameter(5, allowed)
                .setParameter(6, actor == null ? null : actor.getId())
                .setParameter(7, DateUtil.now()).setParameter(8, DateUtil.now()).executeUpdate();
        }
        return ResponseEntity.ok(ApiResponse.ok("Permission saved."));
    }

    // ── Reports / Downloads ───────────────────────────────────────────────────

    @GetMapping("/reports/users")
    public ResponseEntity<byte[]> downloadUsers(@RequestParam(required = false) String planCategory) {
        StringBuilder sql = new StringBuilder("SELECT u.id,u.tr_user_id,u.name,u.email,u.mobile,u.plan,u.status,u.plan_expiry,u.created_at FROM users u WHERE 1=1");
        if (planCategory != null && !planCategory.isBlank()) {
            sql.append(switch (planCategory.toLowerCase()) {
                case "free" -> " AND LOWER(u.plan) IN ('free trial','free')";
                case "basic" -> " AND LOWER(u.plan) IN ('basic','starter')";
                case "pro" -> " AND LOWER(u.plan) IN ('pro','premium')";
                case "elite" -> " AND LOWER(u.plan) IN ('elite','enterprise','institutional')";
                default -> "";
            });
        }
        sql.append(" ORDER BY u.id DESC LIMIT 5000");
        List<Map<String, Object>> rows = list(sql.toString(), "id", "trUserId", "name", "email", "mobile", "plan", "status", "planExpiry", "createdAt");
        StringBuilder csv = new StringBuilder("id,tr_user_id,name,email,mobile,plan,status,plan_expiry,created_at\n");
        for (Map<String, Object> r : rows) {
            csv.append(csvVal(r.get("id"))).append(',').append(csvVal(r.get("trUserId"))).append(',')
                .append(csvVal(r.get("name"))).append(',').append(csvVal(r.get("email"))).append(',')
                .append(csvVal(r.get("mobile"))).append(',').append(csvVal(r.get("plan"))).append(',')
                .append(csvVal(r.get("status"))).append(',').append(csvVal(r.get("planExpiry"))).append(',')
                .append(csvVal(r.get("createdAt"))).append('\n');
        }
        return csvResponse("users_" + (planCategory != null ? planCategory : "all") + "_report.csv", csv.toString());
    }

    @GetMapping("/reports/payments")
    public ResponseEntity<byte[]> downloadPayments() {
        List<Map<String, Object>> rows = list(
            "SELECT p.id,u.name,u.email,p.billing_cycle,p.amount,p.gst,p.discount,p.net_amount,p.gateway_name,p.gateway_mode,p.transaction_id,p.payment_status,p.payment_date,p.invoice_number FROM payments p LEFT JOIN users u ON u.id=p.user_id ORDER BY p.id DESC LIMIT 5000",
            "id", "userName", "userEmail", "billingCycle", "amount", "gst", "discount", "netAmount", "gatewayName", "gatewayMode", "transactionId", "paymentStatus", "paymentDate", "invoiceNumber");
        StringBuilder csv = new StringBuilder("id,user_name,email,billing_cycle,amount,gst,discount,net_amount,gateway,mode,transaction_id,status,payment_date,invoice\n");
        for (Map<String, Object> r : rows) {
            csv.append(csvVal(r.get("id"))).append(',').append(csvVal(r.get("userName"))).append(',')
                .append(csvVal(r.get("userEmail"))).append(',').append(csvVal(r.get("billingCycle"))).append(',')
                .append(csvVal(r.get("amount"))).append(',').append(csvVal(r.get("gst"))).append(',')
                .append(csvVal(r.get("discount"))).append(',').append(csvVal(r.get("netAmount"))).append(',')
                .append(csvVal(r.get("gatewayName"))).append(',').append(csvVal(r.get("gatewayMode"))).append(',')
                .append(csvVal(r.get("transactionId"))).append(',').append(csvVal(r.get("paymentStatus"))).append(',')
                .append(csvVal(r.get("paymentDate"))).append(',').append(csvVal(r.get("invoiceNumber"))).append('\n');
        }
        return csvResponse("payments_report.csv", csv.toString());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Map<String, Object>> listPlans() {
        return list("SELECT id,plan_name,plan_category,monthly_price,quarterly_price,half_yearly_price,yearly_price,trial_days,description,status,created_at FROM subscription_plans ORDER BY id",
            "id", "planName", "planCategory", "monthlyPrice", "quarterlyPrice", "halfYearlyPrice", "yearlyPrice", "trialDays", "description", "status", "createdAt");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(String sql, String... keys) {
        List<Object[]> rows;
        try { rows = em.createNativeQuery(sql).getResultList(); } catch (Exception e) { return List.of(); }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < keys.length && i < r.length; i++) m.put(keys[i], r[i]);
            result.add(m);
        }
        return result;
    }

    private long count(String sql) { try { Object r = em.createNativeQuery(sql).getSingleResult(); return r == null ? 0 : ((Number) r).longValue(); } catch (Exception e) { return 0; } }
    private double dblQuery(String sql) { try { Object r = em.createNativeQuery(sql).getSingleResult(); return r == null ? 0 : ((Number) r).doubleValue(); } catch (Exception e) { return 0; } }
    private String text(Object v) { return v == null ? "" : String.valueOf(v).trim(); }
    private String textOr(Object v, String fb) { String s = text(v); return s.isBlank() ? fb : s; }
    private boolean bool(Object v) { return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v)) || "1".equals(String.valueOf(v)); }
    private int integer(Object v, int fb) { try { return v == null || text(v).isBlank() ? fb : ((Number) (v instanceof Number ? v : Double.parseDouble(String.valueOf(v)))).intValue(); } catch (Exception e) { return fb; } }
    private double dbl(Object v, double fb) { try { return v == null || text(v).isBlank() ? fb : Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return fb; } }
    private Long lng(Object v) { try { return v == null || text(v).isBlank() ? null : Long.parseLong(text(v)); } catch (Exception e) { return null; } }
    private String esc(String s) { return s == null ? "" : s.replace("'", "''"); }
    private String csvVal(Object v) { String s = v == null ? "" : String.valueOf(v); return "\"" + s.replace("\"", "\"\"") + "\""; }

    private ResponseEntity<byte[]> csvResponse(String filename, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
        return ResponseEntity.ok().headers(headers).body(bytes);
    }
}
