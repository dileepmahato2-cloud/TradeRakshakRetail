package com.traderakshak.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Reads the Real Trading gate (BRD Volume 3, Section 2.5) and the risk limits
 * (Section 2.7) from trep-institution-service, which owns both.
 *
 * These checks are deliberately not reimplemented here: ta_risk_configs and
 * ta_real_trading_gate live in the institution service, and duplicating the
 * evaluation in core would let the two drift apart.
 *
 * Fails closed. A compliance gate that cannot be reached must block the order,
 * never wave it through.
 */
@Service
public class TradingGateClient {

    private static final Logger log = LoggerFactory.getLogger(TradingGateClient.class);
    private static final int TIMEOUT_MS = 8000;

    private final BlockingHttpService http;
    private final ObjectMapper mapper;

    @Value("${institution.service.url:http://localhost:8081}")
    private String baseUrl;

    public TradingGateClient(BlockingHttpService http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    /** Outcome of the pre-order checks. {@code code} is null when allowed. */
    public record Decision(boolean allowed, String code, String message) {
        static Decision ok() { return new Decision(true, null, null); }
        static Decision block(String code, String message) { return new Decision(false, code, message); }
    }

    /**
     * Runs the gate then the risk-limit check, in that order, for a real order.
     * Call this immediately before submitting to the broker.
     */
    public Decision checkRealOrder(String authorizationHeader, String tradingMode) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return Decision.block("TRADING-GATE",
                    "Cannot verify the Real Trading gate without an authenticated session.");
        }
        Map<String, String> headers = Map.of(
                "Accept", "application/json",
                "Authorization", authorizationHeader);

        Decision gate = checkGate(headers);
        if (!gate.allowed()) return gate;

        return checkRiskLimits(headers, tradingMode);
    }

    /** BR-2.5.1 / BR-2.5.3 / BR-3.4.2 — eligibility, KYC, broker link, risk acknowledgment. */
    private Decision checkGate(Map<String, String> headers) {
        JsonNode data;
        try {
            data = dataOf(http.get(baseUrl + "/api/trading-academy/real-trading/gate", headers, TIMEOUT_MS));
        } catch (Exception e) {
            log.warn("Real Trading gate check failed; blocking order", e);
            return Decision.block("TRADING-GATE",
                    "Real Trading gate is unavailable, so the order was not sent: " + e.getMessage());
        }
        if (data == null) {
            return Decision.block("TRADING-GATE", "Real Trading gate returned no decision.");
        }
        String status = data.path("gate_status").asText("");
        if ("cleared".equalsIgnoreCase(status)) return Decision.ok();

        return Decision.block("TRADING-GATE",
                "BR-2.5.1: Real Trading gate is " + (status.isEmpty() ? "not cleared" : status) + ". " +
                joinArray(data.path("blocked_reasons")));
    }

    /** BR-2.7.1 — order must be inside the applicable risk limits. */
    private Decision checkRiskLimits(Map<String, String> headers, String tradingMode) {
        String mode = tradingMode == null || tradingMode.isBlank() ? "real" : tradingMode.toLowerCase();
        JsonNode data;
        try {
            String url = baseUrl + "/api/trading-academy/risk/validate-order?tradingMode="
                    + URLEncoder.encode(mode, StandardCharsets.UTF_8);
            data = dataOf(http.get(url, headers, TIMEOUT_MS));
        } catch (Exception e) {
            log.warn("Risk-limit check failed; blocking order", e);
            return Decision.block("TRADING-RISK",
                    "Risk-limit check is unavailable, so the order was not sent: " + e.getMessage());
        }
        if (data == null) {
            return Decision.block("TRADING-RISK", "Risk-limit check returned no decision.");
        }
        if (data.path("allowed").asBoolean(false)) return Decision.ok();

        return Decision.block("TRADING-RISK",
                "BR-2.7.1: Order blocked by risk limits. " + joinArray(data.path("violations")));
    }

    private JsonNode dataOf(String response) throws Exception {
        JsonNode root = mapper.readTree(response);
        JsonNode data = root.path("data");
        return data.isMissingNode() || data.isNull() ? null : data;
    }

    private String joinArray(JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode n : array) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(n.asText());
        }
        return sb.toString();
    }
}
