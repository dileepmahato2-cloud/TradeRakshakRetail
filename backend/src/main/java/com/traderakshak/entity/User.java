package com.traderakshak.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * TradeRakshak™ User Entity
 * Supports 10 user types + 3 agent levels (full hierarchy)
 */
@Entity
@Table(name = "users")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class User implements UserDetails {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tr_user_id", unique = true)
    private String trUserId;

    @Column(name = "institution_id") @Builder.Default
    private Long institutionId = 1L;

    private String name;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    @JsonIgnore
    private String password;

    /** Legacy role field — kept for backward compat */
    private String role;

    /** User type from hierarchy: super_admin, platform_admin, admin,
     *  institution_admin, compliance_officer, research_analyst, trainer,
     *  sales_manager, support_executive, level_1, level_2, level_3,
     *  agent_l1, agent_l2, agent_l3, paid_subscriber, free_trial */
    @Column(name = "user_type") @Builder.Default
    private String userType = "paid_subscriber";

    private String level;
    private String plan;

    @Builder.Default private String segments = "EQ-INT,EQ-SWG";
    @Builder.Default private String status = "active";

    private String mobile;

    @Column(name = "otp_enabled") @Builder.Default
    private int otpEnabled = 0;

    @JsonIgnore
    private String pan;

    @Column(name = "kyc_status") @Builder.Default
    private String kycStatus = "pending";

    @Column(name = "kyc_documents") private String kycDocuments;
    @Column(name = "kyc_verified_at") private String kycVerifiedAt;

    @Column(name = "parent_user_id") private Long parentUserId;
    @Column(name = "created_by")     private Long createdBy;
    @Column(name = "approved_by")    private Long approvedBy;
    @Column(name = "approved_at")    private String approvedAt;

    @Column(name = "referral_code", unique = true) private String referralCode;
    @Column(name = "referred_by_code")             private String referredByCode;

    @Column(name = "wallet_balance")  @Builder.Default private double walletBalance = 0.0;
    @Column(name = "wallet_credits")  @Builder.Default private double walletCredits = 0.0;

    @Column(name = "api_key", unique = true) private String apiKey;
    @Column(name = "api_calls_today") @Builder.Default private int apiCallsToday = 0;
    @Column(name = "api_calls_limit") @Builder.Default private int apiCallsLimit = 0;

    @Column(name = "consent_accepted_at") private String consentAcceptedAt;
    @Column(name = "last_login")          private String lastLogin;
    @Column(name = "login_count")   @Builder.Default private int loginCount = 0;
    @Column(name = "failed_login_count") @Builder.Default private int failedLoginCount = 0;
    @Column(name = "lockout_until") private String lockoutUntil;

    @Column(name = "plan_expiry")   private String planExpiry;
    @Column(name = "plan_status")   @Builder.Default private String planStatus = "free";
    @Column(name = "subscription_id") private String subscriptionId;
    @Column(name = "trial_ends_at") private String trialEndsAt;
    @Column(name = "auto_renew")    @Builder.Default private int autoRenew = 1;

    @Column(name = "theme_preference") @Builder.Default private String themePreference = "dark_pro";
    @Column(name = "suspended_reason") private String suspendedReason;
    @Column(name = "device_hash")      private String deviceHash;
    private String notes;
    private String tags;

    @Column(name = "created_at") private String createdAt;
    @Column(name = "updated_at") private String updatedAt;

    // ── Spring Security ───────────────────────────────────────────────────────

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        String effectiveRole = userType != null ? userType : (role != null ? role : "paid_subscriber");
        return List.of(new SimpleGrantedAuthority("ROLE_" + normalizeRole(effectiveRole).toUpperCase()));
    }

    @Override public String getUsername() { return email; }
    @Override public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() {
        if (lockoutUntil == null) return true;
        try {
            return LocalDateTime.parse(lockoutUntil.replace(" ", "T"))
                    .isBefore(LocalDateTime.now());
        } catch (Exception e) { return true; }
    }

    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return "active".equalsIgnoreCase(status); }

    // ── Hierarchy helpers ─────────────────────────────────────────────────────

    public boolean isSuperAdmin()       { return "super_admin".equals(normalizeRole(userType)); }
    public boolean isPlatformAdmin()    { return "platform_admin".equals(normalizeRole(userType)); }
    public boolean isAdmin()            {
        String type = normalizeRole(userType);
        return !type.isBlank() && (type.contains("admin") || isSuperAdmin() || isPlatformAdmin());
    }
    public boolean isInstitutionAdmin() { return "institution_admin".equals(normalizeRole(userType)); }
    public boolean isComplianceOfficer(){ return "compliance_officer".equals(normalizeRole(userType)); }
    public boolean isResearchAnalyst()  { return "research_analyst".equals(normalizeRole(userType)); }
    public boolean isTrainer()          { return "trainer".equals(normalizeRole(userType)); }
    public boolean isSalesManager()     { return "sales_manager".equals(normalizeRole(userType)); }
    public boolean isSupportExecutive() { return "support_executive".equals(normalizeRole(userType)); }
    public boolean isAgent()            { return normalizeRole(userType).startsWith("agent_"); }
    public boolean isLevel1()           { return "level_1".equals(normalizeRole(userType)) || "level_1".equals(normalizeRole(level)); }
    public boolean isLevel2()           { return "level_2".equals(normalizeRole(userType)) || "level_2".equals(normalizeRole(level)); }
    public boolean isLevel3()           { return "level_3".equals(normalizeRole(userType)) || "level_3".equals(normalizeRole(level)); }
    public boolean isPaidSubscriber()   { return "paid_subscriber".equals(normalizeRole(userType)); }
    public boolean isFreeTrial()        { return "free_trial".equals(normalizeRole(userType)); }

    public int getHierarchyLevel() {
        return switch (normalizeRole(userType)) {
            case "super_admin"       -> 1;
            case "platform_admin"    -> 2;
            case "admin", "compliance_officer", "research_analyst", "trainer" -> 3;
            case "institution_admin","sales_manager","support_executive" -> 4;
            case "level_1"           -> 5;
            case "level_2"           -> 6;
            case "level_3"           -> 7;
            case "agent_l3"          -> 7;
            case "agent_l1","agent_l2" -> 8;
            case "paid_subscriber"   -> 9;
            case "free_trial"        -> 10;
            default -> 9;
        };
    }

    /** Can this user manage (create/edit) the given userType? */
    public boolean canManageUserType(String targetType) {
        int myLevel = getHierarchyLevel();
        User target = User.builder().userType(normalizeRole(targetType)).build();
        return myLevel < target.getHierarchyLevel();
    }

    public static String normalizeRole(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }
}
