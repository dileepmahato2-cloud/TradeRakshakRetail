package com.traderakshak.service;

import java.util.Locale;

/**
 * Dhan order statuses, and the correlation id that makes an order findable again.
 *
 * <p>Pure and separate from the service that polls, because both pieces are the kind that fail
 * silently. A status mis-mapped as terminal stops an order being followed while it is still live;
 * a correlation id Dhan rejects means a lost placement response can never be resolved, and the
 * user is left not knowing whether they own the stock.
 */
public final class BrokerOrderStatus {

    private BrokerOrderStatus() {}

    /**
     * Whether Dhan will send no further updates for this order.
     *
     * <p>From the v2 Orders documentation the full set is TRANSIT, PENDING, REJECTED, CANCELLED,
     * PART_TRADED, TRADED, EXPIRED.
     *
     * <p><b>PART_TRADED is NOT terminal.</b> It is the status most easily mistaken for one: some
     * quantity has filled, so the order looks dealt with, but the remainder is still working at
     * the exchange and will move to TRADED, CANCELLED or EXPIRED. Treating it as terminal would
     * freeze {@code pending_qty} at a stale number and leave a position whose real size nobody
     * knows.
     *
     * <p>An unknown status is treated as NOT terminal. Dhan may add one, and continuing to poll an
     * order that has in fact finished costs one row in a request that was being made anyway, while
     * abandoning one that has not is a position silently dropped.
     */
    public static boolean isTerminal(String status) {
        if (status == null) return false;
        switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "TRADED":
            case "REJECTED":
            case "CANCELLED":
            case "EXPIRED":
                return true;
            default:
                return false;
        }
    }

    /** Whether any quantity actually executed. */
    public static boolean isFilled(String status) {
        String s = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        return "TRADED".equals(s) || "PART_TRADED".equals(s);
    }

    /** Whether the order will never produce a fill. */
    public static boolean isDead(String status) {
        String s = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        return "REJECTED".equals(s) || "CANCELLED".equals(s) || "EXPIRED".equals(s);
    }

    /**
     * Our own {@code trade_status} for a broker status and a fill count.
     *
     * <p>Section 8: a trade is not closed until the exit order is confirmed executed, and a
     * partial fill keeps executed and pending quantities separate. So the stored status is derived
     * from both the broker's word and the quantities, not from the broker's word alone - TRADED
     * with a filled quantity short of the order is still a partial as far as the position is
     * concerned.
     */
    public static String tradeStatus(String brokerStatus, int orderQty, int filledQty) {
        String s = brokerStatus == null ? "" : brokerStatus.trim().toUpperCase(Locale.ROOT);
        if (isDead(s)) return s;                          // REJECTED / CANCELLED / EXPIRED
        if (filledQty <= 0) return "PLACED";              // TRANSIT / PENDING, nothing done yet
        if (orderQty > 0 && filledQty < orderQty) return "PARTIAL";
        return "FILLED";
    }

    /** Max length Dhan accepts for a correlation id. */
    public static final int CORRELATION_ID_MAX = 30;

    /**
     * A Dhan-safe correlation id derived from our own idempotency key.
     *
     * <p><b>Why this is not just the idempotency key.</b> Ours is
     * {@code userId:strategy:symbol:side:date:signal} - readable, and both far longer than 30
     * characters and full of colons, which Dhan's allowed set
     * ({@code a-z A-Z 0-9}, space, underscore, hyphen) excludes. Sending it would have the order
     * rejected on a field that has nothing to do with the trade.
     *
     * <p><b>Why it matters that it is deterministic.</b> The correlation id is the only way to
     * answer "did that order actually reach the broker" after a placement response is lost to a
     * timeout - {@code GET /orders/external/{correlation-id}} finds it. That recovery only works
     * if the same logical order always produces the same id, so it is a hash of the key rather
     * than anything involving a clock or a random value.
     *
     * <p>Shape: {@code TR-<user>-<yyMMdd>-<hash>}, at most 30 characters. The hash is 64-bit FNV-1a
     * in base 36. Not cryptographic, and it does not need to be: it distinguishes one of a
     * handful of orders a day for one user, and a collision would have to occur inside the same
     * user and date prefix.
     */
    public static String correlationId(long userId, String yyyyMmDd, String idempotencyKey) {
        String date = yyyyMmDd == null ? "" : yyyyMmDd.replace("-", "");
        if (date.length() == 8) date = date.substring(2);          // yyyyMMdd -> yyMMdd
        String prefix = "TR-" + userId + "-" + date + "-";
        int room = CORRELATION_ID_MAX - prefix.length();
        String hash = base36(fnv1a(idempotencyKey == null ? "" : idempotencyKey));
        if (room < 4) {
            // A pathologically long user id leaves no room for the prefix. Fall back to the hash
            // alone, which is still deterministic and still unique per logical order - losing the
            // human-readable part is much better than sending a truncated prefix that collides.
            return sanitize(hash).substring(0, Math.min(CORRELATION_ID_MAX, hash.length()));
        }
        if (hash.length() > room) hash = hash.substring(0, room);
        return sanitize(prefix + hash);
    }

    /** Belt and braces: nothing outside Dhan's allowed set can leave this class. */
    private static String sanitize(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c) && c < 128) out.append(c);
            else if (c == '-' || c == '_') out.append(c);
            else out.append('-');
        }
        return out.length() > CORRELATION_ID_MAX
            ? out.substring(0, CORRELATION_ID_MAX) : out.toString();
    }

    private static long fnv1a(String s) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static String base36(long v) {
        // Unsigned, so a negative hash does not produce a leading '-' that eats a character and
        // halves the usable space.
        return Long.toUnsignedString(v, 36).toUpperCase(Locale.ROOT);
    }

    /**
     * Our idempotency key: the natural identity of one logical order.
     *
     * <p>Stored with a unique index, so a duplicate submission is refused by the database rather
     * than by a check in Java - two concurrent submissions both pass a check and both insert.
     */
    public static String idempotencyKey(long userId, String strategyKey, String symbol,
                                        String side, String tradeDate, long signalId) {
        return userId + ":" + n(strategyKey) + ":" + n(symbol).toUpperCase(Locale.ROOT) + ":"
             + n(side).toUpperCase(Locale.ROOT) + ":" + n(tradeDate) + ":" + signalId;
    }

    private static String n(String s) { return s == null ? "" : s.trim(); }
}
