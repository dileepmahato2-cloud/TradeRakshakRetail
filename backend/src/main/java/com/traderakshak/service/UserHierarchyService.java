package com.traderakshak.service;

// =============================================================================
// UserHierarchyService — manages the complete 10-level user hierarchy
// Handles: create, approve, suspend, block, reset, transfer, bulk ops
// Enforces: a user can only manage types BELOW their own hierarchy level
// =============================================================================

import com.traderakshak.config.AppProperties;
import com.traderakshak.entity.User;
import com.traderakshak.exception.TradeRakshakException;
import com.traderakshak.repository.UserRepository;
import com.traderakshak.util.DateUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserHierarchyService {

    private final UserRepository userRepo;
    private final PasswordEncoder encoder;
    private final AuditService auditService;
    private final AppProperties props;

    @PersistenceContext
    private EntityManager em;

    // ── User Type definitions ──────────────────────────────────────────────────

    public static final List<String> ALL_USER_TYPES = List.of(
        "super_admin", "platform_admin", "admin", "institution_admin",
        "compliance_officer", "research_analyst", "trainer", "sales_manager",
        "support_executive", "level_1", "level_2", "level_3",
        "agent_l1", "agent_l2", "agent_l3",
        "paid_subscriber", "free_trial"
    );

    /** Return which userTypes the actor is allowed to create */
    public List<String> creatableTypes(User actor) {
        return ALL_USER_TYPES.stream()
            .filter(t -> actor.canManageUserType(t))
            .toList();
    }

    // ── Create user ────────────────────────────────────────────────────────────

    @Transactional
    public User createUser(User actor, CreateUserRequest req) {
        // 1. Hierarchy guard — cannot create equal or higher level user
        if (!actor.canManageUserType(req.getUserType())) {
            throw new TradeRakshakException("USR-001",
                "You cannot create a user of type: " + req.getUserType() +
                ". Your hierarchy level does not permit this.");
        }

        // 2. Email uniqueness
        if (userRepo.existsByEmail(req.getEmail().toLowerCase())) {
            throw new TradeRakshakException("USR-002",
                "Email already registered: " + req.getEmail());
        }

        // 3. Validate PAN if provided
        String pan = "";
        if (req.getPan() != null && !req.getPan().isBlank()) {
            pan = req.getPan().replaceAll("[^A-Z0-9]", "").toUpperCase();
            if (pan.length() != 10) {
                throw new TradeRakshakException("USR-003",
                    "Invalid PAN format. Must be 10 alphanumeric characters.");
            }
        }

        // 4. Generate TR User ID
        long seq = userRepo.countAll() + 1;
        String trUserId = "TR-" + DateUtil.currentYear() + "-" + String.format("%06d", seq);

        // 5. Generate referral code
        String referralCode = generateReferralCode(trUserId);

        // 6. Determine default plan based on user type
        String defaultPlan = switch (req.getUserType()) {
            case "super_admin", "platform_admin", "admin",
                 "compliance_officer", "research_analyst" -> "Elite";
            case "institution_admin", "trainer", "sales_manager" -> "Pro";
            case "level_1", "level_2", "support_executive" -> "Pro";
            case "level_3", "agent_l1", "agent_l2", "agent_l3" -> "Starter";
            case "paid_subscriber" -> req.getPlan() != null ? req.getPlan() : "Starter";
            default -> "Free Trial";
        };

        User user = User.builder()
            .trUserId(trUserId)
            .institutionId(req.getInstitutionId() != null ? req.getInstitutionId() : actor.getInstitutionId())
            .name(req.getName())
            .email(req.getEmail().toLowerCase())
            .password(encoder.encode(req.getPassword() != null ? req.getPassword() : "User@123"))
            .role(req.getUserType())          // backward compat
            .userType(req.getUserType())
            .level(req.getLevel() != null ? req.getLevel() : req.getUserType())
            .plan(req.getPlan() != null ? req.getPlan() : defaultPlan)
            .segments(req.getSegments() != null ? req.getSegments() : "EQ-INT,EQ-SWG")
            .status(req.getStatus() != null ? req.getStatus() : "active")
            .mobile(req.getMobile())
            .pan(pan)
            .parentUserId(req.getParentUserId() != null ? req.getParentUserId() : actor.getId())
            .createdBy(actor.getId())
            .referralCode(referralCode)
            .referredByCode(req.getReferredByCode())
            .planExpiry(req.getPlanExpiry())
            .planStatus(isInternalType(req.getUserType()) ? "internal" : "free")
            .kycStatus("pending")
            .createdAt(DateUtil.now())
            .updatedAt(DateUtil.now())
            .build();

        userRepo.save(user);

        // Track referral if applicable
        if (req.getReferredByCode() != null && !req.getReferredByCode().isBlank()) {
            trackReferral(req.getReferredByCode(), user.getId());
        }

        auditService.logActivity(actor.getId(), "UserManagement", "CreateUser", "success",
            "Created " + req.getUserType() + " : " + trUserId, "", "");

        log.info("User created: {} ({}) by {}", trUserId, req.getUserType(), actor.getTrUserId());
        return user;
    }

    // ── Single-user operations (BRD §6.1 all 12 operations) ──────────────────

    @Transactional
    public void suspendUser(User actor, Long targetId, String reason) {
        User target = getAndAuthorize(actor, targetId, "suspend");
        target.setStatus("suspended");
        target.setSuspendedReason(reason);
        target.setUpdatedAt(DateUtil.now());
        userRepo.save(target);
        auditService.logActivity(actor.getId(), "UserManagement", "SuspendUser", "success",
            "Suspended: " + target.getTrUserId() + " reason: " + reason, "", "");
    }

    @Transactional
    public void activateUser(User actor, Long targetId) {
        User target = getAndAuthorize(actor, targetId, "activate");
        target.setStatus("active");
        target.setSuspendedReason(null);
        target.setUpdatedAt(DateUtil.now());
        userRepo.save(target);
        auditService.logActivity(actor.getId(), "UserManagement", "ActivateUser", "success",
            "Activated: " + target.getTrUserId(), "", "");
    }

    @Transactional
    public void blockUser(User actor, Long targetId, String reason) {
        User target = getAndAuthorize(actor, targetId, "block");
        target.setStatus("blocked");
        target.setSuspendedReason(reason);
        target.setUpdatedAt(DateUtil.now());
        userRepo.save(target);
        auditService.logActivity(actor.getId(), "UserManagement", "BlockUser", "success",
            "Blocked: " + target.getTrUserId(), "", "");
    }

    @Transactional
    public String resetPassword(User actor, Long targetId, String newPassword) {
        User target = getAndAuthorize(actor, targetId, "reset_password");
        String generated = newPassword != null ? newPassword
            : "Tr@" + (int)(Math.random() * 90000 + 10000);
        target.setPassword(encoder.encode(generated));
        target.setFailedLoginCount(0);
        target.setLockoutUntil(null);
        target.setUpdatedAt(DateUtil.now());
        userRepo.save(target);
        auditService.logActivity(actor.getId(), "UserManagement", "ResetPassword", "success",
            "Reset PW for: " + target.getTrUserId(), "", "");
        return generated;
    }

    @Transactional
    public void unlockUser(User actor, Long targetId) {
        User target = getAndAuthorize(actor, targetId, "unlock");
        target.setFailedLoginCount(0);
        target.setLockoutUntil(null);
        target.setUpdatedAt(DateUtil.now());
        userRepo.save(target);
    }

    @Transactional
    public void approveUser(User actor, Long targetId) {
        User target = getAndAuthorize(actor, targetId, "approve");
        target.setApprovedBy(actor.getId());
        target.setApprovedAt(DateUtil.now());
        target.setStatus("active");
        target.setUpdatedAt(DateUtil.now());
        userRepo.save(target);
        auditService.logActivity(actor.getId(), "UserManagement", "ApproveUser", "success",
            "Approved: " + target.getTrUserId(), "", "");
    }

    @Transactional
    public void changeUserType(User actor, Long targetId, String newType) {
        if (!actor.canManageUserType(newType)) {
            throw new TradeRakshakException("USR-004",
                "Cannot assign type " + newType + " — exceeds your hierarchy level.");
        }
        User target = getAndAuthorize(actor, targetId, "change_type");
        target.setUserType(newType);
        target.setRole(newType);
        target.setUpdatedAt(DateUtil.now());
        userRepo.save(target);
        auditService.logActivity(actor.getId(), "UserManagement", "ChangeUserType", "success",
            target.getTrUserId() + " → " + newType, "", "");
    }

    @Transactional
    public void transferUser(User actor, Long targetId, Long newParentId) {
        User target = getAndAuthorize(actor, targetId, "transfer");
        User newParent = userRepo.findById(newParentId)
            .orElseThrow(() -> new TradeRakshakException("USR-005", "New parent user not found."));
        if (!actor.canManageUserType(newParent.getUserType())) {
            throw new TradeRakshakException("USR-006", "Cannot transfer to specified parent.");
        }
        target.setParentUserId(newParentId);
        target.setUpdatedAt(DateUtil.now());
        userRepo.save(target);
    }

    @Transactional
    public void generateApiKey(User actor, Long targetId) {
        User target = getAndAuthorize(actor, targetId, "api_key");
        String apiKey = "TR-API-" + UUID.randomUUID().toString().replace("-", "").substring(0, 32).toUpperCase();
        target.setApiKey(apiKey);
        target.setUpdatedAt(DateUtil.now());
        userRepo.save(target);
    }

    // ── Bulk operations ────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> bulkCreate(User actor, List<CreateUserRequest> requests) {
        int success = 0, failed = 0;
        List<String> errors = new ArrayList<>();
        for (CreateUserRequest req : requests) {
            try {
                createUser(actor, req);
                success++;
            } catch (Exception e) {
                failed++;
                errors.add(req.getEmail() + ": " + e.getMessage());
            }
        }
        return Map.of("success", success, "failed", failed, "errors", errors);
    }

    @Transactional
    public int bulkUpdatePlan(User actor, List<Long> userIds, String newPlan) {
        int count = 0;
        for (Long uid : userIds) {
            try {
                User target = getAndAuthorize(actor, uid, "bulk_plan");
                target.setPlan(newPlan);
                target.setUpdatedAt(DateUtil.now());
                userRepo.save(target);
                count++;
            } catch (Exception ignored) {}
        }
        return count;
    }

    @Transactional
    public int bulkSuspend(User actor, List<Long> userIds, String reason) {
        int count = 0;
        for (Long uid : userIds) {
            try { suspendUser(actor, uid, reason); count++; }
            catch (Exception ignored) {}
        }
        return count;
    }

    // ── User tree / hierarchy reporting ───────────────────────────────────────

    public Map<String, Object> getUserTree(User actor) {
        // Returns hierarchical tree of users the actor can manage
        List<User> manageable = userRepo.findAll().stream()
            .filter(u -> !u.getId().equals(actor.getId()))
            .filter(u -> actor.canManageUserType(u.getUserType()))
            .toList();

        // Group by parent
        Map<Long, List<Map<String, Object>>> byParent = new HashMap<>();
        for (User u : manageable) {
            Long parentId = u.getParentUserId() != null ? u.getParentUserId() : 0L;
            byParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(userSummary(u));
        }

        return Map.of("tree", byParent, "total", manageable.size(),
                      "by_type", groupByType(manageable));
    }

    public Map<String, Long> getHierarchySummary(User actor) {
        Map<String, Long> summary = new LinkedHashMap<>();
        for (String type : ALL_USER_TYPES) {
            if (actor.canManageUserType(type)) {
                long count = userRepo.findAll().stream()
                    .filter(u -> type.equals(u.getUserType())).count();
                summary.put(type, count);
            }
        }
        return summary;
    }

    // ── Permission check ───────────────────────────────────────────────────────

    public boolean hasPermission(User user, String module, String action) {
        if (user != null && user.isSuperAdmin()) return true;
        try {
            Object result = em.createNativeQuery(
                "SELECT can_" + action + " FROM permissions WHERE user_type=:ut AND module=:mod")
                .setParameter("ut", User.normalizeRole(user.getUserType()))
                .setParameter("mod", module)
                .getSingleResult();
            return result != null && ((Number) result).intValue() == 1;
        } catch (Exception e) {
            return false;
        }
    }

    public Map<String, Object> getMyPermissions(User user) {
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                "SELECT module, can_view, can_create, can_edit, can_delete, can_approve, can_export " +
                "FROM permissions WHERE user_type=:ut")
                .setParameter("ut", User.normalizeRole(user.getUserType()))
                .getResultList();

            Map<String, Object> perms = new LinkedHashMap<>();
            for (Object[] row : rows) {
                perms.put((String) row[0], Map.of(
                    "view",   row[1], "create", row[2], "edit",   row[3],
                    "delete", row[4], "approve", row[5], "export", row[6]));
            }
            return perms;
        } catch (Exception e) {
            return Map.of();
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private User getAndAuthorize(User actor, Long targetId, String action) {
        User target = userRepo.findById(targetId)
            .orElseThrow(() -> new TradeRakshakException("USR-404", "User not found: " + targetId));
        if (!actor.canManageUserType(target.getUserType()) && !actor.getId().equals(targetId)) {
            throw new TradeRakshakException("USR-403",
                "Insufficient hierarchy level to " + action + " this user.");
        }
        return target;
    }

    private String generateReferralCode(String trUserId) {
        return "TR" + trUserId.replace("TR-", "").replace("-", "") +
               Integer.toHexString((int)(Math.random() * 65535)).toUpperCase();
    }

    private void trackReferral(String code, Long referredUserId) {
        try {
            userRepo.findAll().stream()
                .filter(u -> code.equals(u.getReferralCode()))
                .findFirst()
                .ifPresent(referrer -> {
                    em.createNativeQuery(
                        "INSERT INTO referrals(referrer_code,referrer_user_id,referred_user_id,referred_at,status) VALUES(?,?,?,?,'pending')")
                        .setParameter(1, code).setParameter(2, referrer.getId())
                        .setParameter(3, referredUserId).setParameter(4, DateUtil.now())
                        .executeUpdate();
                });
        } catch (Exception e) {
            log.debug("Referral tracking error: {}", e.getMessage());
        }
    }

    private boolean isInternalType(String type) {
        return List.of("super_admin","platform_admin","admin","institution_admin",
            "compliance_officer","research_analyst","trainer","sales_manager",
            "support_executive").contains(type);
    }

    private Map<String, Object> userSummary(User u) {
        return Map.of("id", u.getId(), "trUserId", u.getTrUserId() != null ? u.getTrUserId() : "",
                      "name", u.getName() != null ? u.getName() : "",
                      "email", u.getEmail(), "userType", u.getUserType() != null ? u.getUserType() : "",
                      "plan", u.getPlan() != null ? u.getPlan() : "",
                      "status", u.getStatus() != null ? u.getStatus() : "");
    }

    private Map<String, Long> groupByType(List<User> users) {
        Map<String, Long> grouped = new LinkedHashMap<>();
        for (User u : users) {
            String type = u.getUserType() != null ? u.getUserType() : "unknown";
            grouped.merge(type, 1L, Long::sum);
        }
        return grouped;
    }

    // ── Inner request class ────────────────────────────────────────────────────

    @lombok.Data
    public static class CreateUserRequest {
        private String name, email, password, mobile, pan;
        private String userType, level, plan, segments, status;
        private Long institutionId, parentUserId;
        private String planExpiry, referredByCode, notes;
    }
}
