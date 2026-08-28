package com.traderakshak.service;

import com.traderakshak.config.AppProperties;
import com.traderakshak.dto.request.LoginRequest;
import com.traderakshak.dto.request.RegisterRequest;
import com.traderakshak.dto.response.AuthResponse;
import com.traderakshak.entity.User;
import com.traderakshak.exception.TradeRakshakException;
import com.traderakshak.repository.UserRepository;
import com.traderakshak.security.JwtService;
import com.traderakshak.util.DateUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service @RequiredArgsConstructor @Slf4j
public class AuthService {

    private final UserRepository  userRepo;
    private final PasswordEncoder encoder;
    private final JwtService      jwtService;
    private final AppProperties   props;
    private final AuditService    auditService;

    private static final String SEBI =
        "TradeRakshak™ is NOT a SEBI-registered Investment Advisor. For educational purposes only.";

    @Transactional
    public AuthResponse login(LoginRequest req, String ip) {
        String email = req.getEmail().trim().toLowerCase();
        User user = userRepo.findByEmail(email)
            .orElseThrow(() -> new TradeRakshakException("AUTH-001","Incorrect email or password."));

        if (!user.isAccountNonLocked())
            throw new LockedException("Account locked. Try again in " +
                props.getSecurity().getLockoutMinutes() + " min. (AUTH-003)");
        if (!user.isEnabled())
            throw new TradeRakshakException("AUTH-005","Account inactive. Contact support.");

        if (!encoder.matches(req.getPassword(), user.getPassword())) {
            int cnt = user.getFailedLoginCount() + 1;
            user.setFailedLoginCount(cnt);
            if (cnt >= props.getSecurity().getMaxFailedLogins()) {
                user.setLockoutUntil(LocalDateTime.now()
                    .plusMinutes(props.getSecurity().getLockoutMinutes())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                log.warn("Account locked for {} after {} attempts", email, cnt);
            }
            userRepo.save(user);
            throw new TradeRakshakException("AUTH-001","Incorrect email or password.");
        }

        user.setFailedLoginCount(0); user.setLockoutUntil(null);
        user.setLoginCount(user.getLoginCount() + 1);
        user.setLastLogin(DateUtil.now()); user.setConsentAcceptedAt(DateUtil.now());
        user.setUpdatedAt(DateUtil.now());
        userRepo.save(user);

        auditService.logConsent(user.getId(), "Login", SEBI, ip, true);
        auditService.logActivity(user.getId(), "Auth", "Login", "success",
            "Type:" + user.getUserType() + " Role:" + user.getRole(), ip, "");

        return buildResponse(user);
    }

    @Transactional
    public AuthResponse register(RegisterRequest req, String ip) {
        String email = req.getEmail().trim().toLowerCase();
        if (userRepo.existsByEmail(email))
            throw new TradeRakshakException("REG-001","Email already registered.");

        String pan = req.getPan().replaceAll("[^A-Z0-9]","").toUpperCase();
        if (pan.length() != 10)
            throw new TradeRakshakException("REG-002","Invalid 10-character PAN.");

        auditService.logConsent(null, "Registration", SEBI, ip, true);

        long seq = userRepo.countAll() + 1;
        String trUserId = "TR-" + DateUtil.currentYear() + "-" + String.format("%06d", seq);

        User user = User.builder()
            .trUserId(trUserId).institutionId(1L).name(req.getName())
            .email(email).password(encoder.encode(req.getPassword()))
            .role("paid_subscriber").userType("free_trial")
            .level("level_2").plan("Free Trial").segments("EQ-INT,EQ-SWG")
            .status("active").mobile(req.getMobile()).pan(pan)
            .kycStatus("pending").consentAcceptedAt(DateUtil.now())
            .createdAt(DateUtil.now()).updatedAt(DateUtil.now()).build();

        userRepo.save(user);
        log.info("New user registered: {} ({})", trUserId, email);
        return buildResponse(user);
    }

    /**
     * Exchanges a valid refresh token for a fresh access token, so an active session is not
     * cut off by the 30-minute access-token expiry. The refresh token is rotated on every
     * call (buildResponse issues a new one), keeping the window for a leaked token short.
     */
    @Transactional(readOnly = true)
    public AuthResponse refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank())
            throw new TradeRakshakException("AUTH-004", "Refresh token is required.");
        if (!jwtService.isRefreshToken(refreshToken))
            throw new TradeRakshakException("AUTH-004", "Not a refresh token. Please log in again.");

        String email;
        try {
            email = jwtService.extractUsername(refreshToken);
        } catch (Exception e) {
            throw new TradeRakshakException("AUTH-004", "Session expired. Please log in again.");
        }

        User user = userRepo.findByEmail(email)
            .orElseThrow(() -> new TradeRakshakException("AUTH-004", "Session expired. Please log in again."));
        if (!jwtService.isTokenValid(refreshToken, user))
            throw new TradeRakshakException("AUTH-004", "Session expired. Please log in again.");
        if (!user.isAccountNonLocked())
            throw new LockedException("Account locked. (AUTH-003)");
        if (!user.isEnabled())
            throw new TradeRakshakException("AUTH-005", "Account inactive. Contact support.");

        return buildResponse(user);
    }

    private AuthResponse buildResponse(User user) {
        String token   = jwtService.generateToken(user);
        String refresh = jwtService.generateRefreshToken(user);
        return AuthResponse.builder()
            .token(token).refreshToken(refresh)
            .trUserId(user.getTrUserId()).name(user.getName())
            .email(user.getEmail()).role(user.getRole())
            .userType(user.getUserType() != null ? user.getUserType() : user.getRole())
            .hierarchyLevel(user.getHierarchyLevel())
            .plan(user.getPlan()).planExpiry(user.getPlanExpiry())
            .planStatus(user.getPlanStatus())
            .sebiDisclaimer(props.getSebiDisclaimer()).build();
    }
}
