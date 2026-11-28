package com.traderakshak.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Whether the stored Dhan access token has expired.
 *
 * <p>One place, because two callers need the same answer and must not drift: the live WebSocket
 * feed and the REST price refresh. Both were discovering an expired token by failing against
 * Dhan - the feed with AUTHENTICATION_FAILED, the quote endpoint with a 200 and an empty payload -
 * and both then retried on their own schedule. On 2026-11-24 that produced 156 rejected WebSocket
 * connections and a quote call per refresh cycle, for a whole session, over one expired credential.
 *
 * <p>A Dhan token lasts about 24 hours, so this is due every single day before the open. Asking
 * the stored expiry first costs one indexed row read and saves every one of those calls.
 *
 * <p><b>Unknown is never treated as expired.</b> A missing, unparseable or future expiry lets the
 * caller proceed exactly as before. The worst case is the old behaviour; refusing to call Dhan
 * because a query failed would be a self-inflicted outage.
 */
@Service
public class DhanTokenStatus {

    private static final Logger log = LoggerFactory.getLogger(DhanTokenStatus.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @PersistenceContext
    private EntityManager em;

    /** Cached briefly: this is consulted on every refresh cycle and the answer changes rarely. */
    private volatile String cached;
    private volatile long cachedAtMs;
    private static final long TTL_MS = 60_000L;

    /**
     * @return a plain, actionable sentence when the token has definitely expired, else null.
     */
    public String expiredMessage() {
        long now = System.currentTimeMillis();
        if (now - cachedAtMs < TTL_MS) return cached;
        String msg = compute();
        cached = msg;
        cachedAtMs = now;
        return msg;
    }

    /** True when Dhan should not be called at all. */
    public boolean isExpired() {
        return expiredMessage() != null;
    }

    /** Called after a successful re-authentication so the next call re-reads immediately. */
    public void invalidate() {
        cachedAtMs = 0;
    }

    @SuppressWarnings("unchecked")
    private String compute() {
        try {
            // A single-column native query returns the scalar itself, not an Object[].
            List<Object> rows = em.createNativeQuery(
                "SELECT COALESCE(token_expiry,'') FROM broker_credentials "
              + "WHERE broker_name='dhan' AND is_active=1 AND is_verified=1 "
              + "ORDER BY updated_at DESC NULLS LAST LIMIT 1").getResultList();
            if (rows.isEmpty()) return null;
            Object raw = rows.get(0);
            String expiry = raw == null ? "" : String.valueOf(raw).trim();
            if (expiry.length() < 10) return null;
            // The column is TEXT and has carried both 'yyyy-MM-ddTHH:mm:ss' and
            // 'yyyy-MM-dd HH:mm:ss'. Normalising the separator lets one string comparison handle
            // both, and avoids a parse that could throw on a third format and be read as expired.
            String nowIso = LocalDateTime.now(IST).toString().replace('T', ' ');
            String expIso = expiry.replace('T', ' ');
            if (expIso.compareTo(nowIso) > 0) return null;
            return "Dhan access token expired at " + expiry.substring(0, Math.min(19, expiry.length()))
                 + ". Dhan is not being called for prices until it is renewed: open Broker and "
                 + "re-authenticate Dhan, and both the live feed and the price refresh resume on "
                 + "their own. A Dhan token lasts about 24 hours, so this falls due daily before "
                 + "the 09:15 open.";
        } catch (Exception e) {
            log.debug("Dhan token expiry check skipped: {}", e.getMessage());
            return null;
        }
    }
}
