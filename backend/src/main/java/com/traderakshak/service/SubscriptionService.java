package com.traderakshak.service;

// =============================================================================
// SubscriptionService — full subscription lifecycle
// upgrade, downgrade, cancel, renew, trial conversion, coupon, wallet credits
// =============================================================================

import com.traderakshak.entity.*;
import com.traderakshak.exception.TradeRakshakException;
import com.traderakshak.repository.*;
import com.traderakshak.util.DateUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionService {

    private final UserRepository      userRepo;
    private final SubscriptionRepository subRepo;
    private final CouponRepository    couponRepo;
    private final WalletRepository    walletRepo;
    private final AuditService        auditService;

    @PersistenceContext
    private EntityManager em;

    // ── Activate / create subscription ────────────────────────────────────────

    @Transactional
    public Map<String, Object> activatePlan(Long userId, String planName, String billingCycle,
                                             String couponCode, String paymentMethod,
                                             String gatewayPaymentId) {
        // Fetch plan
        Object[] plan = getPlan(planName);
        if (plan == null) throw new TradeRakshakException("SUB-001", "Plan not found: " + planName);

        // ── TESTING MODE: override all prices to zero ─────────────────────────
        // No real money is charged. All plans are FREE for testing.
        // Remove this block and restore price calculation when going live.
        double basePrice = 0.0;    // was: ((Number) plan[2]).doubleValue()
        double gstRate   = 0.0;    // was: ((Number) plan[3]).doubleValue()
        int validityDays = ((Number) plan[4]).intValue();
        double discount  = 0;

        // Apply coupon if provided
        if (couponCode != null && !couponCode.isBlank()) {
            Coupon coupon = couponRepo.findByCodeAndStatus(couponCode.toUpperCase(), "active")
                .orElse(null);
            if (coupon != null && isCouponValid(coupon, planName, basePrice)) {
                discount = switch (coupon.getCouponType()) {
                    case "percentage" -> basePrice * coupon.getDiscountValue() / 100;
                    case "fixed"      -> Math.min(coupon.getDiscountValue(), basePrice);
                    case "free_days"  -> { validityDays += coupon.getFreeDays(); yield 0; }
                    default           -> 0;
                };
                coupon.setCurrentUses(coupon.getCurrentUses() + 1);
                couponRepo.save(coupon);
                logCouponUse(coupon.getId(), userId, discount);
            }
        }

        // Apply wallet credits
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new TradeRakshakException("USR-404", "User not found"));
        double walletUsed = 0;
        if (user.getWalletCredits() > 0) {
            walletUsed = Math.min(user.getWalletCredits(), basePrice - discount);
            discount += walletUsed;
            creditWallet(userId, -walletUsed, "Subscription payment offset", null);
        }

        double discountedBase = Math.max(0, basePrice - discount);
        double gst   = Math.round(discountedBase * gstRate / 100 * 100) / 100.0;
        double total = Math.round((discountedBase + gst) * 100) / 100.0;

        String startDate = DateUtil.today();
        String endDate   = LocalDate.now().plusDays(validityDays).toString();

        // Deactivate current active subscription
        subRepo.findByUserIdAndStatus(userId, "active").ifPresent(s -> {
            s.setStatus("superseded");
            s.setUpdatedAt(DateUtil.now());
            subRepo.save(s);
        });

        // Create new subscription
        Subscription sub = Subscription.builder()
            .userId(userId).institutionId(user.getInstitutionId())
            .planName(planName).billingCycle(billingCycle)
            .status("active").startDate(startDate).endDate(endDate)
            .nextBilling(endDate).autoRenew(1).createdAt(DateUtil.now()).updatedAt(DateUtil.now())
            .build();
        subRepo.save(sub);

        // Update user
        user.setPlan(planName);
        user.setPlanStatus("paid");
        user.setPlanExpiry(endDate);
        user.setAutoRenew(1);
        user.setUserType("paid_subscriber");
        user.setUpdatedAt(DateUtil.now());
        userRepo.save(user);

        // Record payment
        String invoiceNumber = generateInvoiceNumber();
        recordPayment(userId, planName, billingCycle, basePrice, gst, total,
                      discount - walletUsed, couponCode, paymentMethod, gatewayPaymentId,
                      startDate, endDate, invoiceNumber);

        // Log subscription change
        logSubChange(userId, user.getPlan(), planName, "activate", null);

        auditService.logActivity(userId, "Subscription", "Activate", "success",
            planName + " until " + endDate, "", "");

        return Map.of("plan", planName, "startDate", startDate, "endDate", endDate,
                      "invoiceNumber", invoiceNumber, "total", total,
                      "gst", gst, "discount", discount);
    }

    // ── Upgrade ───────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> upgradePlan(Long userId, String newPlan) {
        User user = userRepo.findById(userId).orElseThrow();
        String oldPlan = user.getPlan();

        // Calculate proration credit
        double prorationCredit = calculateProration(userId, oldPlan);
        if (prorationCredit > 0) {
            creditWallet(userId, prorationCredit, "Proration credit for upgrade from " + oldPlan, null);
        }

        logSubChange(userId, oldPlan, newPlan, "upgrade", prorationCredit);
        return activatePlan(userId, newPlan, "monthly", null, "wallet", null);
    }

    // ── Downgrade ─────────────────────────────────────────────────────────────

    @Transactional
    public void downgradePlan(Long userId, String newPlan, String reason) {
        User user = userRepo.findById(userId).orElseThrow();
        // Downgrade takes effect at end of current billing cycle
        subRepo.findByUserIdAndStatus(userId, "active").ifPresent(s -> {
            s.setDowngradeTo(newPlan);
            s.setUpdatedAt(DateUtil.now());
            subRepo.save(s);
        });
        logSubChange(userId, user.getPlan(), newPlan, "downgrade_scheduled", null);
        auditService.logActivity(userId, "Subscription", "DowngradeScheduled", "success",
            "Will downgrade to " + newPlan + " at billing cycle end", "", "");
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    @Transactional
    public void cancelAutoRenewal(Long userId, String reason) {
        User user = userRepo.findById(userId).orElseThrow();
        user.setPlanStatus("cancelled");
        user.setAutoRenew(0);
        user.setUpdatedAt(DateUtil.now());
        userRepo.save(user);
        subRepo.findByUserIdAndStatus(userId, "active").ifPresent(s -> {
            s.setAutoRenew(0);
            s.setCancelReason(reason);
            s.setUpdatedAt(DateUtil.now());
            subRepo.save(s);
        });
        logSubChange(userId, user.getPlan(), null, "cancel_auto_renew", null);
    }

    @Transactional
    public void cancelImmediately(Long userId, String reason) {
        User user = userRepo.findById(userId).orElseThrow();
        subRepo.findByUserIdAndStatus(userId, "active").ifPresent(s -> {
            s.setStatus("cancelled");
            s.setCancelReason(reason);
            s.setCancelledAt(DateUtil.now());
            s.setUpdatedAt(DateUtil.now());
            subRepo.save(s);
        });
        user.setPlan("Free Trial");
        user.setPlanStatus("expired");
        user.setAutoRenew(0);
        user.setUpdatedAt(DateUtil.now());
        userRepo.save(user);
        logSubChange(userId, user.getPlan(), "Free Trial", "cancel_immediate", null);
    }

    // ── Wallet operations ─────────────────────────────────────────────────────

    @Transactional
    public void creditWallet(Long userId, double amount, String description, String refId) {
        User user = userRepo.findById(userId).orElseThrow();
        double newBalance = user.getWalletCredits() + amount;
        user.setWalletCredits(newBalance);
        user.setUpdatedAt(DateUtil.now());
        userRepo.save(user);

        walletRepo.save(WalletTransaction.builder()
            .userId(userId).transactionType(amount >= 0 ? "credit" : "debit")
            .amount(Math.abs(amount)).balanceAfter(newBalance)
            .description(description).referenceId(refId)
            .createdAt(DateUtil.now()).build());
    }

    // ── Coupon validation ─────────────────────────────────────────────────────

    public Map<String, Object> validateCoupon(String code, String planName, double orderAmount) {
        Coupon coupon = couponRepo.findByCodeAndStatus(code.toUpperCase(), "active")
            .orElseThrow(() -> new TradeRakshakException("CPN-001", "Invalid or expired coupon code."));

        if (!isCouponValid(coupon, planName, orderAmount)) {
            throw new TradeRakshakException("CPN-002",
                "Coupon not applicable for this plan or order amount.");
        }

        double discount = switch (coupon.getCouponType()) {
            case "percentage" -> orderAmount * coupon.getDiscountValue() / 100;
            case "fixed"      -> Math.min(coupon.getDiscountValue(), orderAmount);
            case "free_days"  -> 0;
            default -> 0;
        };

        return Map.of("valid", true, "code", code, "discount", discount,
                      "freeDays", coupon.getFreeDays(), "type", coupon.getCouponType(),
                      "description", coupon.getDiscountValue() +
                          ("percentage".equals(coupon.getCouponType()) ? "% off" :
                           "free_days".equals(coupon.getCouponType()) ? " free days" : "₹ off"));
    }

    // ── Auto-expiry scheduler ─────────────────────────────────────────────────

    @Scheduled(cron = "0 0 1 * * *")  // Daily at 1 AM
    @Transactional
    public void processExpiredPlans() {
        String today = DateUtil.today();
        List<User> expired = userRepo.findByPlanStatusAndPlanExpiryBefore("paid", today);
        for (User u : expired) {
            // Check if downgrade scheduled
            Optional<Subscription> sub = subRepo.findByUserIdAndStatus(u.getId(), "active");
            String newPlan = sub.map(Subscription::getDowngradeTo).filter(p -> p != null && !p.isBlank())
                                .orElse("Free Trial");
            u.setPlan(newPlan);
            u.setPlanStatus("expired");
            u.setAutoRenew(0);
            u.setUserType("free_trial".equals(newPlan.toLowerCase()) ? "free_trial" : "paid_subscriber");
            u.setUpdatedAt(DateUtil.now());
            userRepo.save(u);
            sub.ifPresent(s -> { s.setStatus("expired"); s.setUpdatedAt(DateUtil.now()); subRepo.save(s); });
            log.info("Plan expired: {} → {}", u.getTrUserId(), newPlan);
        }
        if (!expired.isEmpty()) log.info("Processed {} expired plans", expired.size());
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private Object[] getPlan(String planName) {
        try {
            return (Object[]) em.createNativeQuery(
                "SELECT id,name,price,gst,validity_days FROM plans WHERE name=:n AND status='active'")
                .setParameter("n", planName).getSingleResult();
        } catch (Exception e) { return null; }
    }

    private boolean isCouponValid(Coupon c, String planName, double amount) {
        String today = DateUtil.today();
        if (c.getValidFrom() != null && today.compareTo(c.getValidFrom()) < 0) return false;
        if (c.getValidTo()   != null && today.compareTo(c.getValidTo())   > 0) return false;
        if (c.getMaxUses() > 0 && c.getCurrentUses() >= c.getMaxUses()) return false;
        if (amount < c.getMinOrderAmount()) return false;
        String plans = c.getApplicablePlans();
        return plans == null || plans.equals("ALL") || plans.contains(planName);
    }

    private double calculateProration(Long userId, String currentPlan) {
        return subRepo.findByUserIdAndStatus(userId, "active").map(s -> {
            try {
                long remaining = java.time.temporal.ChronoUnit.DAYS.between(
                    LocalDate.now(), LocalDate.parse(s.getEndDate()));
                Object[] plan = getPlan(currentPlan);
                if (plan == null || remaining <= 0) return 0.0;
                long total = java.time.temporal.ChronoUnit.DAYS.between(
                    LocalDate.parse(s.getStartDate()), LocalDate.parse(s.getEndDate()));
                double dailyRate = ((Number) plan[2]).doubleValue() / Math.max(1, total);
                return Math.round(dailyRate * remaining * 100) / 100.0;
            } catch (Exception e) { return 0.0; }
        }).orElse(0.0);
    }

    private String generateInvoiceNumber() {
        String ym = java.time.YearMonth.now().toString();
        try {
            Object cnt = em.createNativeQuery(
                "SELECT COUNT(*) FROM payment_history WHERE invoice_number LIKE :p")
                .setParameter("p", "TR-INV-" + ym + "%").getSingleResult();
            int seq = ((Number) cnt).intValue() + 1;
            return "TR-INV-" + ym + "-" + String.format("%06d", seq);
        } catch (Exception e) {
            return "TR-INV-" + ym + "-" + (int)(Math.random() * 999999);
        }
    }

    private void recordPayment(Long userId, String plan, String cycle,
                                double base, double gst, double total, double discount,
                                String coupon, String method, String gatewayId,
                                String start, String end, String invoice) {
        try {
            em.createNativeQuery(
                "INSERT INTO payment_history(user_id,plan_name,billing_cycle," +
                "base_amount_inr,gst_amount_inr,total_amount_inr,offer_code," +
                "discount_amount_inr,payment_status,payment_method,gateway," +
                "gateway_payment_id,plan_start_date,plan_end_date,invoice_number,created_at) " +
                "VALUES(?,?,?,?,?,?,?,?,'success',?,?,?,?,?,?,?,?)")
                .setParameter(1,userId).setParameter(2,plan).setParameter(3,cycle)
                .setParameter(4,base).setParameter(5,gst).setParameter(6,total)
                .setParameter(7,coupon!=null?coupon:"").setParameter(8,discount)
                .setParameter(9,method).setParameter(10,"razorpay")
                .setParameter(11,gatewayId!=null?gatewayId:"")
                .setParameter(12,start).setParameter(13,end)
                .setParameter(14,invoice).setParameter(15,DateUtil.now())
                .executeUpdate();
        } catch (Exception e) { log.error("Record payment error: {}", e.getMessage()); }
    }

    private void logCouponUse(Long couponId, Long userId, double discount) {
        try {
            em.createNativeQuery(
                "INSERT INTO coupon_uses(coupon_id,user_id,discount_applied,used_at) VALUES(?,?,?,?)")
                .setParameter(1,couponId).setParameter(2,userId)
                .setParameter(3,discount).setParameter(4,DateUtil.now()).executeUpdate();
        } catch (Exception ignored) {}
    }

    private void logSubChange(Long userId, String from, String to, String type, Double credit) {
        try {
            em.createNativeQuery(
                "INSERT INTO subscription_changes(user_id,from_plan,to_plan,change_type,prorated_credit,initiated_by,created_at) VALUES(?,?,?,?,?,?,?)")
                .setParameter(1,userId).setParameter(2,from).setParameter(3,to)
                .setParameter(4,type).setParameter(5,credit!=null?credit:0)
                .setParameter(6,userId).setParameter(7,DateUtil.now()).executeUpdate();
        } catch (Exception ignored) {}
    }
}
