package com.traderakshak.controller;

import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.entity.User;
import com.traderakshak.repository.SubscriptionRepository;
import com.traderakshak.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/subscriptions")
@RequiredArgsConstructor
@Tag(name = "Subscriptions", description = "Subscription lifecycle, upgrade, downgrade, coupon, wallet")
public class SubscriptionController {

    private final SubscriptionService  subService;
    private final SubscriptionRepository subRepo;

    @GetMapping("/my")
    @Operation(summary = "Get my active subscription")
    public ResponseEntity<ApiResponse<Object>> mySubscription(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            subRepo.findByUserIdAndStatus(user.getId(), "active").orElse(null)));
    }

    @GetMapping("/history")
    @Operation(summary = "My subscription history")
    public ResponseEntity<ApiResponse<Object>> history(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(subRepo.findByUserId(user.getId())));
    }

    @PostMapping("/activate")
    @Operation(summary = "Activate / subscribe to a plan — FREE in testing mode, no real payment")
    public ResponseEntity<ApiResponse<Map<String, Object>>> activate(
            @RequestBody ActivateRequest req,
            @AuthenticationPrincipal User user) {
        // ── TESTING MODE: no real payment required ─────────────────────────────
        // Any activation request is processed at ZERO cost regardless of plan.
        // Payment method "testing" explicitly signals no gateway call.
        // consentPayment check is bypassed — no real money is involved.
        //
        // When going live, this block is removed and Razorpay verification re-enabled.
        // That change is made via SuperAdmin feature_config without code change.
        // ───────────────────────────────────────────────────────────────────────

        String paymentMethod = req.getPaymentMethod() != null ? req.getPaymentMethod() : "testing";
        String gatewayId     = "TESTING-FREE-" + System.currentTimeMillis();

        Map<String, Object> result = subService.activatePlan(
            user.getId(), req.getPlanName(),
            req.getBillingCycle() != null ? req.getBillingCycle() : "monthly",
            req.getCouponCode(), paymentMethod, gatewayId);

        return ResponseEntity.ok(ApiResponse.ok(
            "Plan activated (Testing Mode — No charge). Enjoy TradeRakshak™!", result));
    }

    @PostMapping("/upgrade")
    @Operation(summary = "Upgrade plan — prorated credit applied")
    public ResponseEntity<ApiResponse<Map<String, Object>>> upgrade(
            @RequestBody PlanRequest req, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            "Plan upgraded to " + req.getPlanName() + ".",
            subService.upgradePlan(user.getId(), req.getPlanName())));
    }

    @PostMapping("/downgrade")
    @Operation(summary = "Schedule downgrade at next billing cycle")
    public ResponseEntity<ApiResponse<Void>> downgrade(
            @RequestBody DowngradeRequest req, @AuthenticationPrincipal User user) {
        subService.downgradePlan(user.getId(), req.getPlanName(), req.getReason());
        return ResponseEntity.ok(ApiResponse.ok(
            "Downgrade to " + req.getPlanName() + " scheduled at end of billing cycle.", null));
    }

    @PostMapping("/cancel-auto-renew")
    @Operation(summary = "Cancel auto-renewal — plan active until expiry")
    public ResponseEntity<ApiResponse<Void>> cancelAutoRenew(
            @RequestBody ReasonRequest req, @AuthenticationPrincipal User user) {
        if (!req.isConsentCancel()) {
            return ResponseEntity.badRequest().body(
                ApiResponse.error("CONSENT-001", "Cancellation consent is required."));
        }
        subService.cancelAutoRenewal(user.getId(), req.getReason());
        return ResponseEntity.ok(ApiResponse.ok(
            "Auto-renewal cancelled. Your plan remains active until expiry.", null));
    }

    @PostMapping("/coupon/validate")
    @Operation(summary = "Validate coupon code")
    public ResponseEntity<ApiResponse<Map<String, Object>>> validateCoupon(
            @RequestBody CouponRequest req, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            subService.validateCoupon(req.getCode(), req.getPlanName(), req.getOrderAmount())));
    }

    @GetMapping("/wallet")
    @Operation(summary = "My wallet balance and transactions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> wallet(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "balance", user.getWalletBalance(),
            "credits", user.getWalletCredits()
        )));
    }

    // ── Admin: manage other users' subscriptions ──────────────────────────────

    @PostMapping("/admin/{userId}/activate")
    @Operation(summary = "Admin: activate plan for user")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adminActivate(
            @PathVariable Long userId,
            @RequestBody ActivateRequest req,
            @AuthenticationPrincipal User actor) {
        if (!actor.isAdmin()) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(ApiResponse.ok("Plan activated.",
            subService.activatePlan(userId, req.getPlanName(), req.getBillingCycle(),
                req.getCouponCode(), req.getPaymentMethod(), req.getGatewayPaymentId())));
    }

    @PostMapping("/admin/{userId}/cancel")
    @Operation(summary = "Admin: cancel user subscription immediately")
    public ResponseEntity<ApiResponse<Void>> adminCancel(
            @PathVariable Long userId,
            @RequestBody ReasonRequest req,
            @AuthenticationPrincipal User actor) {
        if (!actor.isAdmin()) return ResponseEntity.status(403).build();
        subService.cancelImmediately(userId, req.getReason());
        return ResponseEntity.ok(ApiResponse.ok("Subscription cancelled.", null));
    }

    @PostMapping("/admin/{userId}/credit-wallet")
    @Operation(summary = "Admin: credit wallet for user")
    public ResponseEntity<ApiResponse<Void>> creditWallet(
            @PathVariable Long userId,
            @RequestBody WalletRequest req,
            @AuthenticationPrincipal User actor) {
        if (!actor.isAdmin()) return ResponseEntity.status(403).build();
        subService.creditWallet(userId, req.getAmount(), req.getDescription(), null);
        return ResponseEntity.ok(ApiResponse.ok("Wallet credited ₹" + req.getAmount(), null));
    }

    // ── Inner request classes ──────────────────────────────────────────────────

    @Data static class ActivateRequest {
        private String planName, billingCycle="monthly", couponCode, paymentMethod="upi", gatewayPaymentId;
        private boolean consentPayment;
    }
    @Data static class PlanRequest      { private String planName; }
    @Data static class DowngradeRequest { private String planName, reason; }
    @Data static class ReasonRequest    { private String reason; private boolean consentCancel; }
    @Data static class CouponRequest    { private String code, planName; private double orderAmount; }
    @Data static class WalletRequest    { private double amount; private String description; }
}
