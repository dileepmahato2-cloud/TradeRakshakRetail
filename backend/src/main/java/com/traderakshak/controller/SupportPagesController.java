package com.traderakshak.controller;

import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.entity.User;
import com.traderakshak.util.DateUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
public class SupportPagesController {
    @PersistenceContext
    private EntityManager em;
    private final PasswordEncoder passwordEncoder;

    public SupportPagesController(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<Map<String, Object>>> profile(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(loadProfile(user)));
    }

    @PutMapping("/profile")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateProfile(@AuthenticationPrincipal User user,
                                                                          @RequestBody Map<String, Object> req) {
        Long userId = userId(user);
        if (userId == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("PROFILE-USER", "Please login again."));
        }
        String name = text(req.get("name"));
        String email = text(req.get("email"));
        String mobile = text(req.get("mobile"));
        String password = text(req.get("password"));
        if (name.isBlank() || email.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("PROFILE-REQUIRED", "Full name and email are required."));
        }
        try {
            if (password.isBlank()) {
                em.createNativeQuery("UPDATE users SET name=?, email=?, mobile=?, updated_at=? WHERE id=?")
                    .setParameter(1, name)
                    .setParameter(2, email)
                    .setParameter(3, mobile)
                    .setParameter(4, DateUtil.now())
                    .setParameter(5, userId)
                    .executeUpdate();
            } else {
                em.createNativeQuery("UPDATE users SET name=?, email=?, mobile=?, password=?, updated_at=? WHERE id=?")
                    .setParameter(1, name)
                    .setParameter(2, email)
                    .setParameter(3, mobile)
                    .setParameter(4, passwordEncoder.encode(password))
                    .setParameter(5, DateUtil.now())
                    .setParameter(6, userId)
                    .executeUpdate();
            }
            return ResponseEntity.ok(ApiResponse.ok("Profile updated.", loadProfile(user)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("PROFILE-SAVE", "Profile could not be updated. Check email is not already used."));
        }
    }

    @GetMapping("/issues")
    public ResponseEntity<ApiResponse<Map<String, Object>>> issues(@AuthenticationPrincipal User user) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("modules", List.of("Strategy Builder", "Backtesting", "Paper Trading", "Trade Journal", "Market Data",
            "Watchlist", "Checklist/Gate", "Broker API", "Login/Auth", "Data Warehouse", "Profile", "Other"));
        data.put("issueTypes", List.of("Bug", "Data Error", "Performance", "UI Problem", "Feature Request", "Other"));
        data.put("severities", List.of("High", "Medium", "Low"));
        data.put("issues", loadIssues(user));
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    @PostMapping("/issues")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> createIssue(@AuthenticationPrincipal User user,
                                                                        @RequestBody Map<String, Object> req) {
        Long userId = userId(user);
        if (userId == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("ISSUE-USER", "Please login again."));
        }
        String module = text(req.get("module"));
        String issueType = text(req.get("issueType"));
        String description = text(req.get("description"));
        String severity = text(req.get("severity"));
        if (module.isBlank() || issueType.isBlank() || description.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("ISSUE-REQUIRED", "Module, issue type and description are required."));
        }
        if (severity.isBlank()) severity = "Medium";
        String ticketId = "TR-" + DateUtil.today().replace("-", "") + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        em.createNativeQuery("INSERT INTO support_tickets(ticket_id,user_id,institution_id,category,subject,description,priority,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)")
            .setParameter(1, ticketId)
            .setParameter(2, userId)
            .setParameter(3, institutionId(user))
            .setParameter(4, module)
            .setParameter(5, issueType)
            .setParameter(6, description)
            .setParameter(7, severity)
            .setParameter(8, "Open")
            .setParameter(9, DateUtil.now())
            .setParameter(10, DateUtil.now())
            .executeUpdate();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("issues", loadIssues(user));
        data.put("ticketId", ticketId);
        return ResponseEntity.ok(ApiResponse.ok("Issue submitted.", data));
    }

    @GetMapping("/feedback")
    public ResponseEntity<ApiResponse<Map<String, Object>>> feedback(@AuthenticationPrincipal User user) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sections", List.of("Strategy Builder", "Backtesting", "Paper Trading", "Trade Journal", "Market Data",
            "Data Warehouse", "Broker API", "Checklist", "Overall App", "Profile", "Other"));
        data.put("priorities", List.of("High", "Medium", "Low"));
        data.put("feedback", loadFeedback(user));
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    @PostMapping("/feedback")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> createFeedback(@AuthenticationPrincipal User user,
                                                                          @RequestBody Map<String, Object> req) {
        Long userId = userId(user);
        if (userId == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("FEEDBACK-USER", "Please login again."));
        }
        String section = text(req.get("section"));
        String subSection = text(req.get("subSection"));
        String message = text(req.get("message"));
        String priority = text(req.get("priority"));
        if (section.isBlank() || message.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("FEEDBACK-REQUIRED", "Section and feedback are required."));
        }
        if (priority.isBlank()) priority = "Medium";
        em.createNativeQuery("INSERT INTO feedback(user_id,section,sub_section,message,priority,status,created_at) VALUES(?,?,?,?,?,?,?)")
            .setParameter(1, userId)
            .setParameter(2, section)
            .setParameter(3, subSection)
            .setParameter(4, message)
            .setParameter(5, priority)
            .setParameter(6, "Open")
            .setParameter(7, DateUtil.now())
            .executeUpdate();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("feedback", loadFeedback(user));
        return ResponseEntity.ok(ApiResponse.ok("Feedback submitted.", data));
    }

    @GetMapping("/feedback/download")
    public ResponseEntity<byte[]> downloadFeedback(@AuthenticationPrincipal User user) {
        List<Map<String, Object>> rows = isAdmin(user) ? loadAllFeedback() : loadFeedback(user);
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("id,section,sub_section,message,priority,status,created_at\n");
        for (Map<String, Object> row : rows) {
            csv.append(csv(row.get("id"))).append(',')
                .append(csv(row.get("section"))).append(',')
                .append(csv(row.get("subSection"))).append(',')
                .append(csv(row.get("message"))).append(',')
                .append(csv(row.get("priority"))).append(',')
                .append(csv(row.get("status"))).append(',')
                .append(csv(row.get("createdAt"))).append('\n');
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.set("Content-Disposition", "attachment; filename=\"feedback_details_" + DateUtil.today() + ".csv\"");
        return ResponseEntity.ok().headers(headers).body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> loadProfile(User user) {
        Long userId = userId(user);
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("name", user == null ? "" : nullToBlank(user.getName()));
        fallback.put("email", user == null ? "" : nullToBlank(user.getEmail()));
        fallback.put("mobile", user == null ? "" : nullToBlank(user.getMobile()));
        if (userId == null) return fallback;
        try {
            Object[] row = (Object[]) em.createNativeQuery(
                "SELECT id,name,email,mobile,role,user_type,level,plan,status,pan,otp_enabled,created_at,consent_accepted_at,plan_status FROM users WHERE id=?")
                .setParameter(1, userId)
                .getSingleResult();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", row[0]);
            data.put("name", nullToBlank(row[1]));
            data.put("email", nullToBlank(row[2]));
            data.put("mobile", nullToBlank(row[3]));
            data.put("role", first(row[5], row[4], "paid_subscriber"));
            data.put("level", first(row[6], row[5], "-"));
            data.put("plan", first(row[7], row[13], "Free"));
            data.put("status", first(row[8], "active"));
            data.put("pan", maskPan(row[9]));
            data.put("otpEnabled", "1".equals(String.valueOf(row[10])) || "true".equalsIgnoreCase(String.valueOf(row[10])));
            data.put("registered", nullToBlank(row[11]));
            data.put("consentAt", nullToBlank(row[12]));
            return data;
        } catch (Exception e) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadIssues(User user) {
        Long userId = userId(user);
        if (userId == null) return List.of();
        List<Object[]> rows = em.createNativeQuery(
            "SELECT id,ticket_id,category,subject,description,priority,status,created_at FROM support_tickets WHERE user_id=? ORDER BY id DESC")
            .setParameter(1, userId)
            .getResultList();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row[0]);
            item.put("ticketId", row[1]);
            item.put("module", row[2]);
            item.put("issueType", row[3]);
            item.put("description", row[4]);
            item.put("severity", row[5]);
            item.put("status", row[6]);
            item.put("createdAt", row[7]);
            list.add(item);
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadFeedback(User user) {
        Long userId = userId(user);
        if (userId == null) return List.of();
        List<Object[]> rows = em.createNativeQuery(
            "SELECT id,section,sub_section,message,priority,status,created_at FROM feedback WHERE user_id=? ORDER BY id DESC")
            .setParameter(1, userId)
            .getResultList();
        return mapFeedbackRows(rows);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadAllFeedback() {
        List<Object[]> rows = em.createNativeQuery(
            "SELECT id,section,sub_section,message,priority,status,created_at FROM feedback ORDER BY id DESC")
            .getResultList();
        return mapFeedbackRows(rows);
    }

    private List<Map<String, Object>> mapFeedbackRows(List<Object[]> rows) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row[0]);
            item.put("section", row[1]);
            item.put("subSection", row[2]);
            item.put("message", row[3]);
            item.put("priority", row[4]);
            item.put("status", row[5]);
            item.put("createdAt", row[6]);
            list.add(item);
        }
        return list;
    }

    private Long userId(User user) {
        return user == null ? null : user.getId();
    }

    private Long institutionId(User user) {
        return user == null || user.getInstitutionId() == null ? 1L : user.getInstitutionId();
    }

    private boolean isAdmin(User user) {
        return user != null && user.isAdmin();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String nullToBlank(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String first(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank() && !"null".equalsIgnoreCase(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    private String maskPan(Object pan) {
        String value = nullToBlank(pan);
        if (value.isBlank()) return "Not provided";
        if (value.length() <= 4) return value;
        return "******" + value.substring(value.length() - 4);
    }

    private String csv(Object value) {
        String text = nullToBlank(value).replace("\r", " ").replace("\n", " ");
        if (text.contains(",") || text.contains("\"")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
