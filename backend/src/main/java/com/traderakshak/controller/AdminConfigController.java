package com.traderakshak.controller;

// =============================================================================
// AdminConfigController  — Admin manages feature flags (including backtesting)
// NotificationController — Users configure their own delivery channels
// =============================================================================

import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.entity.User;
import com.traderakshak.service.FeatureConfigService;
import com.traderakshak.service.NotificationChannelService;
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

import java.util.*;

// ─────────────────────────────────────────────────────────────────────────────
// ADMIN FEATURE CONFIG  (/admin/features)
// ─────────────────────────────────────────────────────────────────────────────

@RestController
@RequestMapping("/admin/features")
@RequiredArgsConstructor
@Tag(name = "Admin Feature Config", description = "Admin manages all feature flags — enable/disable/schedule")
class AdminConfigController {

    private final FeatureConfigService featureSvc;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PLATFORM_ADMIN','ADMIN','INSTITUTION_ADMIN')")
    @Operation(summary = "Get all feature configs — admin panel view")
    public ResponseEntity<ApiResponse<List<?>>> getAllFeatures(@AuthenticationPrincipal User actor) {
        return ResponseEntity.ok(ApiResponse.ok(featureSvc.getAllConfigs()));
    }

    @GetMapping("/{featureKey}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PLATFORM_ADMIN','ADMIN','INSTITUTION_ADMIN')")
    @Operation(summary = "Get single feature config")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getFeature(
            @PathVariable String featureKey,
            @AuthenticationPrincipal User actor) {
        return ResponseEntity.ok(ApiResponse.ok(featureSvc.getConfigMap(featureKey)));
    }

    @PutMapping("/{featureKey}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PLATFORM_ADMIN','ADMIN')")
    @Operation(summary = "Update feature config — schedule, plan, role, message")
    public ResponseEntity<ApiResponse<Void>> updateFeature(
            @PathVariable String featureKey,
            @RequestBody Map<String, Object> req,
            @AuthenticationPrincipal User actor) {
        featureSvc.updateConfig(featureKey, req, actor);
        return ResponseEntity.ok(ApiResponse.ok(
            "Feature '" + featureKey + "' configuration updated.", null));
    }

    @PatchMapping("/{featureKey}/toggle")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PLATFORM_ADMIN','ADMIN')")
    @Operation(summary = "Quick toggle — enable or disable a feature")
    public ResponseEntity<ApiResponse<Void>> toggleFeature(
            @PathVariable String featureKey,
            @RequestBody ToggleRequest req,
            @AuthenticationPrincipal User actor) {
        featureSvc.toggleFeature(featureKey, req.isEnabled(), actor);
        String action = req.isEnabled() ? "ENABLED" : "DISABLED";
        return ResponseEntity.ok(ApiResponse.ok(
            "Feature '" + featureKey + "' is now " + action + ".", null));
    }

    @GetMapping("/check/{featureKey}")
    @Operation(summary = "Check if feature is available for current user+time")
    public ResponseEntity<?> checkFeature(
            @PathVariable String featureKey,
            @AuthenticationPrincipal User user) {
        FeatureConfigService.FeatureCheckResult result = featureSvc.checkFeature(featureKey, user);
        if (result.isAllowed()) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of("allowed", true, "feature", featureKey)));
        }
        // Return the configured HTTP status (423 for schedule, 403 for forbidden)
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("allowed",      false);
        body.put("feature",      featureKey);
        body.put("message",      result.getMessage());
        body.put("httpStatus",   result.getHttpStatus());
        if (result.getAvailableAt() != null) body.put("availableAt", result.getAvailableAt());

        return ResponseEntity.status(result.getHttpStatus()).body(ApiResponse.error(
            "FEAT-" + result.getHttpStatus(), result.getMessage()));
    }

    @GetMapping("/my-access")
    @Operation(summary = "Get full feature access summary for current user")
    public ResponseEntity<ApiResponse<Map<String, Object>>> myAccess(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(featureSvc.getUserFeatureSummary(user)));
    }

    @Data static class ToggleRequest { private boolean enabled; }
}

// ─────────────────────────────────────────────────────────────────────────────
// USER NOTIFICATION CHANNELS  (/notifications)
// ─────────────────────────────────────────────────────────────────────────────

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
@Tag(name = "Notification Channels", description = "User configures Email, SMS, Telegram, WhatsApp delivery channels")
class NotificationController {

    private final NotificationChannelService notifSvc;

    @GetMapping("/channels")
    @Operation(summary = "Get my current notification channel settings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMyChannels(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(notifSvc.getUserChannels(user.getId(), user)));
    }

    @PutMapping("/channels")
    @Operation(summary = "Save my notification channel preferences")
    public ResponseEntity<ApiResponse<Map<String, Object>>> saveChannels(
            @RequestBody Map<String, Object> req,
            @AuthenticationPrincipal User user) {
        Map<String, Object> saved = notifSvc.saveChannels(user, req);
        return ResponseEntity.ok(ApiResponse.ok("Notification settings saved.", saved));
    }

    @PostMapping("/channels/{channel}/test")
    @Operation(summary = "Send test message on a specific channel — email/sms/telegram/whatsapp")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testChannel(
            @PathVariable String channel,
            @AuthenticationPrincipal User user) {
        // Validate channel name
        if (!Set.of("email","sms","telegram","whatsapp").contains(channel.toLowerCase())) {
            return ResponseEntity.badRequest().body(
                ApiResponse.error("NOTIF-001", "Invalid channel. Valid: email, sms, telegram, whatsapp"));
        }
        Map<String, Object> result = notifSvc.testChannel(user, channel);
        boolean sent = (boolean) result.getOrDefault("sent", false);
        return ResponseEntity.ok(ApiResponse.ok(
            sent ? "✅ Test " + channel + " message sent successfully!" :
                   "❌ Test " + channel + " message failed. " + result.getOrDefault("detail",""),
            result));
    }

    @GetMapping("/channels/{channel}/setup-guide")
    @Operation(summary = "Get setup guide for configuring a channel (Telegram, WhatsApp)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> setupGuide(
            @PathVariable String channel) {
        Map<String, Object> guide = switch (channel.toLowerCase()) {
            case "telegram" -> Map.of(
                "channel",  "Telegram",
                "steps", List.of(
                    "1. Open Telegram and search for @TradeRakshakBot",
                    "2. Click 'Start' or send /start to the bot",
                    "3. The bot will reply with your Chat ID (a number like 123456789)",
                    "4. Copy that Chat ID and paste it in the 'Telegram Chat ID' field",
                    "5. Click 'Test Telegram' to verify the connection"
                ),
                "botUsername", "@TradeRakshakBot",
                "helpText", "Your Chat ID is a unique number that identifies your Telegram account to our bot."
            );
            case "whatsapp" -> Map.of(
                "channel", "WhatsApp",
                "steps", List.of(
                    "1. Enter your WhatsApp number with country code (e.g. +919876543210)",
                    "2. Check the 'I consent to receive WhatsApp messages' box",
                    "3. Click 'Test WhatsApp' to send a verification message",
                    "4. Reply 'YES' to the WhatsApp message to confirm opt-in"
                ),
                "helpText", "WhatsApp requires explicit opt-in. You must reply to the first message to activate.",
                "note", "WhatsApp delivery uses pre-approved templates only (TRAI compliance)."
            );
            case "sms" -> Map.of(
                "channel", "SMS",
                "steps", List.of(
                    "1. Enter your 10-digit mobile number (without country code)",
                    "2. Click 'Test SMS' to send a test message",
                    "3. SMS alerts are delivered via MSG91 (Indian numbers only)"
                ),
                "helpText", "Indian mobile numbers only. SMS alerts are delivered within 30 seconds."
            );
            case "email" -> Map.of(
                "channel", "Email",
                "steps", List.of(
                    "1. Your account email is pre-filled",
                    "2. You can change it to any email address",
                    "3. Click 'Test Email' to verify delivery",
                    "4. Check your spam folder if you don't receive it"
                ),
                "helpText", "Email is the most reliable channel. Recommended for all important alerts."
            );
            default -> Map.of("error", "Unknown channel: " + channel);
        };
        return ResponseEntity.ok(ApiResponse.ok(guide));
    }

    @PersistenceContext private EntityManager em2;

    @GetMapping("/history")
    @Operation(summary = "My notification delivery history")
    public ResponseEntity<ApiResponse<List<?>>> myHistory(@AuthenticationPrincipal User user) {
        try {
            List<?> history = em2.createNativeQuery(
                "SELECT channel,notification_type,subject,recipient,status,error_message,sent_at " +
                "FROM notification_delivery_log WHERE user_id=:uid ORDER BY created_at DESC LIMIT 100")
                .setParameter("uid", user.getId()).getResultList();
            return ResponseEntity.ok(ApiResponse.ok(history));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.ok(List.of()));
        }
    }

    @GetMapping("/platform-config")
    @Operation(summary = "Get platform-level channel configuration (bot names, capabilities)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> platformConfig() {
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "telegram", Map.of(
                "botUsername",  "@TradeRakshakBot",
                "supported",    true,
                "setupGuide",   "/api/notifications/channels/telegram/setup-guide"
            ),
            "whatsapp", Map.of(
                "supported",    true,
                "requiresOptIn",true,
                "setupGuide",   "/api/notifications/channels/whatsapp/setup-guide"
            ),
            "sms", Map.of(
                "provider",     "MSG91",
                "supported",    true,
                "countriesSupported", List.of("IN"),
                "setupGuide",   "/api/notifications/channels/sms/setup-guide"
            ),
            "email", Map.of(
                "supported",    true,
                "provider",     "SMTP/SendGrid",
                "setupGuide",   "/api/notifications/channels/email/setup-guide"
            ),
            "in_app", Map.of(
                "supported",    true,
                "alwaysOn",     true
            ),
            "sebiDisclaimer", "TradeRakshak™ is NOT a SEBI-registered Investment Advisor. For educational use only."
        )));
    }
}
