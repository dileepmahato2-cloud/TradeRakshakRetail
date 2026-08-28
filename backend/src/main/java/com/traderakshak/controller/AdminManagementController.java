package com.traderakshak.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/admin/management")
public class AdminManagementController {
    @PersistenceContext
    private EntityManager em;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper mapper;

    public AdminManagementController(PasswordEncoder passwordEncoder, ObjectMapper mapper) {
        this.passwordEncoder = passwordEncoder;
        this.mapper = mapper;
    }

    @GetMapping("/users/dashboard")
    public ResponseEntity<ApiResponse<Map<String, Object>>> usersDashboard() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", Map.of(
            "totalUsers", count("SELECT COUNT(*) FROM users"),
            "activeUsers", count("SELECT COUNT(*) FROM users WHERE status='active'"),
            "inactiveUsers", count("SELECT COUNT(*) FROM users WHERE status<>'active'"),
            "realTradingEnabled", count("SELECT COUNT(*) FROM user_trading_permissions WHERE real_trading_enabled=1")
        ));
        data.put("users", users());
        data.put("institutions", simpleList("SELECT id,name,status FROM institutions ORDER BY name LIMIT 200", "id", "name", "status"));
        data.put("brokers", simpleList("SELECT id,broker_label,broker_name FROM broker_credentials ORDER BY broker_label LIMIT 200", "id", "label", "name"));
        data.put("roles", List.of("super_admin", "platform_admin", "admin", "institution_admin", "user", "level_1", "level_2", "level_3", "paid_subscriber", "free_trial"));
        data.put("plans", List.of("Free Trial", "Starter", "Pro", "Elite", "Institutional"));
        data.put("segments", List.of("EQ-INT", "EQ-SWG", "EQ-POS", "FUT", "OPT-CE", "OPT-PE", "IDX"));
        data.put("audit", auditRows());
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    @PostMapping("/users")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> saveUser(@RequestBody Map<String, Object> req,
                                                                     @AuthenticationPrincipal User actor) {
        Long id = lng(req.get("id"));
        String email = text(req.get("email")).toLowerCase(Locale.ROOT);
        String name = text(req.get("name"));
        if (name.isBlank() || email.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("USER-REQUIRED", "Name and email are required."));
        }
        String segments = joinList(req.get("segments"));
        if (segments.isBlank()) segments = "EQ-INT,EQ-SWG";
        Long institutionId = lng(req.get("institutionId"));
        if (institutionId == null) institutionId = 1L;
        if (id == null) {
            String password = text(req.get("password"));
            if (password.isBlank()) password = "Change@123";
            String trUserId = "TR-" + DateUtil.today().replace("-", "") + "-" + (count("SELECT COUNT(*) FROM users") + 1);
            em.createNativeQuery("INSERT INTO users(tr_user_id,institution_id,name,email,password,role,user_type,level,plan,segments,status,mobile,otp_enabled,plan_expiry,created_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
                .setParameter(1, trUserId)
                .setParameter(2, institutionId)
                .setParameter(3, name)
                .setParameter(4, email)
                .setParameter(5, passwordEncoder.encode(password))
                .setParameter(6, textOr(req.get("role"), "user"))
                .setParameter(7, textOr(req.get("role"), "user"))
                .setParameter(8, textOr(req.get("level"), "level_1"))
                .setParameter(9, textOr(req.get("plan"), "Free Trial"))
                .setParameter(10, segments)
                .setParameter(11, textOr(req.get("status"), "active"))
                .setParameter(12, text(req.get("mobile")))
                .setParameter(13, bool(req.get("otpEnabled")) ? 1 : 0)
                .setParameter(14, text(req.get("planExpiry")))
                .setParameter(15, actor == null ? null : actor.getId())
                .setParameter(16, DateUtil.now())
                .setParameter(17, DateUtil.now())
                .executeUpdate();
            Long createdId = lng(single("SELECT id FROM users WHERE email='" + escSql(email) + "'"));
            saveTradingPermission(createdId, institutionId, req, actor);
            audit(actor, createdId, "USER_CREATED", req);
            return ResponseEntity.ok(ApiResponse.ok("User created.", Map.of("users", users())));
        }
        em.createNativeQuery("UPDATE users SET institution_id=?,name=?,email=?,role=?,user_type=?,level=?,plan=?,segments=?,status=?,mobile=?,otp_enabled=?,plan_expiry=?,updated_at=? WHERE id=?")
            .setParameter(1, institutionId)
            .setParameter(2, name)
            .setParameter(3, email)
            .setParameter(4, textOr(req.get("role"), "user"))
            .setParameter(5, textOr(req.get("role"), "user"))
            .setParameter(6, textOr(req.get("level"), "level_1"))
            .setParameter(7, textOr(req.get("plan"), "Free Trial"))
            .setParameter(8, segments)
            .setParameter(9, textOr(req.get("status"), "active"))
            .setParameter(10, text(req.get("mobile")))
            .setParameter(11, bool(req.get("otpEnabled")) ? 1 : 0)
            .setParameter(12, text(req.get("planExpiry")))
            .setParameter(13, DateUtil.now())
            .setParameter(14, id)
            .executeUpdate();
        saveTradingPermission(id, institutionId, req, actor);
        audit(actor, id, "USER_UPDATED", req);
        return ResponseEntity.ok(ApiResponse.ok("User updated.", Map.of("users", users())));
    }

    @PutMapping("/users/{id}/status")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateUserStatus(@PathVariable Long id,
                                                                             @RequestBody Map<String, Object> req,
                                                                             @AuthenticationPrincipal User actor) {
        String status = textOr(req.get("status"), "inactive");
        em.createNativeQuery("UPDATE users SET status=?,updated_at=? WHERE id=?")
            .setParameter(1, status).setParameter(2, DateUtil.now()).setParameter(3, id).executeUpdate();
        audit(actor, id, "USER_STATUS_" + status.toUpperCase(Locale.ROOT), req);
        return ResponseEntity.ok(ApiResponse.ok("User status updated.", Map.of("users", users())));
    }

    @PostMapping("/users/{id}/reset-password")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> resetPassword(@PathVariable Long id,
                                                                          @RequestBody Map<String, Object> req,
                                                                          @AuthenticationPrincipal User actor) {
        String password = textOr(req.get("password"), "Change@123");
        em.createNativeQuery("UPDATE users SET password=?,updated_at=? WHERE id=?")
            .setParameter(1, passwordEncoder.encode(password)).setParameter(2, DateUtil.now()).setParameter(3, id).executeUpdate();
        audit(actor, id, "PASSWORD_RESET", Map.of("temporaryPasswordSet", true));
        return ResponseEntity.ok(ApiResponse.ok("Password reset completed.", Map.of("users", users())));
    }

    @PostMapping(value = "/users/bulk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> bulkUpload(@RequestParam("file") MultipartFile file,
                                                                       @AuthenticationPrincipal User actor) throws Exception {
        String csv = new String(file.getBytes(), StandardCharsets.UTF_8);
        int imported = 0;
        for (String line : csv.split("\\R")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("name,email") || line.isBlank()) continue;
            String[] p = line.split(",", -1);
            if (p.length < 2 || p[0].isBlank() || p[1].isBlank()) continue;
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("name", p[0].trim());
            req.put("email", p[1].trim());
            req.put("mobile", p.length > 2 ? p[2].trim() : "");
            req.put("role", p.length > 3 ? p[3].trim() : "user");
            req.put("level", p.length > 4 ? p[4].trim() : "level_1");
            req.put("plan", p.length > 5 ? p[5].trim() : "Free Trial");
            req.put("status", p.length > 6 ? p[6].trim() : "active");
            req.put("segments", p.length > 7 ? p[7].trim() : "EQ-INT,EQ-SWG");
            req.put("password", p.length > 8 && !p[8].isBlank() ? p[8].trim() : "Change@123");
            saveUser(req, actor);
            imported++;
        }
        audit(actor, null, "USER_BULK_UPLOAD", Map.of("imported", imported, "file", file.getOriginalFilename()));
        return ResponseEntity.ok(ApiResponse.ok(imported + " users processed.", Map.of("users", users(), "imported", imported)));
    }

    @GetMapping("/users/template")
    public ResponseEntity<byte[]> userTemplate() {
        return csv("user_bulk_template.csv", "name,email,mobile,role,level,plan,status,segments,password\n");
    }

    @GetMapping("/users/download")
    public ResponseEntity<byte[]> usersDownload() {
        StringBuilder sb = new StringBuilder("tr_user_id,name,email,mobile,role,level,plan,status,plan_expiry,segments,institution_id,last_login,login_count\n");
        for (Map<String, Object> u : users()) {
            sb.append(csv(u.get("trUserId"))).append(',').append(csv(u.get("name"))).append(',').append(csv(u.get("email"))).append(',')
                .append(csv(u.get("mobile"))).append(',').append(csv(u.get("role"))).append(',').append(csv(u.get("level"))).append(',')
                .append(csv(u.get("plan"))).append(',').append(csv(u.get("status"))).append(',').append(csv(u.get("planExpiry"))).append(',')
                .append(csv(u.get("segments"))).append(',').append(csv(u.get("institutionId"))).append(',').append(csv(u.get("lastLogin"))).append(',')
                .append(csv(u.get("loginCount"))).append('\n');
        }
        return csv("users_download.csv", sb.toString());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> users() {
        List<Object[]> rows = em.createNativeQuery(
            "SELECT u.id,u.tr_user_id,u.name,u.email,u.mobile,u.role,u.user_type,u.level,u.plan,u.status,u.plan_expiry,u.segments,u.institution_id,COALESCE(i.name,''),u.last_login,u.login_count,COALESCE(tp.paper_trading_enabled,1),COALESCE(tp.real_trading_enabled,0),COALESCE(tp.max_order_value,0) " +
            "FROM users u LEFT JOIN institutions i ON i.id=u.institution_id LEFT JOIN user_trading_permissions tp ON tp.user_id=u.id ORDER BY u.id DESC LIMIT 500")
            .getResultList();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            String[] keys = {"id", "trUserId", "name", "email", "mobile", "role", "userType", "level", "plan", "status", "planExpiry", "segments", "institutionId", "institutionName", "lastLogin", "loginCount", "paperTrading", "realTrading", "maxOrderValue"};
            for (int i = 0; i < keys.length; i++) m.put(keys[i], r[i]);
            list.add(m);
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(String sql, String... keys) {
        List<Object[]> rows = em.createNativeQuery(sql).getResultList();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < keys.length && i < r.length; i++) m.put(keys[i], r[i]);
            list.add(m);
        }
        return list;
    }

    private List<Map<String, Object>> simpleList(String sql, String... keys) { return list(sql, keys); }

    private List<Map<String, Object>> auditRows() {
        return list("SELECT activity_type,details_json,created_at FROM user_management_audit ORDER BY id DESC LIMIT 50", "activityType", "details", "createdAt");
    }

    private void saveTradingPermission(Long userId, Long institutionId, Map<String, Object> req, User actor) {
        if (userId == null) return;
        int updated = em.createNativeQuery("UPDATE user_trading_permissions SET institution_id=?,paper_trading_enabled=?,real_trading_enabled=?,max_order_value=?,max_daily_order_value=?,allowed_segments=?,broker_ids=?,updated_by=?,updated_at=? WHERE user_id=?")
            .setParameter(1, institutionId).setParameter(2, bool(req.get("paperTrading")) ? 1 : 0).setParameter(3, bool(req.get("realTrading")) ? 1 : 0)
            .setParameter(4, dbl(req.get("maxOrderValue"), 0)).setParameter(5, dbl(req.get("maxDailyOrderValue"), 0))
            .setParameter(6, joinList(req.get("segments"))).setParameter(7, joinList(req.get("brokerIds")))
            .setParameter(8, actor == null ? null : actor.getId()).setParameter(9, DateUtil.now()).setParameter(10, userId).executeUpdate();
        if (updated == 0) {
            em.createNativeQuery("INSERT INTO user_trading_permissions(user_id,institution_id,paper_trading_enabled,real_trading_enabled,max_order_value,max_daily_order_value,allowed_segments,broker_ids,updated_by,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)")
                .setParameter(1, userId).setParameter(2, institutionId).setParameter(3, bool(req.get("paperTrading")) ? 1 : 0).setParameter(4, bool(req.get("realTrading")) ? 1 : 0)
                .setParameter(5, dbl(req.get("maxOrderValue"), 0)).setParameter(6, dbl(req.get("maxDailyOrderValue"), 0)).setParameter(7, joinList(req.get("segments")))
                .setParameter(8, joinList(req.get("brokerIds"))).setParameter(9, actor == null ? null : actor.getId()).setParameter(10, DateUtil.now()).executeUpdate();
        }
    }

    private void audit(User actor, Long targetUserId, String type, Object details) {
        try {
            em.createNativeQuery("INSERT INTO user_management_audit(actor_user_id,target_user_id,institution_id,activity_type,details_json,created_at) VALUES(?,?,?,?,?,?)")
                .setParameter(1, actor == null ? null : actor.getId())
                .setParameter(2, targetUserId)
                .setParameter(3, actor == null ? 1L : actor.getInstitutionId())
                .setParameter(4, type)
                .setParameter(5, mapper.writeValueAsString(details))
                .setParameter(6, DateUtil.now())
                .executeUpdate();
        } catch (Exception ignored) {}
    }

    private ResponseEntity<byte[]> csv(String fileName, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDisposition(ContentDisposition.attachment().filename(fileName).build());
        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    private String csv(Object value) {
        String s = value == null ? "" : String.valueOf(value);
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private long count(String sql) {
        try { Object r = em.createNativeQuery(sql).getSingleResult(); return r == null ? 0 : ((Number) r).longValue(); } catch (Exception e) { return 0; }
    }
    private Object single(String sql) { try { return em.createNativeQuery(sql).getSingleResult(); } catch (Exception e) { return null; } }
    private String text(Object v) { return v == null ? "" : String.valueOf(v).trim(); }
    private String textOr(Object v, String fallback) { String s = text(v); return s.isBlank() ? fallback : s; }
    private boolean bool(Object v) { return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v)) || "1".equals(String.valueOf(v)); }
    private int integer(Object v, int fallback) { try { return v == null || text(v).isBlank() ? fallback : ((Number) (v instanceof Number ? v : Double.parseDouble(String.valueOf(v)))).intValue(); } catch (Exception e) { return fallback; } }
    private double dbl(Object v, double fallback) { try { return v == null || text(v).isBlank() ? fallback : Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return fallback; } }
    private Long lng(Object v) { try { return v == null || text(v).isBlank() ? null : Long.parseLong(text(v)); } catch (Exception e) { return null; } }
    private String escSql(String s) { return s == null ? "" : s.replace("'", "''"); }
    private String joinList(Object value) {
        if (value instanceof List<?> l) {
            List<String> parts = new ArrayList<>();
            for (Object o : l) if (!text(o).isBlank()) parts.add(text(o));
            return String.join(",", parts);
        }
        return text(value);
    }
}
