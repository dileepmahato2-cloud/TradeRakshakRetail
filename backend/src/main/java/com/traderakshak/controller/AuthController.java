package com.traderakshak.controller;

// =============================================================================
// Auth endpoints — replaces Python /login /register /logout /otp routes
// R3: consent required on login + register
// R4: SEBI disclaimer in every response
// BRD §17 L5: rate limiting applied via RateLimitFilter
// =============================================================================

import com.traderakshak.dto.request.LoginRequest;
import com.traderakshak.dto.request.RegisterRequest;
import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.dto.response.AuthResponse;
import com.traderakshak.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Login, Register, Token refresh")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    @Operation(summary = "Login — BRD §17 + R3 consent",
               description = "Validates BCrypt password, checks lockout, logs consent, returns JWT.")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest req,
            HttpServletRequest httpReq) {

        if (!req.isConsent()) {
            return ResponseEntity.badRequest().body(
                ApiResponse.error("CONSENT-001",
                    "Please accept the SEBI declaration to continue."));
        }

        AuthResponse auth = authService.login(req, httpReq.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.ok("Login successful.", auth));
    }

    @PostMapping("/register")
    @Operation(summary = "Register — BRD §28 PAN-only registration")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest req,
            HttpServletRequest httpReq) {

        if (!req.isConsent()) {
            return ResponseEntity.badRequest().body(
                ApiResponse.error("CONSENT-001",
                    "Please accept the declaration before registering."));
        }

        AuthResponse auth = authService.register(req, httpReq.getRemoteAddr());
        return ResponseEntity.ok(
            ApiResponse.ok("Registration successful! TR ID: " + auth.getTrUserId(), auth));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new access token")
    // required=false: a missing/unparseable body must surface as AUTH-004 (400) via the
    // null check below, not as a generic 500 from HttpMessageNotReadableException.
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(@RequestBody(required = false) Map<String, String> body) {
        String refreshToken = body == null ? null : body.get("refreshToken");
        return ResponseEntity.ok(ApiResponse.ok("Session refreshed.", authService.refresh(refreshToken)));
    }

    @PostMapping("/logout")
    @Operation(summary = "Logout — JWT is stateless; client discards token")
    public ResponseEntity<ApiResponse<Void>> logout() {
        // Stateless JWT — no server-side session to invalidate.
        // Client drops the token. Optional: maintain a blocklist in Redis for prod.
        return ResponseEntity.ok(ApiResponse.ok("Logged out successfully.", null));
    }

    @GetMapping("/health")
    @Operation(summary = "Auth health check — public")
    public ResponseEntity<ApiResponse<String>> health() {
        return ResponseEntity.ok(ApiResponse.ok("TradeRakshak™ v11.4 API is running."));
    }
}
