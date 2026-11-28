package com.traderakshak.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The demo account against the real database.
 *
 * <p>Paper trading previously had no balance - only a deployment cap that nothing debited and
 * nothing persisted. The properties that matter are therefore the ones a cap does not have: the
 * balance moves when a trade happens, it survives a restart, it cannot go negative, and the
 * ledger explains it.
 *
 * <p>Runs against the local database with a user id no real account uses, and removes its own
 * rows afterwards, so it neither depends on nor disturbs existing data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.datasource.password=123456",
    "sync.enabled=false",
    "server.ssl.enabled=false"
})
class DemoAccountServiceIT {

    private static final long TEST_USER = 999_001L;
    private static final long TEST_INST = 999L;

    @Autowired DemoAccountService demo;
    @PersistenceContext EntityManager em;

    @Transactional
    void clean() {
        em.createNativeQuery("DELETE FROM demo_account_ledger WHERE user_id = ?1")
            .setParameter(1, TEST_USER).executeUpdate();
        em.createNativeQuery("DELETE FROM demo_account WHERE user_id = ?1")
            .setParameter(1, TEST_USER).executeUpdate();
    }

    @Test
    @Transactional
    void opensAtTheConfiguredBalanceAndStaysThere() {
        clean();
        double opening = demo.openingBalance();
        assertEquals(opening, demo.balance(TEST_USER, TEST_INST), 0.01,
            "a new account opens at the configured balance");

        // Asking again must not re-seed it. This is the behaviour that was missing: a balance
        // re-applied on every read is a constant, not an account.
        assertEquals(opening, demo.balance(TEST_USER, TEST_INST), 0.01);
        clean();
    }

    @Test
    @Transactional
    void aBuyDebitsTheConsiderationAndItsCharges() {
        clean();
        double opening = demo.balance(TEST_USER, TEST_INST);

        double after = demo.recordBuy(TEST_USER, TEST_INST, "TESTSYM", 10, 100.0, 25.0, "T-1");

        assertEquals(opening - 1025.0, after, 0.01, "10 x 100 plus 25 of charges");
        assertEquals(after, demo.balance(TEST_USER, TEST_INST), 0.01, "and it persists");
        clean();
    }

    @Test
    @Transactional
    void aSellCreditsTheProceedsNetOfCharges() {
        clean();
        demo.recordBuy(TEST_USER, TEST_INST, "TESTSYM", 10, 100.0, 25.0, "T-1");
        double afterBuy = demo.balance(TEST_USER, TEST_INST);

        double afterSell = demo.recordSell(TEST_USER, TEST_INST, "TESTSYM", 10, 110.0, 25.0, "T-1");

        assertEquals(afterBuy + 1075.0, afterSell, 0.01, "10 x 110 less 25 of charges");
        clean();
    }

    @Test
    @Transactional
    void theAccountCannotBeOverdrawn() {
        clean();
        double balance = demo.balance(TEST_USER, TEST_INST);

        // Running out of money is the outcome paper trading exists to reveal, so it must be an
        // error rather than a negative balance nobody notices.
        assertThrows(IllegalStateException.class,
            () -> demo.post(TEST_USER, TEST_INST, "BUY", "TESTSYM", 1,
                            balance + 1000, -(balance + 1000), "T-X", "overdraw"),
            "a debit beyond the balance must be refused");

        assertEquals(balance, demo.balance(TEST_USER, TEST_INST), 0.01,
            "and the balance is untouched by the refusal");
        clean();
    }

    @Test
    @Transactional
    void theLedgerExplainsTheBalance() {
        clean();
        demo.recordBuy(TEST_USER, TEST_INST, "TESTSYM", 5, 200.0, 10.0, "T-2");
        demo.recordSell(TEST_USER, TEST_INST, "TESTSYM", 5, 220.0, 10.0, "T-2");

        List<Map<String, Object>> rows = demo.ledger(TEST_USER, TEST_INST, 10);
        assertEquals(2, rows.size());
        assertEquals("SELL", rows.get(0).get("type"), "newest first");
        assertEquals("BUY", rows.get(1).get("type"));

        Map<String, Object> check = demo.recomputeBalance(TEST_USER, TEST_INST);
        assertTrue((Boolean) check.get("in_agreement"),
            "the stored balance must equal the ledger total: " + check);
        clean();
    }

    @Test
    @Transactional
    void theSummaryReportsNoMarginFacility() {
        clean();
        Map<String, Object> s = demo.summary(TEST_USER, TEST_INST);
        assertEquals("DEMO", s.get("account_type"));
        assertEquals(Boolean.FALSE, s.get("is_real"), "a demo account must never read as real");
        assertEquals(0.0, (Double) s.get("used_margin"), 0.01);
        assertEquals(s.get("available_balance"), s.get("buying_power"),
            "a demo account's buying power is its cash and nothing more");
        clean();
    }
}
