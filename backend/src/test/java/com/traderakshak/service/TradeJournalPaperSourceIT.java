package com.traderakshak.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simulated trades must appear in the Trade Journal, classified by who placed them.
 *
 * <p>The specification's first sentence about the journal is that every trade the system generates
 * appears in it. That was not true: the automation engine and the manual paper path both write
 * {@code paper_trades}, nothing copied those rows into {@code trading_journal}, and the journal
 * service read only the two journal tables - so a simulated trade could exist and never appear.
 *
 * <p><b>Why this uses its own fixtures.</b> An earlier version of this test asserted against the
 * live row {@code VB-1-20261125-JLHL} and passed - but that row carries
 * {@code paper_trades.source = 'AUTO'} while its linked signal says
 * {@code trigger_rule = 'MANUAL'}. It was placed by hand and recorded as automation's work, by the
 * very bug this work fixes. The test was therefore passing BECAUSE of a defect, which is worse
 * than not testing at all. It now writes one genuinely automated and one genuinely manual row
 * under a user id no real account uses, and removes them afterwards, so what it proves does not
 * depend on anybody's data being correct.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.datasource.password=123456",
    "sync.enabled=false",
    "server.ssl.enabled=false"
})
class TradeJournalPaperSourceIT {

    private static final long TEST_USER = 999_002L;
    private static final long TEST_INST = 999L;

    @Autowired TradeJournalService journal;
    @Autowired TransactionTemplate tx;
    @PersistenceContext EntityManager em;

    // Run through a TransactionTemplate rather than @Transactional: these are called from
    // @BeforeEach on this same instance, so a self-invocation would bypass the proxy and the
    // DML would fail with "Executing an update/delete query" outside a transaction.
    void insertFixtures() {
        tx.executeWithoutResult(s -> {
        // One placed by the scheduled engine, one by a person through the application. Same
        // account, same stock price, different origin - so the only thing the classification can
        // be reading is the origin itself.
        em.createNativeQuery(
            "INSERT INTO paper_trades(trade_id,user_id,institution_id,trade_type,exchange,symbol,"
          + "sector,segment,qty,entry_price,entry_date,status,source,strategy_key,trade_mode,"
          + "total_charges,automation_config) VALUES "
          + "('IT-AUTO-1',?,?,'PAPER','NSE','ITAUTO','Testing','EQ-SWG',10,100.0,'2026-11-25',"
          + "  'open','AUTO','volbreakout','virtual',12.5,'{\"buyTime\":\"15:25\"}'),"
          + "('IT-MAN-1',?,?,'PAPER','NSE','ITMAN','Testing','EQ-SWG',10,100.0,'2026-11-25',"
          + "  'open','MANUAL','volbreakout','virtual',12.5,NULL)")
            .setParameter(1, TEST_USER).setParameter(2, TEST_INST)
            .setParameter(3, TEST_USER).setParameter(4, TEST_INST)
            .executeUpdate();
        });
    }

    void removeFixtures() {
        tx.executeWithoutResult(s -> em.createNativeQuery(
            "DELETE FROM paper_trades WHERE user_id = ?")
            .setParameter(1, TEST_USER).executeUpdate());
    }

    @BeforeEach void setUp()    { removeFixtures(); insertFixtures(); }
    @AfterEach  void tearDown() { removeFixtures(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(String source, String mode) {
        Map<String, Object> out =
            journal.list(TEST_USER, null, null, null, null, mode, source, null, 500);
        assertEquals(Boolean.TRUE, out.get("success"),
            "the journal must read successfully: " + out.get("message"));
        return (List<Map<String, Object>>) out.getOrDefault("rows", List.of());
    }

    private Map<String, Object> bySymbol(List<Map<String, Object>> rows, String symbol) {
        return rows.stream().filter(r -> symbol.equals(r.get("symbol"))).findFirst()
            .orElseThrow(() -> new AssertionError(symbol + " is missing from the journal"));
    }

    @Test
    @DisplayName("a simulated trade reaches the journal at all")
    void simulatedTradesAppear() {
        List<Map<String, Object>> all = rows(null, null);
        assertEquals(2, all.size(), "both simulated trades must appear");

        for (Map<String, Object> r : all) {
            assertEquals(Boolean.TRUE, r.get("simulated"));
            assertEquals("PAPER", r.get("accountType"),
                "a row read from paper_trades is simulated money by definition");
            assertEquals("paper_trades", r.get("source"));
            assertEquals("", r.get("brokerOrderId"),
                "a simulated fill has no broker order id, and that is correct, not missing");
            assertEquals(10, r.get("quantity"));
        }
    }

    @Test
    @DisplayName("an automated trade is System Trade / Paper Trade")
    void automatedIsSystem() {
        Map<String, Object> r = bySymbol(rows(null, null), "ITAUTO");
        assertEquals("SYSTEM", r.get("tradeOrigin"));
        assertEquals("System Trade", r.get("tradeTypeLabel"));
        assertEquals("Paper Trade", r.get("accountTypeLabel"));
        assertEquals("System Trade / Paper Trade", r.get("classification"));
    }

    @Test
    @DisplayName("a hand-placed trade is Manual Trade / Paper Trade, never System")
    void manualIsNotSystem() {
        // The defect in one assertion: this row was placed by a person, and before the fix it
        // would have carried source='AUTO' and read as the platform's own trade.
        Map<String, Object> r = bySymbol(rows(null, null), "ITMAN");
        assertEquals("MANUAL", r.get("tradeOrigin"));
        assertEquals("Manual Trade / Paper Trade", r.get("classification"));
        assertNotEquals("SYSTEM", r.get("tradeOrigin"),
            "a manually placed order must not be classified as a System Trade");
    }

    @Test
    @DisplayName("a system trade carries the automation settings it ran under")
    void systemTradeCarriesItsConfig() {
        assertNotNull(bySymbol(rows(null, null), "ITAUTO").get("automationConfig"),
            "section 5B requires a System Trade to record its automation configuration");
        assertNull(bySymbol(rows(null, null), "ITMAN").get("automationConfig"),
            "a manual trade has no automation configuration, and null says so");
    }

    @Test
    @DisplayName("the origin filters separate the two and overlap on neither")
    void originFiltersSeparate() {
        List<Map<String, Object>> system = rows("SYSTEM", null);
        List<Map<String, Object>> manual = rows("MANUAL", null);

        assertEquals(1, system.size());
        assertEquals("ITAUTO", system.get(0).get("symbol"));
        assertEquals(1, manual.size());
        assertEquals("ITMAN", manual.get(0).get("symbol"));
    }

    @Test
    @DisplayName("the External filter returns no simulated trade")
    void externalExcludesSimulated() {
        for (Map<String, Object> r : rows("EXTERNAL", null)) {
            assertNotEquals(Boolean.TRUE, r.get("simulated"));
        }
        assertTrue(rows("EXTERNAL", null).isEmpty(),
            "these fixtures are simulated, so none of them is an external broker trade");
    }

    @Test
    @DisplayName("the Real filter never returns simulated money")
    void realFilterExcludesSimulated() {
        assertTrue(rows(null, "REAL").isEmpty(),
            "a Real-account view must not contain a simulated trade");
    }

    @Test
    @DisplayName("the Paper filter returns both of them")
    void paperFilterReturnsBoth() {
        List<Map<String, Object>> paper = rows(null, "PAPER");
        assertEquals(2, paper.size());
        for (Map<String, Object> r : paper) assertEquals("PAPER", r.get("accountType"));
    }

    @Test
    @DisplayName("an open simulated position reads as Executed, not pending")
    void openReadsAsExecuted() {
        Map<String, Object> r = bySymbol(rows(null, null), "ITAUTO");
        assertEquals("OPEN", r.get("tradeStatus"));
        assertEquals("EXECUTED", r.get("executionStatus"));
        assertEquals("Executed", r.get("executionStatusLabel"));
    }

    @Test
    @DisplayName("the classification matrix counts exactly the rows it describes")
    void matrixAgreesWithRows() {
        Map<String, Object> out =
            journal.list(TEST_USER, null, null, null, null, null, null, null, 500);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
            (List<Map<String, Object>>) out.getOrDefault("rows", List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> matrix =
            (Map<String, Object>) out.getOrDefault("classificationMatrix", Map.of());

        int counted = 0;
        for (Object cell : matrix.values()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> c = (Map<String, Object>) cell;
            counted += ((Number) c.get("total")).intValue();
        }
        assertEquals(rows.size(), counted,
            "a summary counting a different number of trades from the list under it is worse "
          + "than no summary");
        assertEquals(2, counted);
    }

    @Test
    @DisplayName("real and paper money are never added into one total")
    void accountsTotalledSeparately() {
        Map<String, Object> out =
            journal.list(TEST_USER, null, null, null, null, null, null, null, 500);
        @SuppressWarnings("unchecked")
        Map<String, Object> byAccount =
            (Map<String, Object>) out.getOrDefault("analyticsByAccount", Map.of());

        assertFalse(byAccount.isEmpty(), "the per-account split must always be available");
        // These fixtures are all simulated, so there is exactly one account present and the
        // headline total is not a mixture.
        assertEquals(1, byAccount.size());
        assertTrue(byAccount.containsKey("PAPER"));
        assertEquals(Boolean.FALSE, out.get("mixesAccounts"));
    }
}
