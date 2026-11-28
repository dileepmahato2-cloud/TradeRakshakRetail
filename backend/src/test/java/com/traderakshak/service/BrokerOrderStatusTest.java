package com.traderakshak.service;

import org.junit.jupiter.api.Test;

import static com.traderakshak.service.BrokerOrderStatus.CORRELATION_ID_MAX;
import static com.traderakshak.service.BrokerOrderStatus.correlationId;
import static com.traderakshak.service.BrokerOrderStatus.idempotencyKey;
import static com.traderakshak.service.BrokerOrderStatus.isDead;
import static com.traderakshak.service.BrokerOrderStatus.isFilled;
import static com.traderakshak.service.BrokerOrderStatus.isTerminal;
import static com.traderakshak.service.BrokerOrderStatus.tradeStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Order status classification and the correlation id.
 *
 * <p>Both are pinned because both fail quietly. A status wrongly called terminal stops an order
 * being followed while it is still working at the exchange; a correlation id Dhan rejects means a
 * placement response lost to a timeout can never be resolved, and the user cannot find out whether
 * they own the stock.
 */
class BrokerOrderStatusTest {

    // ------------------------------------------------------- status classification

    @Test
    void terminalStatusesAreTheFourThatEndAnOrder() {
        assertTrue(isTerminal("TRADED"));
        assertTrue(isTerminal("REJECTED"));
        assertTrue(isTerminal("CANCELLED"));
        assertTrue(isTerminal("EXPIRED"));
    }

    @Test
    void partTradedIsNotTerminal() {
        // The one most easily mistaken for terminal: some quantity filled, so it looks dealt with,
        // but the remainder is still working and will become TRADED, CANCELLED or EXPIRED. Calling
        // it terminal freezes pending_qty at a stale number.
        assertFalse(isTerminal("PART_TRADED"),
            "the unfilled remainder is still live at the exchange");
        assertTrue(isFilled("PART_TRADED"), "but some quantity did execute");
    }

    @Test
    void transitAndPendingAreNotTerminal() {
        assertFalse(isTerminal("TRANSIT"));
        assertFalse(isTerminal("PENDING"));
        assertFalse(isFilled("PENDING"));
    }

    @Test
    void anUnknownStatusIsTreatedAsStillLive() {
        // Dhan may add one. Polling an order that has in fact finished costs a row in a request
        // being made anyway; abandoning one that has not is a position silently dropped.
        assertFalse(isTerminal("SOMETHING_NEW"));
        assertFalse(isTerminal(null));
        assertFalse(isTerminal(""));
    }

    @Test
    void caseAndWhitespaceDoNotChangeTheAnswer() {
        assertTrue(isTerminal(" traded "));
        assertTrue(isDead("rejected"));
    }

    @Test
    void deadOrdersWillNeverFill() {
        assertTrue(isDead("REJECTED"));
        assertTrue(isDead("CANCELLED"));
        assertTrue(isDead("EXPIRED"));
        assertFalse(isDead("TRADED"), "traded is finished, but it is not dead - it filled");
        assertFalse(isDead("PART_TRADED"));
    }

    // ------------------------------------------------------- our stored trade_status

    @Test
    void storedStatusComesFromTheQuantitiesNotJustTheBrokersWord() {
        // TRADED with a filled quantity short of the order is still a partial as far as the
        // position is concerned, and section 8 requires executed and pending to stay separate.
        assertEquals("PARTIAL", tradeStatus("TRADED", 100, 60));
        assertEquals("FILLED", tradeStatus("TRADED", 100, 100));
    }

    @Test
    void nothingFilledYetIsStillJustPlaced() {
        assertEquals("PLACED", tradeStatus("PENDING", 100, 0));
        assertEquals("PLACED", tradeStatus("TRANSIT", 100, 0));
    }

    @Test
    void partialFillIsReportedAsPartial() {
        assertEquals("PARTIAL", tradeStatus("PART_TRADED", 100, 40));
    }

    @Test
    void aDeadOrderKeepsTheBrokersOwnReason() {
        // REJECTED must not be flattened into a generic failure - the reason is what tells the
        // user whether to retry, fix a mapping, or add funds.
        assertEquals("REJECTED", tradeStatus("REJECTED", 100, 0));
        assertEquals("CANCELLED", tradeStatus("CANCELLED", 100, 0));
        assertEquals("EXPIRED", tradeStatus("EXPIRED", 100, 0));
    }

    @Test
    void aRejectionAfterAPartialFillStillReportsTheRejection() {
        // Can happen on a sliced order. The filled quantity is real and is recorded elsewhere, but
        // the order's own outcome was a rejection and saying "PARTIAL" would hide that.
        assertEquals("REJECTED", tradeStatus("REJECTED", 100, 40));
    }

    // ------------------------------------------------------- correlation id

    @Test
    void correlationIdFitsDhansThirtyCharacterLimit() {
        String id = correlationId(1, "2026-11-24",
            idempotencyKey(1, "volbreakout", "HMAAGRO", "BUY", "2026-11-24", 42));
        assertTrue(id.length() <= CORRELATION_ID_MAX,
            "Dhan rejects anything longer, on a field unrelated to the trade itself: " + id);
    }

    @Test
    void correlationIdUsesOnlyCharactersDhanAllows() {
        // Allowed: letters, digits, space, underscore, hyphen. Our own idempotency key is full of
        // colons, so it cannot be sent as-is.
        String key = idempotencyKey(1, "volbreakout", "M&M", "BUY", "2026-11-24", 7);
        assertTrue(key.contains(":"), "the readable key does contain colons");
        String id = correlationId(1, "2026-11-24", key);
        assertTrue(id.matches("[A-Za-z0-9 _-]+"), "not Dhan-safe: " + id);
    }

    @Test
    void theSameLogicalOrderAlwaysProducesTheSameCorrelationId() {
        // This is the whole point. After a placement response is lost to a timeout,
        // GET /orders/external/{correlation-id} is the only way to find out whether the order
        // reached the broker - and that only works if the id is reproducible.
        String key = idempotencyKey(1, "volbreakout", "JLHL", "BUY", "2026-11-24", 3);
        assertEquals(correlationId(1, "2026-11-24", key), correlationId(1, "2026-11-24", key));
    }

    @Test
    void differentOrdersProduceDifferentCorrelationIds() {
        String buy  = correlationId(1, "2026-11-24",
            idempotencyKey(1, "volbreakout", "JLHL", "BUY", "2026-11-24", 3));
        String sell = correlationId(1, "2026-11-24",
            idempotencyKey(1, "volbreakout", "JLHL", "SELL", "2026-11-24", 3));
        String other = correlationId(1, "2026-11-24",
            idempotencyKey(1, "volbreakout", "OAL", "BUY", "2026-11-24", 3));

        assertNotEquals(buy, sell, "a buy and a sell of the same stock are different orders");
        assertNotEquals(buy, other);
    }

    @Test
    void aLongUserIdStillYieldsAValidId() {
        // Degrades to the hash alone rather than sending a truncated prefix that could collide.
        String id = correlationId(999999999999L, "2026-11-24", "some-key");
        assertTrue(id.length() <= CORRELATION_ID_MAX, id);
        assertTrue(id.matches("[A-Za-z0-9 _-]+"), id);
    }

    @Test
    void theHashNeverCarriesALeadingMinusSign() {
        // Long.toString on a negative hash would start with '-', spending a character and looking
        // like a malformed id. Unsigned base-36 avoids it. Checked over many keys because only
        // about half of all hashes are negative.
        for (int i = 0; i < 500; i++) {
            String id = correlationId(1, "2026-11-24", "key-" + i);
            assertFalse(id.endsWith("-"), "empty hash segment for key-" + i + ": " + id);
            assertTrue(id.startsWith("TR-1-261124-"), id);
            assertTrue(id.length() <= CORRELATION_ID_MAX, id);
        }
    }

    @Test
    void handlesMissingDateAndKeyWithoutThrowing() {
        assertTrue(correlationId(1, null, null).length() <= CORRELATION_ID_MAX);
        assertTrue(correlationId(1, "", "").matches("[A-Za-z0-9 _-]+"));
    }

    @Test
    void idempotencyKeyIsCaseStableOnSymbolAndSide() {
        assertEquals(idempotencyKey(1, "volbreakout", "jlhl", "buy", "2026-11-24", 3),
                     idempotencyKey(1, "volbreakout", "JLHL", "BUY", "2026-11-24", 3),
            "the same order typed in a different case must not place twice");
    }
}
