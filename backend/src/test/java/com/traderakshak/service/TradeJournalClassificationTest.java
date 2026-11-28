package com.traderakshak.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.traderakshak.service.TradeJournalService.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The Trade Journal classification matrix: Trade Type against Account Type.
 *
 * <p>The specification asks for four combinations - System/Manual against Real/Paper - plus a
 * third origin for a trade placed directly at the broker. The classification is DERIVED from
 * columns that already exist rather than stored a second time, so what needs guarding is the
 * derivation itself.
 *
 * <p>Two cases here are real defects that were found in the running system rather than invented
 * for a test: a broker import being indistinguishable from a hand-typed entry, and an unknown
 * account being labelled "paper money".
 */
class TradeJournalClassificationTest {

    // ───────────────────────────────────────────────────────── trade type

    @Test
    @DisplayName("automation's own trades are System trades")
    void systemTrade() {
        assertEquals("SYSTEM", originOf(false, "volbreakout"));
        assertEquals("System Trade", originLabel(originOf(false, "volbreakout")));
    }

    @Test
    @DisplayName("a trade a person placed here is a Manual trade")
    void manualTrade() {
        assertEquals("MANUAL", originOf(true, "manual"));
        assertEquals("Manual Trade", originLabel(originOf(true, "manual")));
    }

    @Test
    @DisplayName("a broker import is External, not Manual - it is written with is_manual=1")
    void externalIsNotManual() {
        // BrokerTradeImportService writes is_manual=1 and strategy_key='broker_import'. Under a
        // two-way test that row is a Manual trade, which is exactly wrong: the user never placed
        // it through this application. The strategy key is what separates them.
        assertEquals("EXTERNAL", originOf(true, "broker_import"));
        assertEquals("External Broker Trade", originLabel(originOf(true, "broker_import")));
        assertNotEquals(originOf(true, "manual"), originOf(true, "broker_import"));
    }

    @Test
    @DisplayName("the broker-import key is matched whatever its casing or padding")
    void externalKeyIsRobust() {
        assertEquals("EXTERNAL", originOf(true, "BROKER_IMPORT"));
        assertEquals("EXTERNAL", originOf(true, "  broker_import  "));
        assertEquals("EXTERNAL", originOf(false, "broker_import"));
    }

    // ───────────────────────────────────────────────────────── account type

    @Test
    @DisplayName("every spelling the codebase writes maps to the right account")
    void accountSpellings() {
        // The codebase writes all of these: REAL and LIVE for real money, PAPER, DEMO and the
        // simulator's own 'virtual' for simulated. A literal comparison against one spelling
        // silently drops the others.
        assertEquals("REAL",  accountOf("REAL"));
        assertEquals("REAL",  accountOf("LIVE"));
        assertEquals("REAL",  accountOf("real"));
        assertEquals("PAPER", accountOf("PAPER"));
        assertEquals("PAPER", accountOf("DEMO"));
        assertEquals("PAPER", accountOf("virtual"));
    }

    @Test
    @DisplayName("an unrecorded account is Unclassified, never assumed to be paper money")
    void unknownAccountIsNotPaper() {
        // This is the defect, not a nicety. Defaulting an unknown account to PAPER files a trade
        // whose account nobody knows under the harmless-sounding half of a distinction the
        // specification requires be exact - and the old filter did exactly that with
        // COALESCE(trading_mode,'PAPER').
        assertEquals("UNKNOWN", accountOf(null));
        assertEquals("UNKNOWN", accountOf(""));
        assertEquals("UNKNOWN", accountOf("   "));
        assertNotEquals("PAPER", accountOf(null));
        assertEquals("Unclassified", accountLabel(accountOf(null)));
    }

    // ───────────────────────────────────────────────────────── the matrix

    @Test
    @DisplayName("all four required combinations are expressible and distinct")
    void fourCombinations() {
        String[][] cases = {
            {"SYSTEM", "REAL"}, {"SYSTEM", "PAPER"},
            {"MANUAL", "REAL"}, {"MANUAL", "PAPER"}
        };
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String[] c : cases) {
            String label = originLabel(c[0]) + " / " + accountLabel(c[1]);
            assertTrue(seen.add(label), "each combination must be a distinct label: " + label);
        }
        assertEquals(4, seen.size());
        assertTrue(seen.contains("System Trade / Real Trade"));
        assertTrue(seen.contains("System Trade / Paper Trade"));
        assertTrue(seen.contains("Manual Trade / Real Trade"));
        assertTrue(seen.contains("Manual Trade / Paper Trade"));
    }

    @Test
    @DisplayName("an external broker trade keeps the Real classification")
    void externalStaysReal() {
        // Section 6: "For External Broker Trade, retain the Real Trade classification and
        // identify its source as External Broker."
        assertEquals("External Broker Trade / Real Trade",
            originLabel(originOf(true, "broker_import")) + " / " + accountLabel(accountOf("REAL")));
    }

    // ───────────────────────────────────────────────────── execution status

    @Test
    @DisplayName("every stored status maps into the required execution vocabulary")
    void storedStatusesMap() {
        assertEquals("ORDER_SUBMITTED",  executionStatusOf("PLACED"));
        assertEquals("PARTIALLY_FILLED", executionStatusOf("PARTIAL"));
        assertEquals("EXECUTED",         executionStatusOf("FILLED"));
        assertEquals("EXECUTED",         executionStatusOf("CLOSED"));
        assertEquals("REJECTED",         executionStatusOf("REJECTED"));
        assertEquals("CANCELLED",        executionStatusOf("CANCELLED"));
        assertEquals("FAILED",           executionStatusOf("EXPIRED"));
    }

    @Test
    @DisplayName("an open position reads as Executed, not as still pending")
    void openPositionHasFilled() {
        // An open position is one whose ENTRY filled. Reporting it as submitted-but-not-executed
        // would say the money is uncommitted while it is actually in a stock.
        assertEquals("EXECUTED", executionStatusOf("OPEN"));
        assertEquals("Executed", executionStatusLabel(executionStatusOf("OPEN")));
    }

    @Test
    @DisplayName("all eight required statuses have a label")
    void allEightLabelled() {
        String[] required = {"SCHEDULED", "SIGNAL_CONFIRMED", "ORDER_SUBMITTED",
                             "PARTIALLY_FILLED", "EXECUTED", "REJECTED", "CANCELLED", "FAILED"};
        for (String st : required) {
            String label = executionStatusLabel(st);
            assertNotEquals("Unknown", label, st + " must have a label");
            assertFalse(label.isBlank());
        }
        assertEquals(8, required.length);
    }

    @Test
    @DisplayName("an unrecognised status is empty rather than guessed into a real state")
    void unknownStatusIsNotGuessed() {
        // Mapping something unrecognised onto EXECUTED would report a fill that may not exist.
        assertEquals("", executionStatusOf("SOMETHING_ELSE"));
        assertEquals("", executionStatusOf(null));
        assertEquals("", executionStatusOf(""));
    }

    @Test
    @DisplayName("a simulated trade can never be reported as real money")
    void paperIsNeverReal() {
        for (String mode : new String[] {"PAPER", "DEMO", "virtual"}) {
            assertNotEquals("REAL", accountOf(mode),
                "a simulated trade must never classify as a real one: " + mode);
        }
    }
}
