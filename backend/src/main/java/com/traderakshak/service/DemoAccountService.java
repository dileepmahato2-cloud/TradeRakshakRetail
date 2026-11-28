package com.traderakshak.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Demo (paper) account: a virtual balance that behaves like money.
 *
 * <p>Paper trading had no balance at all. The nearest thing was a config key,
 * {@code volbreakout.available.funds}, whose own description concedes it is a cap on deployment
 * rather than a balance - nothing was debited when a paper position opened, nothing credited when
 * it closed, and nothing survived a restart. A paper strategy could therefore spend the same
 * rupee every session and never run out, which makes its results incomparable with a real
 * account's and hides the one failure mode paper trading exists to reveal: running out of money.
 *
 * <p>Every movement is a ledger row and the balance is the running total of those rows. The
 * balance column is a cached sum, not a separate truth: {@link #recomputeBalance} rebuilds it
 * from the ledger, so the two can be compared rather than trusted.
 *
 * <p>Opening balance is seeded once, on first use. It is explicitly NOT re-applied on later
 * reads - a demo account that has traded down to 4 lakh stays at 4 lakh across restarts, which
 * is the whole point of persisting it.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DemoAccountService {

    @PersistenceContext private EntityManager em;
    private final AppConfigService appConfig;

    /** What a brand-new demo account opens with. Settable; never re-applied to an existing one. */
    public double openingBalance() {
        return appConfig.getDouble("demo.account.opening_balance", 1_000_000.0);
    }

    /**
     * The account's balance, opening it at the configured amount the first time it is asked for.
     *
     * <p>The insert is conditional on the row not existing, so two concurrent callers cannot both
     * seed it and double the opening balance.
     */
    @Transactional
    public double balance(Long userId, Long institutionId) {
        long u = userId == null ? 1L : userId;
        long i = institutionId == null ? 1L : institutionId;

        em.createNativeQuery(
            "INSERT INTO demo_account (user_id, institution_id, balance, opening_balance) "
          + "SELECT ?, ?, ?, ? WHERE NOT EXISTS ("
          + "  SELECT 1 FROM demo_account WHERE user_id = ? AND institution_id = ?)")
            .setParameter(1, u).setParameter(2, i)
            .setParameter(3, openingBalance()).setParameter(4, openingBalance())
            .setParameter(5, u).setParameter(6, i)
            .executeUpdate();

        Object v = em.createNativeQuery(
            "SELECT balance FROM demo_account WHERE user_id = ? AND institution_id = ?")
            .setParameter(1, u).setParameter(2, i).getSingleResult();
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    /**
     * Move money and record why, returning the balance afterwards.
     *
     * @param amount signed: negative debits the account, positive credits it. One signed column
     *               rather than an amount plus a direction, so the balance is the sum of that
     *               column and a mis-set direction flag cannot exist.
     * @throws IllegalStateException when a debit would overdraw the account. A demo account that
     *         can go negative is not simulating anything - running out of money is the outcome
     *         paper trading is for.
     */
    @Transactional
    public double post(Long userId, Long institutionId, String entryType, String symbol,
                       Integer quantity, Double price, double amount, String reference,
                       String note) {
        long u = userId == null ? 1L : userId;
        long i = institutionId == null ? 1L : institutionId;

        double current = balance(u, i);
        double after = round2(current + amount);
        if (after < 0) {
            throw new IllegalStateException(String.format(
                "Demo account has %.2f and this would take it to %.2f.", current, after));
        }

        em.createNativeQuery(
            "UPDATE demo_account SET balance = ?, updated_at = now() "
          + "WHERE user_id = ? AND institution_id = ?")
            .setParameter(1, after).setParameter(2, u).setParameter(3, i)
            .executeUpdate();

        em.createNativeQuery(
            "INSERT INTO demo_account_ledger (user_id, institution_id, entry_type, symbol, "
          + "quantity, price, amount, balance_after, reference, note) "
          + "VALUES (?,?,?,?,?,?,?,?,?,?)")
            .setParameter(1, u).setParameter(2, i)
            .setParameter(3, entryType == null ? "ADJUSTMENT" : entryType)
            .setParameter(4, symbol)
            .setParameter(5, quantity)
            .setParameter(6, price)
            .setParameter(7, round2(amount))
            .setParameter(8, after)
            .setParameter(9, reference)
            .setParameter(10, note)
            .executeUpdate();

        return after;
    }

    /** A paper purchase: the consideration and its charges leave the account. */
    @Transactional
    public double recordBuy(Long userId, Long institutionId, String symbol, int qty,
                            double price, double charges, String reference) {
        double consideration = round2(qty * price);
        double total = round2(consideration + Math.max(0, charges));
        return post(userId, institutionId, "BUY", symbol, qty, price, -total, reference,
            String.format("Bought %d at %.2f, charges %.2f", qty, price, Math.max(0, charges)));
    }

    /** A paper sale: proceeds arrive net of charges. */
    @Transactional
    public double recordSell(Long userId, Long institutionId, String symbol, int qty,
                             double price, double charges, String reference) {
        double consideration = round2(qty * price);
        double net = round2(consideration - Math.max(0, charges));
        return post(userId, institutionId, "SELL", symbol, qty, price, net, reference,
            String.format("Sold %d at %.2f, charges %.2f", qty, price, Math.max(0, charges)));
    }

    /** The ledger, newest first. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> ledger(Long userId, Long institutionId, int limit) {
        long u = userId == null ? 1L : userId;
        long i = institutionId == null ? 1L : institutionId;
        List<Object[]> rows = em.createNativeQuery(
            "SELECT to_char(entry_at,'YYYY-MM-DD HH24:MI:SS'), entry_type, symbol, quantity, "
          + "price, amount, balance_after, reference, note "
          + "FROM demo_account_ledger WHERE user_id = ? AND institution_id = ? "
          + "ORDER BY entry_at DESC, id DESC LIMIT ?")
            .setParameter(1, u).setParameter(2, i)
            .setParameter(3, Math.max(1, Math.min(limit, 500)))
            .getResultList();

        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", r[0]);
            m.put("type", r[1]);
            m.put("symbol", r[2]);
            m.put("quantity", r[3]);
            m.put("price", r[4]);
            m.put("amount", r[5]);
            m.put("balance_after", r[6]);
            m.put("reference", r[7]);
            m.put("note", r[8]);
            out.add(m);
        }
        return out;
    }

    /**
     * Rebuild the balance from the ledger and report whether it had drifted.
     *
     * <p>The balance column is a cached sum. Anything that can drift should be checkable, so this
     * states the stored figure, the computed one and the difference rather than silently
     * correcting and leaving no trace that it was ever wrong.
     */
    @Transactional
    public Map<String, Object> recomputeBalance(Long userId, Long institutionId) {
        long u = userId == null ? 1L : userId;
        long i = institutionId == null ? 1L : institutionId;
        double stored = balance(u, i);

        Object sum = em.createNativeQuery(
            "SELECT COALESCE(SUM(amount), 0) FROM demo_account_ledger "
          + "WHERE user_id = ? AND institution_id = ?")
            .setParameter(1, u).setParameter(2, i).getSingleResult();
        double moved = sum instanceof Number n ? n.doubleValue() : 0.0;
        double computed = round2(openingBalanceOf(u, i) + moved);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stored_balance", stored);
        out.put("computed_balance", computed);
        out.put("difference", round2(computed - stored));
        out.put("in_agreement", Math.abs(computed - stored) < 0.01);
        if (Math.abs(computed - stored) >= 0.01) {
            log.warn("[DemoAccount] Balance {} disagrees with the ledger total {}.", stored, computed);
        }
        return out;
    }

    /** This account's own opening balance, which may predate a change to the setting. */
    private double openingBalanceOf(long userId, long institutionId) {
        try {
            Object v = em.createNativeQuery(
                "SELECT opening_balance FROM demo_account WHERE user_id = ? AND institution_id = ?")
                .setParameter(1, userId).setParameter(2, institutionId).getSingleResult();
            return v instanceof Number n ? n.doubleValue() : openingBalance();
        } catch (Exception e) {
            return openingBalance();
        }
    }

    /** The account as the Filters panel shows it. */
    public Map<String, Object> summary(Long userId, Long institutionId) {
        long u = userId == null ? 1L : userId;
        long i = institutionId == null ? 1L : institutionId;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("account_type", "DEMO");
        out.put("label", "Demo Account (virtual)");
        out.put("currency", "INR");
        out.put("available_balance", balance(u, i));
        out.put("opening_balance", openingBalanceOf(u, i));
        // A demo account has no margin facility: its buying power is its cash and nothing more.
        out.put("used_margin", 0.0);
        out.put("buying_power", balance(u, i));
        out.put("is_real", false);
        return out;
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
