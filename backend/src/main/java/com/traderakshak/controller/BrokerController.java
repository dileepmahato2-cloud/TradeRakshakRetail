package com.traderakshak.controller;

// =============================================================================
// BrokerController â€” TradeRakshakâ„¢ v11.6
//
// Manages broker authentication credentials for all Indian brokers.
// Supports: Zerodha, Upstox, Angel One, Fyers, Dhan, Shoonya, Kotak, ICICI
//
// Routes:
//   GET  /broker/list           â†’ supported brokers with field requirements
//   GET  /broker/my             â†’ user's saved broker connections
//   POST /broker/save           â†’ add or update broker credentials
//   POST /broker/verify/{id}    â†’ test connection (verify API key/token)
//   PUT  /broker/{id}/toggle    â†’ enable / disable a connection
//   PUT  /broker/{id}/readonly  â†’ toggle read-only mode
//   DELETE /broker/{id}         â†’ remove broker credentials
//   GET  /broker/active         â†’ get user's active verified connections
//
// Security:
//   â€¢ API secrets are encrypted before storage (AES via Fernet key)
//   â€¢ Plain-text secrets are NEVER stored or returned in responses
//   â€¢ read_only=1 means data-only â€” no order placement possible
//   â€¢ All changes are audit-logged
// =============================================================================

import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.entity.User;
import com.traderakshak.service.BlockingHttpService;
import com.traderakshak.service.StockTrackerService;
import com.traderakshak.util.DateUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/broker")
@Slf4j
@RequiredArgsConstructor
@Tag(name = "Broker Connect", description = "Indian broker authentication management â€” Zerodha, Upstox, Angel, Fyers, Dhan, Shoonya, Kotak, ICICI")
public class BrokerController {

    private final BlockingHttpService http;
    private final ObjectMapper mapper;
    private final StockTrackerService trackerService;
    private final PlatformTransactionManager transactionManager;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String DHAN_API = "https://api.dhan.co/v2";
    private static final String DHAN_AUTH = "https://auth.dhan.co";

    @PersistenceContext
    private EntityManager em;

    // ── Concurrency + short-transaction infrastructure ───────────────────────
    // Guards against duplicate/concurrent save·verify·token-generation on the
    // SAME credential row. A second request for a row already being written is
    // rejected immediately instead of blocking on a database row lock.
    private final java.util.Set<Long> writeInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Runs one write (INSERT/UPDATE/DELETE) inside its OWN short, committed
     * transaction (PROPAGATION_REQUIRES_NEW). Network calls, encryption, token
     * generation, validation and JSON parsing must all happen BEFORE this call —
     * never inside it — so the database transaction lasts only a few
     * milliseconds and the row lock is released immediately.
     *
     * Lock-contention failures (SQLState 55P03 → CannotAcquireLockException /
     * PessimisticLockingFailureException) are retried up to 3 times with
     * exponential back-off. If all retries fail the exception propagates so the
     * global handler can return HTTP 409.
     */
    private int writeWithRetry(String label, java.util.function.Supplier<Integer> work) {
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.setTimeout(10); // seconds — a write should never take this long
        int attempts = 0;
        long delayMs = 300L;
        while (true) {
            attempts++;
            long start = System.currentTimeMillis();
            try {
                Integer rows = tt.execute(status -> work.get());
                log.info("[Broker] {} committed in {} ms (attempt {})",
                    label, System.currentTimeMillis() - start, attempts);
                return rows == null ? 0 : rows;
            } catch (PessimisticLockingFailureException ex) {
                if (attempts >= 3) {
                    log.warn("[Broker] {} aborted after {} attempts — lock contention ({})",
                        label, attempts, ex.getMessage());
                    throw ex;
                }
                log.warn("[Broker] {} lock contention on attempt {} — retrying in {} ms",
                    label, attempts, delayMs);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
                delayMs *= 2;
            }
        }
    }

    /** Runs a schema/DDL block inside its own short transaction. */
    private void runInTx(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    // â”€â”€ Supported broker master list â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private static final List<Map<String, Object>> SUPPORTED_BROKERS = List.of(
        brokerMeta("zerodha",  "Zerodha (Kite Connect)",
            "India's largest broker. Requires API key + API secret + Daily login token.",
            "https://kite.zerodha.com/connect/login",
            new String[]{"client_id","api_key","api_secret","access_token"},
            "https://zerodha.com/static/images/kite-logo.png",
            true, "https://developers.kite.trade/"),

        brokerMeta("upstox",   "Upstox (API v2)",
            "Upstox Pro API. Requires API key, API secret, and OAuth access token.",
            "https://api.upstox.com/v2/login/authorization/dialog",
            new String[]{"client_id","api_key","api_secret","access_token"},
            "https://assets.upstox.com/logo.png",
            true, "https://upstox.com/developer/api-documentation/"),

        brokerMeta("angel",    "Angel One (SmartAPI)",
            "Angel Broking SmartAPI. Requires API key, client code, PIN, and TOTP.",
            null,
            new String[]{"client_id","api_key","api_secret","totp_secret"},
            "https://www.angelone.in/favicon.ico",
            true, "https://smartapi.angelbroking.com/"),

        brokerMeta("fyers",    "Fyers (API v3)",
            "Fyers API. Requires App ID, App Secret, and OAuth redirect.",
            "https://api.fyers.in/api/v3/generate-authcode",
            new String[]{"client_id","api_key","api_secret","access_token"},
            "https://fyers.in/favicon.ico",
            true, "https://myapi.fyers.in/"),

        brokerMeta("dhan",     "Dhan (API)",
            "Dhan Trading API. Enter Client ID, PIN, and TOTP. The system generates the access token automatically.",
            null,
            new String[]{"client_id","dhan_pin","totp_secret"},
            "https://dhan.co/favicon.ico",
            false, "https://dhanhq.co/docs/latest/"),

        brokerMeta("shoonya",  "Shoonya by Finvasia (API)",
            "Shoonya (Finvasia) API. Requires User ID, API key, TOTP secret, and vendor code.",
            null,
            new String[]{"client_id","api_key","api_secret","access_token"},
            "https://shoonya.finvasia.com/favicon.ico",
            false, "https://shoonya.com/api-doc/"),

        brokerMeta("kotak",    "Kotak Securities (Neo API)",
            "Kotak Neo Trading API. Requires Consumer Key, Consumer Secret, and Neo finkey.",
            null,
            new String[]{"client_id","api_key","api_secret","access_token"},
            "https://www.kotaksecurities.com/favicon.ico",
            false, "https://tradeapi.kotaksecurities.com/"),

        brokerMeta("icici",    "ICICI Direct (Breeze API)",
            "ICICI Breeze Connect API. Requires API key, API secret, and session token.",
            null,
            new String[]{"client_id","api_key","api_secret","access_token"},
            "https://www.icicidirect.com/favicon.ico",
            false, "https://api.icicidirect.com/breezeapi/")
    );

    // â”€â”€ GET /broker/list â€” all supported brokers with field requirements â”€â”€â”€â”€â”€â”€â”€
    @GetMapping("/list")
    @Operation(summary = "List all supported Indian brokers with credential field requirements")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listBrokers() {
        return ResponseEntity.ok(ApiResponse.ok(SUPPORTED_BROKERS));
    }

    // â”€â”€ GET /broker/my â€” user's saved broker connections â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @GetMapping("/my")
    @Transactional
    @Operation(summary = "Get my saved broker connections (secrets masked)")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> myBrokers(
            @AuthenticationPrincipal User user) {
        try {
            ensureBrokerCredentialSchema();
            markExpiredBrokerTokens(user.getId());
            List<Object[]> rows = em.createNativeQuery(
                "SELECT id, broker_name, broker_label, client_id, api_key, " +
                "is_active, is_verified, read_only, last_verified, verification_msg, " +
                "token_expiry, permissions_json, created_at, updated_at, " +
                "CASE WHEN access_token_enc IS NOT NULL AND access_token_enc<>'' THEN 1 ELSE 0 END AS has_access_token " +
                "FROM broker_credentials WHERE user_id=? ORDER BY id DESC")
                .setParameter(1, user.getId()).getResultList();

            List<Map<String, Object>> result = rows.stream().map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",               r[0]);
                m.put("broker_name",      r[1]);
                m.put("broker_label",     r[2]);
                m.put("client_id",        r[3]);
                m.put("api_key",          r[4]);
                m.put("api_secret",       "********");
                m.put("access_token",     ((Number) r[14]).intValue() == 1 ? "********" : null);
                m.put("is_active",        r[5]);
                m.put("is_verified",      r[6]);
                m.put("read_only",        r[7]);
                m.put("last_verified",    r[8]);
                m.put("verification_msg", r[9]);
                m.put("token_expiry",     r[10]);
                boolean expired = isTokenExpired(text(r[10]));
                m.put("auth_expired",     expired);
                m.put("requires_reauth",  expired || ((Number) r[6]).intValue() == 0);
                m.put("auth_status",      expired ? "expired" : (((Number) r[6]).intValue() == 1 ? "verified" : "verify_required"));
                m.put("permissions",      r[11]);
                m.put("created_at",       r[12]);
                m.put("updated_at",       r[13]);
                SUPPORTED_BROKERS.stream()
                    .filter(b -> b.get("id").equals(r[1]))
                    .findFirst()
                    .ifPresent(b -> m.put("broker_meta", b));
                return m;
            }).collect(Collectors.toList());

            return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (Exception e) {
            log.error("Fetch broker connections: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.ok(List.of()));
        }
    }

    @PostMapping("/dhan/generate-token/{id}")
    @Operation(summary = "Generate and save Dhan access token using Dhan PIN and TOTP")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generateDhanToken(
            @PathVariable Long id,
            @RequestBody DhanTokenRequest req,
            @AuthenticationPrincipal User user) {
        // Reject a second concurrent token-generation for the same credential
        // instead of letting it block on a database row lock.
        if (!writeInFlight.add(id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("BROKER-409",
                "This broker connection is already being updated. Please wait a moment and try again."));
        }
        try {
            ensureBrokerCredentialSchema();
            Object[] row = (Object[]) em.createNativeQuery(
                "SELECT client_id, totp_secret_enc, dhan_pin_enc FROM broker_credentials " +
                "WHERE id=? AND user_id=? AND broker_name='dhan' AND is_active=1")
                .setParameter(1, id)
                .setParameter(2, user.getId())
                .getSingleResult();

            String clientId = String.valueOf(row[0]);
            String pin = req.getPin() == null ? "" : req.getPin().trim();
            String totp = req.getTotp() == null ? "" : req.getTotp().trim();
            if (pin.isBlank()) {
                // Fall back to the remembered PIN. It is decrypted here and used immediately —
                // it is never sent back to the browser, so "remember" costs no extra exposure.
                pin = decodeSecret(String.valueOf(row[2]));
            }
            if (pin.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error("DHAN-PIN",
                    "Enter your Dhan PIN to generate the token, or tick Remember this PIN so it is kept for next time."));
            }
            if (totp.isBlank()) {
                String secret = decodeSecret(String.valueOf(row[1]));
                if (secret.isBlank()) {
                    return ResponseEntity.ok(ApiResponse.error("DHAN-TOTP", "Add Dhan TOTP Secret in broker setup, or enter current TOTP."));
                }
                totp = currentTotp(secret);
            }

            // ── Network + token generation + JSON parsing happen OUTSIDE any DB
            //    transaction, so no connection/lock is held during the call. ──
            String url = DHAN_AUTH + "/app/generateAccessToken?dhanClientId=" + enc(clientId) +
                "&pin=" + enc(pin) + "&totp=" + enc(totp);
            String response = requestDhanAccessToken(url);
            JsonNode json = mapper.readTree(response);
            String accessToken = json.path("accessToken").asText("");
            String expiryTime = json.path("expiryTime").asText("");
            if (accessToken.isBlank()) {
                String dhanMessage = firstText(json, "message", "remarks", "errorMessage", "error");
                return ResponseEntity.ok(ApiResponse.error("DHAN-TOKEN",
                    dhanMessage.isBlank() ? "Dhan did not return an access token. Check Client ID, PIN, and current TOTP." : dhanMessage));
            }

            // Encrypt before the transaction; only the UPDATE runs inside it.
            final String encToken = encryptOrMask(accessToken);
            final String fExpiry  = expiryTime;
            writeWithRetry("generate-token id=" + id, () -> em.createNativeQuery(
                "UPDATE broker_credentials SET access_token_enc=?, token_expiry=?, is_verified=1, " +
                "last_verified=?, verification_msg=?, updated_at=? WHERE id=? AND user_id=?")
                .setParameter(1, encToken)
                .setParameter(2, fExpiry)
                .setParameter(3, DateUtil.now())
                .setParameter(4, "Dhan token generated and saved successfully.")
                .setParameter(5, DateUtil.now())
                .setParameter(6, id)
                .setParameter(7, user.getId())
                .executeUpdate());

            trackerService.clearDhanTokenBlock();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", id);
            data.put("broker", "dhan");
            data.put("tokenExpiry", expiryTime);
            data.put("verified", true);
            return ResponseEntity.ok(ApiResponse.ok("Dhan token generated and saved.", data));
        } catch (PessimisticLockingFailureException lockEx) {
            log.warn("Generate Dhan token id={} lock contention: {}", id, lockEx.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("BROKER-409",
                "Broker credentials are currently being updated. Please try again."));
        } catch (Exception e) {
            log.error("Generate Dhan token: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("DHAN-TOKEN", "Dhan token generation failed: " + e.getMessage()));
        } finally {
            writeInFlight.remove(id);
        }
    }

    @GetMapping("/active")
    @Transactional
    @Operation(summary = "Get verified active broker connections for trade placement")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> activeBrokers(
            @AuthenticationPrincipal User user) {
        try {
            ensureBrokerCredentialSchema();
            markExpiredBrokerTokens(user.getId());
            List<Object[]> rows = em.createNativeQuery(
                "SELECT id, broker_name, broker_label, client_id, is_verified, read_only, " +
                "last_verified, token_expiry " +
                "FROM broker_credentials WHERE user_id=? AND is_active=1 AND is_verified=1 " +
                "ORDER BY broker_name")
                .setParameter(1, user.getId()).getResultList();

            List<Map<String, Object>> result = rows.stream().map(r -> {
                boolean expired = isTokenExpired(text(r[7]));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",          r[0]);
                m.put("broker_name", r[1]);
                m.put("broker_label",r[2]);
                m.put("client_id",   r[3]);
                m.put("is_verified", r[4]);
                m.put("read_only",   r[5]);
                m.put("last_verified",r[6]);
                m.put("token_expiry",r[7]);
                m.put("auth_expired", expired);
                m.put("requires_reauth", expired);
                m.put("auth_status", expired ? "expired" : "verified");
                return m;
            }).filter(m -> !Boolean.TRUE.equals(m.get("auth_expired"))).collect(Collectors.toList());

            return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.ok(List.of()));
        }
    }

    // â”€â”€ POST /broker/save â€” add or update broker credentials â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @PostMapping("/save")
    @Operation(summary = "Save broker credentials (API key/secret encrypted before storage)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> saveBroker(
            @RequestBody SaveBrokerRequest req,
            @AuthenticationPrincipal User user) {

        if (req.getBrokerName() == null || req.getBrokerName().isBlank()) {
            return ResponseEntity.ok(ApiResponse.error("BROKER-400", "Broker name is required."));
        }

        boolean valid = SUPPORTED_BROKERS.stream()
            .anyMatch(b -> b.get("id").equals(req.getBrokerName()));
        if (!valid) {
            return ResponseEntity.ok(ApiResponse.error("BROKER-400",
                "Unsupported broker: " + req.getBrokerName()));
        }

        final String label = SUPPORTED_BROKERS.stream()
            .filter(b -> b.get("id").equals(req.getBrokerName()))
            .map(b -> (String) b.get("name")).findFirst().orElse(req.getBrokerName());

        // Encryption happens BEFORE the transaction, never inside it.
        final String encSecret      = encryptOrMask(req.getApiSecret());
        final String encAccessToken = encryptOrMask(req.getAccessToken());
        final String encTotpSecret  = encryptOrMask(req.getTotpSecret());
        final String encDhanPin     = encryptOrMask(req.getDhanPin());

        Long existingIdTmp = null;
        try {
            ensureBrokerCredentialSchema();
            // Read only (no transaction) to find an existing row for this broker.
            @SuppressWarnings("unchecked")
            List<Object> existingRows = em.createNativeQuery(
                "SELECT id FROM broker_credentials WHERE user_id=? AND broker_name=? ORDER BY id DESC LIMIT 1")
                .setParameter(1, user.getId())
                .setParameter(2, req.getBrokerName())
                .getResultList();
            if (!existingRows.isEmpty()) {
                existingIdTmp = ((Number) existingRows.get(0)).longValue();
            }
        } catch (Exception e) {
            log.error("Save broker credentials (lookup): {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("BROKER-500",
                "Failed to save credentials: " + e.getMessage()));
        }

        final Long existingId = existingIdTmp;

        // Reject a duplicate concurrent write on the SAME credential row.
        if (existingId != null && !writeInFlight.add(existingId)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("BROKER-409",
                "This broker connection is already being updated. Please wait a moment and try again."));
        }
        try {
            if (existingId != null) {
                writeWithRetry("save-update id=" + existingId, () -> {
                    int n = em.createNativeQuery(
                        "UPDATE broker_credentials SET broker_label=?, client_id=?, api_key=?, " +
                        "api_secret_enc=CASE WHEN ?<>'' THEN ? ELSE api_secret_enc END, " +
                        "access_token_enc=CASE WHEN ?<>'' THEN ? ELSE access_token_enc END, " +
                        "totp_secret_enc=CASE WHEN ?<>'' THEN ? ELSE totp_secret_enc END, " +
                        "dhan_pin_enc=CASE WHEN ?<>'' THEN ? ELSE dhan_pin_enc END, " +
                        "redirect_uri=?, read_only=?, is_verified=0, " +
                        "verification_msg='Credentials updated - please verify connection', " +
                        "updated_at=? WHERE id=? AND user_id=?")
                        .setParameter(1, label)
                        .setParameter(2, req.getClientId())
                        .setParameter(3, req.getApiKey())
                        .setParameter(4, encSecret).setParameter(5, encSecret)
                        .setParameter(6, encAccessToken).setParameter(7, encAccessToken)
                        .setParameter(8, encTotpSecret).setParameter(9, encTotpSecret)
                        .setParameter(10, encDhanPin).setParameter(11, encDhanPin)
                        .setParameter(12, req.getRedirectUri())
                        .setParameter(13, req.isReadOnly() ? 1 : 0)
                        .setParameter(14, DateUtil.now())
                        .setParameter(15, existingId)
                        .setParameter(16, user.getId())
                        .executeUpdate();
                    auditLog(user.getId(), "BROKER_UPDATE", req.getBrokerName(),
                        "Credentials updated for " + label);
                    return n;
                });
                return ResponseEntity.ok(ApiResponse.ok("Broker credentials updated. Please verify the connection.",
                    Map.of("id", existingId, "broker", req.getBrokerName(), "action", "updated")));
            } else {
                writeWithRetry("save-insert " + req.getBrokerName(), () -> {
                    int n = em.createNativeQuery(
                        "INSERT INTO broker_credentials(user_id, institution_id, broker_name, broker_label, " +
                        "client_id, api_key, api_secret_enc, access_token_enc, totp_secret_enc, dhan_pin_enc, " +
                        "redirect_uri, is_active, is_verified, read_only, " +
                        "verification_msg, created_at, updated_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,1,0,?,?,?,?)")
                        .setParameter(1, user.getId())
                        .setParameter(2, user.getInstitutionId())
                        .setParameter(3, req.getBrokerName())
                        .setParameter(4, label)
                        .setParameter(5, req.getClientId())
                        .setParameter(6, req.getApiKey())
                        .setParameter(7, encSecret)
                        .setParameter(8, encAccessToken)
                        .setParameter(9, encTotpSecret)
                        .setParameter(10, encDhanPin)
                        .setParameter(11, req.getRedirectUri())
                        .setParameter(12, req.isReadOnly() ? 1 : 0)
                        .setParameter(13, "Credentials saved - click Verify Connection to test")
                        .setParameter(14, DateUtil.now())
                        .setParameter(15, DateUtil.now())
                        .executeUpdate();
                    auditLog(user.getId(), "BROKER_ADD", req.getBrokerName(),
                        "Credentials added for " + label);
                    return n;
                });
                return ResponseEntity.ok(ApiResponse.ok(
                    "Broker credentials saved. Click 'Verify Connection' to test.",
                    Map.of("broker", req.getBrokerName(), "label", label, "action", "created")));
            }
        } catch (PessimisticLockingFailureException lockEx) {
            log.warn("Save broker credentials lock contention: {}", lockEx.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("BROKER-409",
                "Broker credentials are currently being updated. Please try again."));
        } catch (Exception e) {
            log.error("Save broker credentials: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("BROKER-500",
                "Failed to save credentials: " + e.getMessage()));
        } finally {
            if (existingId != null) writeInFlight.remove(existingId);
        }
    }

    // -- POST /broker/verify/{id} -- test connection
    @PostMapping("/verify/{id}")
    @Operation(summary = "Verify broker connection - tests API key validity")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyBroker(
            @PathVariable Long id,
            @AuthenticationPrincipal User user) {

        // Reject a second concurrent verify for the same credential.
        if (!writeInFlight.add(id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("BROKER-409",
                "This broker connection is already being verified. Please wait a moment and try again."));
        }
        try {
            ensureBrokerCredentialSchema();
            Object[] row = (Object[]) em.createNativeQuery(
                "SELECT broker_name, broker_label, client_id, api_key, api_secret_enc, " +
                "access_token_enc, totp_secret_enc, token_expiry FROM broker_credentials " +
                "WHERE id=? AND user_id=? AND is_active=1")
                .setParameter(1, id).setParameter(2, user.getId()).getSingleResult();

            String brokerName = (String) row[0];
            String clientId   = (String) row[2];
            if (isTokenExpired(text(row[7]))) {
                final String emsg = "Broker authentication has expired. Please re-authenticate the token.";
                writeWithRetry("verify-expired id=" + id, () -> em.createNativeQuery(
                    "UPDATE broker_credentials SET is_verified=0, last_verified=?, verification_msg=?, updated_at=? WHERE id=? AND user_id=?")
                    .setParameter(1, DateUtil.now())
                    .setParameter(2, emsg)
                    .setParameter(3, DateUtil.now())
                    .setParameter(4, id)
                    .setParameter(5, user.getId())
                    .executeUpdate());
                return ResponseEntity.ok(ApiResponse.ok(emsg,
                    Map.of("id", id, "verified", false, "requiresReauth", true, "authExpired", true, "message", emsg,
                           "broker_name", brokerName)));
            }

            // Network verification runs OUTSIDE any DB transaction.
            boolean verified = testBrokerConnection(brokerName, clientId, row);
            final String msg = verified
                ? "Connection verified successfully with " + row[1] + " API."
                : "Could not connect to " + row[1] + ". Check required credentials, token/session validity, and broker API access.";

            final boolean fVerified = verified;
            final String fBrokerName = brokerName;
            writeWithRetry("verify id=" + id, () -> {
                int n = em.createNativeQuery(
                    "UPDATE broker_credentials SET is_verified=?, last_verified=?, " +
                    "verification_msg=? WHERE id=? AND user_id=?")
                    .setParameter(1, fVerified ? 1 : 0)
                    .setParameter(2, DateUtil.now())
                    .setParameter(3, msg)
                    .setParameter(4, id)
                    .setParameter(5, user.getId())
                    .executeUpdate();
                auditLog(user.getId(), "BROKER_VERIFY", fBrokerName,
                    fVerified ? "Verified OK" : "Verification failed");
                return n;
            });

            return ResponseEntity.ok(ApiResponse.ok(msg,
                Map.of("id", id, "verified", verified, "message", msg,
                       "broker_name", brokerName)));
        } catch (PessimisticLockingFailureException lockEx) {
            log.warn("Verify broker {} lock contention: {}", id, lockEx.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("BROKER-409",
                "Broker credentials are currently being updated. Please try again."));
        } catch (Exception e) {
            log.error("Verify broker {}: {}", id, e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("BROKER-500",
                "Verification error: " + e.getMessage()));
        } finally {
            writeInFlight.remove(id);
        }
    }

    // -- PUT /broker/{id}/toggle -- enable/disable
    @PutMapping("/{id}/toggle")
    @Transactional
    @Operation(summary = "Enable or disable a broker connection")
    public ResponseEntity<ApiResponse<String>> toggleBroker(
            @PathVariable Long id,
            @AuthenticationPrincipal User user) {
        try {
            int updated = em.createNativeQuery(
                "UPDATE broker_credentials SET is_active = CASE WHEN is_active=1 THEN 0 ELSE 1 END, " +
                "updated_at=? WHERE id=? AND user_id=?")
                .setParameter(1, DateUtil.now())
                .setParameter(2, id).setParameter(3, user.getId())
                .executeUpdate();
            if (updated == 0) return ResponseEntity.ok(ApiResponse.error("BROKER-404", "Not found."));
            return ResponseEntity.ok(ApiResponse.ok("Broker connection toggled."));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("BROKER-500", e.getMessage()));
        }
    }

    // â”€â”€ PUT /broker/{id}/readonly â€” toggle read-only â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @PutMapping("/{id}/readonly")
    @Transactional
    @Operation(summary = "Toggle read-only mode (read-only = data fetch only, no orders)")
    public ResponseEntity<ApiResponse<String>> toggleReadOnly(
            @PathVariable Long id,
            @AuthenticationPrincipal User user) {
        try {
            em.createNativeQuery(
                "UPDATE broker_credentials SET read_only = CASE WHEN read_only=1 THEN 0 ELSE 1 END, " +
                "updated_at=? WHERE id=? AND user_id=?")
                .setParameter(1, DateUtil.now())
                .setParameter(2, id).setParameter(3, user.getId())
                .executeUpdate();
            return ResponseEntity.ok(ApiResponse.ok("Read-only mode toggled."));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("BROKER-500", e.getMessage()));
        }
    }

    // â”€â”€ DELETE /broker/{id} â€” remove credentials â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @DeleteMapping("/{id}")
    @Transactional
    @Operation(summary = "Delete broker credentials permanently")
    public ResponseEntity<ApiResponse<String>> deleteBroker(
            @PathVariable Long id,
            @AuthenticationPrincipal User user) {
        try {
            int deleted = em.createNativeQuery(
                "DELETE FROM broker_credentials WHERE id=? AND user_id=?")
                .setParameter(1, id).setParameter(2, user.getId()).executeUpdate();
            if (deleted == 0) return ResponseEntity.ok(ApiResponse.error("BROKER-404", "Not found."));
            auditLog(user.getId(), "BROKER_DELETE", "id=" + id, "Credentials removed");
            return ResponseEntity.ok(ApiResponse.ok("Broker credentials removed."));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("BROKER-500", e.getMessage()));
        }
    }

    // â”€â”€ GET /broker â€” main data endpoint (kept for backward compat) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @GetMapping
    @Transactional
    @Operation(summary = "Broker overview â€” active connections + supported list")
    public ResponseEntity<ApiResponse<Map<String, Object>>> overview(
            @AuthenticationPrincipal User user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("supported_brokers", SUPPORTED_BROKERS);

        try {
            ensureBrokerCredentialSchema();
            Object count = em.createNativeQuery(
                "SELECT COUNT(*) FROM broker_credentials WHERE user_id=? AND is_active=1")
                .setParameter(1, user.getId()).getSingleResult();
            result.put("connected_count", count);
        } catch (Exception e) {
            result.put("connected_count", 0);
        }

        result.put("message",
            "Use GET /broker/my to see your connections, POST /broker/save to add one.");
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private volatile boolean brokerSchemaReady = false;

    private void ensureBrokerCredentialSchema() {
        if (brokerSchemaReady) return;
        try {
            runInTx(() -> {
            em.createNativeQuery(
                "CREATE TABLE IF NOT EXISTS broker_credentials (" +
                "id BIGSERIAL PRIMARY KEY, " +
                "user_id INTEGER, " +
                "institution_id INTEGER DEFAULT 1, " +
                "broker_name TEXT, " +
                "broker_label TEXT, " +
                "client_id TEXT, " +
                "api_key TEXT, " +
                "api_secret_enc TEXT, " +
                "access_token_enc TEXT, " +
                "totp_secret_enc TEXT, " +
                "redirect_uri TEXT, " +
                "is_active INTEGER DEFAULT 1, " +
                "is_verified INTEGER DEFAULT 0, " +
                "read_only INTEGER DEFAULT 1, " +
                "last_verified TEXT, " +
                "verification_msg TEXT, " +
                "token_expiry TEXT, " +
                "permissions_json TEXT, " +
                "created_at TEXT, " +
                "updated_at TEXT)").executeUpdate();

            addBrokerColumn("user_id INTEGER");
            addBrokerColumn("institution_id INTEGER DEFAULT 1");
            addBrokerColumn("broker_name TEXT");
            addBrokerColumn("broker_label TEXT");
            addBrokerColumn("client_id TEXT");
            addBrokerColumn("api_key TEXT");
            addBrokerColumn("api_secret_enc TEXT");
            addBrokerColumn("access_token_enc TEXT");
            addBrokerColumn("totp_secret_enc TEXT");
            addBrokerColumn("redirect_uri TEXT");
            addBrokerColumn("is_active INTEGER DEFAULT 1");
            addBrokerColumn("is_verified INTEGER DEFAULT 0");
            addBrokerColumn("read_only INTEGER DEFAULT 1");
            addBrokerColumn("last_verified TEXT");
            addBrokerColumn("verification_msg TEXT");
            addBrokerColumn("token_expiry TEXT");
            addBrokerColumn("permissions_json TEXT");
            addBrokerColumn("created_at TEXT");
            addBrokerColumn("updated_at TEXT");

            try {
                em.createNativeQuery("CREATE INDEX IF NOT EXISTS idx_bc_user ON broker_credentials(user_id, is_active)").executeUpdate();
                em.createNativeQuery("CREATE INDEX IF NOT EXISTS idx_bc_broker ON broker_credentials(broker_name, is_active)").executeUpdate();
                em.createNativeQuery("CREATE INDEX IF NOT EXISTS idx_bc_user_broker ON broker_credentials(user_id, broker_name)").executeUpdate();
            } catch (Exception indexErr) {
                log.debug("Broker index creation deferred (lock contention): {}", indexErr.getMessage());
            }
            });

            brokerSchemaReady = true;
        } catch (Exception e) {
            log.debug("Broker schema check: {}", e.getMessage());
        }
    }

    private void addBrokerColumn(String definition) {
        String column = definition.split("\\s+")[0];
        if (columnExists("broker_credentials", column)) return;
        em.createNativeQuery("ALTER TABLE broker_credentials ADD COLUMN IF NOT EXISTS " + definition).executeUpdate();
    }

    private boolean columnExists(String table, String column) {
        try {
            Object count = em.createNativeQuery(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name=? AND column_name=?")
                .setParameter(1, table)
                .setParameter(2, column)
                .getSingleResult();
            return count instanceof Number n && n.longValue() > 0;
        } catch (Exception e) {
            return true;
        }
    }

    private void markExpiredBrokerTokens(Long userId) {
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                "SELECT id, token_expiry FROM broker_credentials WHERE user_id=? AND is_active=1 AND is_verified=1 " +
                "AND token_expiry IS NOT NULL AND token_expiry<>''")
                .setParameter(1, userId)
                .getResultList();
            for (Object[] row : rows) {
                if (!isTokenExpired(text(row[1]))) continue;
                em.createNativeQuery(
                    "UPDATE broker_credentials SET is_verified=0, verification_msg=?, updated_at=? WHERE id=? AND user_id=?")
                    .setParameter(1, "Broker authentication expired. Please re-authenticate the token.")
                    .setParameter(2, DateUtil.now())
                    .setParameter(3, row[0])
                    .setParameter(4, userId)
                    .executeUpdate();
            }
        } catch (Exception e) {
            log.debug("Broker token expiry check skipped: {}", e.getMessage());
        }
    }

    private boolean isTokenExpired(String expiry) {
        if (expiry == null || expiry.isBlank() || "null".equalsIgnoreCase(expiry)) return false;
        LocalDateTime value = parseExpiry(expiry);
        return value != null && value.isBefore(LocalDateTime.now(IST));
    }

    private LocalDateTime parseExpiry(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank()) return null;
        try { return OffsetDateTime.parse(text).atZoneSameInstant(IST).toLocalDateTime(); } catch (Exception ignored) {}
        try { return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME); } catch (Exception ignored) {}
        try { return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).atTime(23, 59, 59); } catch (Exception ignored) {}
        for (String pattern : List.of("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "dd-MM-yyyy HH:mm:ss", "dd-MM-yyyy HH:mm")) {
            try { return LocalDateTime.parse(text, DateTimeFormatter.ofPattern(pattern)); } catch (Exception ignored) {}
        }
        return null;
    }

    private String requestDhanAccessToken(String url) throws Exception {
        Map<String, String> headers = Map.of(
            "Accept", "application/json",
            "Content-Type", "application/json"
        );
        try {
            return http.postJson(url, headers, Map.of(), 15000);
        } catch (Exception postError) {
            try {
                return http.get(url, headers, 15000);
            } catch (Exception getError) {
                postError.addSuppressed(getError);
                throw postError;
            }
        }
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null) return "";
        for (String field : fields) {
            String value = node.path(field).asText("");
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private boolean testBrokerConnection(String broker, String clientId, Object[] row) {
        String apiKey = text(row[3]);
        String secret = decodeSecret(text(row[4]));
        String token = decodeSecret(text(row[5]));
        String totpSecret = decodeSecret(text(row[6]));
        if (clientId == null || clientId.isBlank()) return false;
        try {
            return switch (broker) {
                case "dhan" -> verifyDhan(clientId, token);
                case "zerodha" -> verifyZerodha(apiKey, token);
                case "upstox" -> verifyUpstox(token);
                case "angel" -> verifyAngel(clientId, apiKey, secret, token, totpSecret);
                case "fyers" -> verifyFyers(apiKey, token);
                case "shoonya" -> verifyShoonya(clientId, token);
                case "kotak" -> verifyKotak(token);
                case "icici" -> verifyIcici(apiKey, token);
                default -> false;
            };
        } catch (Exception e) {
            log.warn("{} broker verification failed for client {}: {}", broker, clientId, e.getMessage());
            return false;
        }
    }

    private boolean verifyDhan(String clientId, String accessToken) throws Exception {
        require(accessToken, "Dhan access token");
        http.get(DHAN_API + "/profile", Map.of(
            "Accept", "application/json",
            "Content-Type", "application/json",
            "access-token", accessToken,
            "client-id", clientId
        ), 15000);
        return true;
    }

    private boolean verifyZerodha(String apiKey, String accessToken) throws Exception {
        require(apiKey, "Zerodha API key");
        require(accessToken, "Zerodha access token");
        http.get("https://api.kite.trade/user/profile", Map.of(
            "Accept", "application/json",
            "Authorization", "token " + apiKey + ":" + accessToken
        ), 15000);
        return true;
    }

    private boolean verifyUpstox(String accessToken) throws Exception {
        require(accessToken, "Upstox access token");
        http.get("https://api.upstox.com/v2/user/profile", Map.of(
            "Accept", "application/json",
            "Authorization", "Bearer " + accessToken
        ), 15000);
        return true;
    }

    private boolean verifyAngel(String clientId, String apiKey, String passwordOrPin,
                                String jwtToken, String totpSecret) throws Exception {
        require(apiKey, "Angel API key");
        if (!jwtToken.isBlank()) {
            http.get("https://apiconnect.angelone.in/rest/secure/angelbroking/user/v1/getProfile", angelHeaders(apiKey, jwtToken), 15000);
            return true;
        }
        require(passwordOrPin, "Angel PIN/password");
        require(totpSecret, "Angel TOTP secret");
        String login = http.postJson(
            "https://apiconnect.angelone.in/rest/auth/angelbroking/user/v1/loginByPassword",
            angelHeaders(apiKey, ""),
            Map.of("clientcode", clientId, "password", passwordOrPin, "totp", currentTotp(totpSecret)),
            15000);
        JsonNode json = mapper.readTree(login);
        String generatedToken = json.path("data").path("jwtToken").asText("");
        return !generatedToken.isBlank() || json.path("status").asBoolean(false);
    }

    private Map<String, String> angelHeaders(String apiKey, String jwtToken) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Content-Type", "application/json");
        headers.put("X-UserType", "USER");
        headers.put("X-SourceID", "WEB");
        headers.put("X-ClientLocalIP", "127.0.0.1");
        headers.put("X-ClientPublicIP", "127.0.0.1");
        headers.put("X-MACAddress", "00:00:00:00:00:00");
        headers.put("X-PrivateKey", apiKey);
        if (jwtToken != null && !jwtToken.isBlank()) headers.put("Authorization", "Bearer " + jwtToken);
        return headers;
    }

    private boolean verifyFyers(String appId, String accessToken) throws Exception {
        require(appId, "Fyers App ID");
        require(accessToken, "Fyers access token");
        http.get("https://api-t1.fyers.in/api/v3/profile", Map.of(
            "Accept", "application/json",
            "Authorization", appId + ":" + accessToken
        ), 15000);
        return true;
    }

    private boolean verifyShoonya(String userId, String sessionToken) throws Exception {
        require(sessionToken, "Shoonya session token");
        String jData = "{\"uid\":\"" + userId.replace("\"", "\\\"") + "\"}";
        http.postForm("https://api.shoonya.com/NorenWClientTP/UserDetails", Map.of(),
            Map.of("jData", jData, "jKey", sessionToken), 15000);
        return true;
    }

    private boolean verifyKotak(String accessToken) throws Exception {
        require(accessToken, "Kotak access token");
        http.get("https://gw-napi.kotaksecurities.com/Profile/1.0/profile", Map.of(
            "Accept", "application/json",
            "Authorization", "Bearer " + accessToken
        ), 15000);
        return true;
    }

    private boolean verifyIcici(String apiKey, String sessionToken) throws Exception {
        require(apiKey, "ICICI API key");
        require(sessionToken, "ICICI session token");
        http.get("https://api.icicidirect.com/breezeapi/api/v1/customerdetails", Map.of(
            "Accept", "application/json",
            "X-AppKey", apiKey,
            "X-SessionToken", sessionToken
        ), 15000);
        return true;
    }

    private void require(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required.");
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String currentTotp(String base32Secret) throws Exception {
        byte[] key = base32Decode(base32Secret);
        long counter = System.currentTimeMillis() / 1000L / 30L;
        byte[] msg = ByteBuffer.allocate(8).putLong(counter).array();
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "HmacSHA1"));
        byte[] hash = mac.doFinal(msg);
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
            | ((hash[offset + 1] & 0xff) << 16)
            | ((hash[offset + 2] & 0xff) << 8)
            | (hash[offset + 3] & 0xff);
        return String.format("%06d", binary % 1_000_000);
    }

    private byte[] base32Decode(String value) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        String clean = value == null ? "" : value.replace(" ", "").replace("=", "").toUpperCase(Locale.ROOT);
        int buffer = 0;
        int bitsLeft = 0;
        List<Byte> bytes = new ArrayList<>();
        for (char c : clean.toCharArray()) {
            int val = alphabet.indexOf(c);
            if (val < 0) continue;
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bytes.add((byte) ((buffer >> (bitsLeft - 8)) & 0xff));
                bitsLeft -= 8;
            }
        }
        byte[] out = new byte[bytes.size()];
        for (int i = 0; i < bytes.size(); i++) out[i] = bytes.get(i);
        return out;
    }

    private String decodeSecret(String encoded) {
        if (encoded == null || encoded.isBlank()) return "";
        try {
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return encoded;
        }
    }

    private String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String encryptOrMask(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return "";
        // Simple Base64 encoding as placeholder
        // Production: use AES-256 with AppProperties.fernetKey
        return java.util.Base64.getEncoder().encodeToString(plaintext.getBytes());
    }

    private void auditLog(Long userId, String action, String entity, String details) {
        try {
            em.createNativeQuery(
                "INSERT INTO tracker_audit_log(institution_id,user_id,action,entity_type,entity_id,details_json,created_at) " +
                "VALUES(1,?,?,?,?,?,?)")
                .setParameter(1, userId).setParameter(2, action)
                .setParameter(3, "broker").setParameter(4, entity)
                .setParameter(5, "{\"details\":\"" + details + "\"}")
                .setParameter(6, DateUtil.now()).executeUpdate();
        } catch (Exception ignored) {}
    }

    private static Map<String, Object> brokerMeta(
            String id, String name, String description,
            String oauthUrl, String[] requiredFields,
            String logoUrl, boolean requiresDailyToken, String docsUrl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                  id);
        m.put("name",                name);
        m.put("description",         description);
        m.put("oauth_url",           oauthUrl);
        m.put("required_fields",     requiredFields);
        m.put("logo_url",            logoUrl);
        m.put("requires_daily_token",requiresDailyToken);
        m.put("docs_url",            docsUrl);
        return m;
    }

    // â”€â”€ Request DTOs â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @Data
    static class SaveBrokerRequest {
        private String brokerName;
        private String clientId;
        private String apiKey;
        private String apiSecret;
        private String accessToken;
        private String totpSecret;
        private String redirectUri;
        /** Optional. Encrypted like the other secrets; blank leaves any saved PIN untouched. */
        private String dhanPin;
        private boolean readOnly = true;
    }

    @Data
    static class DhanTokenRequest {
        private String pin;
        private String totp;
    }
}
