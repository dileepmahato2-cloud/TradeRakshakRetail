package com.traderakshak.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traderakshak.entity.User;
import com.traderakshak.util.DateUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The real account's balance, read from the broker.
 *
 * <p>There was no funds call anywhere in the platform. {@code CapitalAllocationService.allocate}
 * takes {@code available} as a parameter supplied by its caller, and no caller ever asked the
 * broker - so "spend 50,000 of my real money" was sized against a number the user typed rather
 * than the money they have.
 *
 * <p>Two rules shape this class. A balance is never invented: when the broker cannot be reached
 * the last successful reading is returned WITH its age and an explicit flag saying it is stale,
 * rather than a zero or a guess. And the figures Dhan separates are kept separate - withdrawable
 * cash, utilised margin and buying power are different numbers, and collapsing them into one
 * "balance" is how an account looks richer than it is.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BrokerFundsService {

    private static final String DHAN_API = "https://api.dhan.co/v2";
    private static final int TIMEOUT_MS = 15_000;

    @PersistenceContext private EntityManager em;
    private final DhanBrokerService broker;
    private final BlockingHttpService http;
    private final ObjectMapper mapper;

    /**
     * The real account's funds: refreshed from the broker, or the last reading if that fails.
     *
     * <p>Always answers. {@code stale} says which kind of answer it is, and the caller is
     * expected to show that rather than treat the two as interchangeable.
     */
    public Map<String, Object> funds(User user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("account_type", "REAL");
        out.put("is_real", true);
        out.put("broker_name", "Dhan");

        DhanBrokerService.Credential cred;
        try {
            cred = broker.activeDhanCredential(user);
        } catch (Exception e) {
            cred = null;
        }
        if (cred == null) {
            out.put("connected", false);
            out.put("stale", true);
            out.put("available_balance", null);
            out.put("message", "No broker account is connected, so there is no real balance to read.");
            return out;
        }

        out.put("connected", true);
        out.put("client_id", maskClientId(cred.clientId()));

        try {
            String body = http.get(DHAN_API + "/fundlimit", headers(cred), TIMEOUT_MS);
            JsonNode n = mapper.readTree(body);
            if (n == null || n.isNull()) throw new IllegalStateException("empty response");

            // Dhan's field spellings, including availabelBalance - their typo, not one to
            // "correct" here, because the wire format is theirs. Each is read by both the
            // documented name and the obvious alternative, so a later correction on their side
            // does not silently zero the balance.
            double available = num(n, "availabelBalance", "availableBalance");
            double withdrawable = num(n, "withdrawableBalance");
            double utilized = num(n, "utilizedAmount", "utilisedAmount");
            double collateral = num(n, "collateralAmount");
            double sodLimit = num(n, "sodLimit");

            // Buying power is what can actually be deployed now. Where the broker does not say
            // so directly, available cash is the honest floor - never cash plus collateral,
            // which would overstate what an equity delivery order can use.
            double buyingPower = available > 0 ? available : sodLimit;

            saveSnapshot(user, cred, available, withdrawable, utilized, collateral, buyingPower, body);

            out.put("stale", false);
            out.put("available_balance", round2(available));
            out.put("withdrawable_balance", round2(withdrawable));
            out.put("used_margin", round2(utilized));
            out.put("collateral", round2(collateral));
            out.put("buying_power", round2(buyingPower));
            out.put("fetched_at", nowText());
            return out;

        } catch (Exception e) {
            log.warn("[BrokerFunds] Could not read Dhan fund limit: {}", e.getMessage());
            // The requirement in full: the last balance, its timestamp, and a clear statement
            // that it could not be refreshed.
            Map<String, Object> last = lastSnapshot(user, cred);
            out.put("stale", true);
            out.put("refresh_error", e.getMessage());
            if (last.isEmpty()) {
                out.put("available_balance", null);
                out.put("message", "The broker balance could not be read, and no earlier reading is stored.");
            } else {
                out.putAll(last);
                out.put("message", "Could not refresh from the broker. Showing the balance as at "
                    + last.get("fetched_at") + ".");
            }
            return out;
        }
    }

    @Transactional
    protected void saveSnapshot(User user, DhanBrokerService.Credential cred,
                                double available, double withdrawable, double utilized,
                                double collateral, double buyingPower, String raw) {
        try {
            em.createNativeQuery(
                "INSERT INTO broker_funds_snapshot (user_id, institution_id, broker_name, client_id, "
              + "available_balance, withdrawable_balance, utilized_margin, collateral_amount, "
              + "buying_power, fetched_at, raw_response) "
              + "VALUES (?,?,'dhan',?,?,?,?,?,?,now(),?) "
              + "ON CONFLICT (user_id, institution_id, broker_name, client_id) DO UPDATE SET "
              + "available_balance = EXCLUDED.available_balance, "
              + "withdrawable_balance = EXCLUDED.withdrawable_balance, "
              + "utilized_margin = EXCLUDED.utilized_margin, "
              + "collateral_amount = EXCLUDED.collateral_amount, "
              + "buying_power = EXCLUDED.buying_power, "
              + "fetched_at = EXCLUDED.fetched_at, raw_response = EXCLUDED.raw_response")
                .setParameter(1, user.getId())
                .setParameter(2, user.getInstitutionId() == null ? 1L : user.getInstitutionId())
                .setParameter(3, cred.clientId())
                .setParameter(4, available).setParameter(5, withdrawable)
                .setParameter(6, utilized).setParameter(7, collateral)
                .setParameter(8, buyingPower)
                .setParameter(9, raw == null ? null : raw.substring(0, Math.min(raw.length(), 4000)))
                .executeUpdate();
        } catch (Exception e) {
            // A balance that could not be cached is still a balance that was read.
            log.debug("[BrokerFunds] Could not store the funds snapshot: {}", e.getMessage());
        }
    }

    /** The last reading for this account, with its age, or empty if there has never been one. */
    private Map<String, Object> lastSnapshot(User user, DhanBrokerService.Credential cred) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Object[] r = (Object[]) em.createNativeQuery(
                "SELECT available_balance, withdrawable_balance, utilized_margin, "
              + "collateral_amount, buying_power, to_char(fetched_at,'YYYY-MM-DD HH24:MI:SS'), "
              + "EXTRACT(EPOCH FROM (now() - fetched_at)) "
              + "FROM broker_funds_snapshot WHERE user_id = ? AND institution_id = ? "
              + "AND broker_name = 'dhan' AND client_id = ?")
                .setParameter(1, user.getId())
                .setParameter(2, user.getInstitutionId() == null ? 1L : user.getInstitutionId())
                .setParameter(3, cred.clientId())
                .getSingleResult();

            out.put("available_balance", dbl(r[0]));
            out.put("withdrawable_balance", dbl(r[1]));
            out.put("used_margin", dbl(r[2]));
            out.put("collateral", dbl(r[3]));
            out.put("buying_power", dbl(r[4]));
            out.put("fetched_at", r[5]);
            out.put("age_seconds", r[6] instanceof Number n ? n.longValue() : null);
        } catch (Exception e) {
            // No row yet: the caller distinguishes "stale" from "never read".
        }
        return out;
    }

    private Map<String, String> headers(DhanBrokerService.Credential cred) {
        return Map.of(
            "Accept", "application/json",
            "Content-Type", "application/json",
            "access-token", cred.accessToken(),
            "client-id", cred.clientId());
    }

    /** The first of these fields the response actually carries. */
    private static double num(JsonNode n, String... names) {
        for (String name : names) {
            JsonNode v = n.get(name);
            if (v != null && v.isNumber()) return v.asDouble();
            if (v != null && v.isTextual()) {
                try { return Double.parseDouble(v.asText().trim()); } catch (Exception ignored) { }
            }
        }
        return 0.0;
    }

    /** Enough of the client id to recognise the account, not enough to reuse it. */
    private static String maskClientId(String clientId) {
        if (clientId == null || clientId.length() <= 4) return "****";
        return "****" + clientId.substring(clientId.length() - 4);
    }

    private static Double dbl(Object v) { return v instanceof Number n ? round2(n.doubleValue()) : null; }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static String nowText() { return DateUtil.now(); }
}
