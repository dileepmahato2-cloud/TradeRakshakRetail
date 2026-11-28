package com.traderakshak.service;

import com.traderakshak.util.DateUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Symbols Dhan refuses to serve, and a reviewed way to take them out of the uploaded list.
 *
 * <p><b>Why.</b> A backfill run reported 167 symbols skipped with DH-905 - "Dhan will not serve
 * this instrument", which means delisted or a security id that no longer resolves. They are 13%
 * of a 1,314-symbol universe, they fail on every run, and each one still costs an API call and a
 * rate-limit slot. Nothing cleared them, so the cost repeated daily for data that will never
 * arrive.
 *
 * <p><b>Review, not cleanup.</b> Nothing is removed on a schedule or on a single click. The report
 * is read first, and the deactivate call requires the caller to name the symbols - passing a count
 * or a "do them all" flag is deliberately impossible. A symbol can be refused for reasons that get
 * fixed (a stale instrument master, a symbol that changed code on a corporate action), and the
 * cost of wrongly removing a live holding from someone's tracked list is far higher than one more
 * failed API call.
 *
 * <p><b>Deactivated, not deleted.</b> {@code is_active = 0} keeps the row, its base price and its
 * upload history, so the decision is reversible and nothing historical breaks. A hard delete would
 * orphan every past ranking and journal reference to the symbol.
 */
@Service
public class DeadSymbolService {

    private static final Logger log = LoggerFactory.getLogger(DeadSymbolService.class);

    @PersistenceContext
    private EntityManager em;

    private final TransactionTemplate tx;

    public DeadSymbolService(TransactionTemplate tx) {
        this.tx = tx;
    }

    /**
     * Symbols that have been refused on at least {@code minJobs} distinct backfill jobs.
     *
     * <p>Distinct JOBS, not occurrences. One job retries a symbol across several date ranges, so
     * counting occurrences would let a single bad run look like a pattern. Repetition across
     * separate jobs is the thing that distinguishes "delisted" from "Dhan had a bad afternoon".
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> report(Long userId, Long institutionId, int minJobs) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        int jobs = Math.max(1, minJobs);
        try {
            // DH-905 alone is NOT evidence that a symbol is dead, and an earlier version of this
            // query treated it as though it were.
            //
            // Measured on 2026-11-24 against the 167 symbols a job reported: all sampled ones are
            // NSE-listed and in Dhan's scrip master with a matching security id, and 89 already
            // hold stored daily candles. A report built on DH-905 would have offered 89 live,
            // listed stocks for removal.
            //
            // So a symbol only appears here when BOTH hold: it has been refused across several
            // separate jobs, AND it has never once returned a daily candle. A stock with history
            // has proved it is real, whatever Dhan says about it this week.
            List<Object[]> result = em.createNativeQuery(
                "SELECT UPPER(TRIM(e.symbol)) sym, COUNT(DISTINCT e.job_id) job_count, "
              + "       MAX(e.created_at) last_seen, MAX(e.error_code) code, "
              + "       MAX(LEFT(COALESCE(e.error_message,''),160)) msg "
              + "FROM dhan_historical_errors e "
              + "WHERE (UPPER(COALESCE(e.error_code,'')) LIKE '%DH-905%' "
              + "   OR LOWER(COALESCE(e.error_message,'')) LIKE '%dh-905%' "
              + "   OR LOWER(COALESCE(e.error_message,'')) LIKE '%missing required fields%') "
              + "  AND NOT EXISTS (SELECT 1 FROM broker_market_candles c "
              + "                   WHERE UPPER(COALESCE(c.symbol,'')) = UPPER(TRIM(e.symbol)) "
              + "                     AND UPPER(COALESCE(c.candle_mode,'')) = 'DAILY') "
              + "GROUP BY 1 HAVING COUNT(DISTINCT e.job_id) >= " + jobs + " "
              + "ORDER BY job_count DESC, sym ASC")
                .getResultList();

            // Only report what is actually still in the uploaded list. A symbol already removed is
            // not a pending decision, and listing it would make the report look like work that is
            // never finished.
            List<Object[]> tracked = em.createNativeQuery(
                "SELECT UPPER(TRIM(symbol)), COALESCE(base_price,0), COALESCE(sector,''), "
              + "COALESCE(entry_date, upload_date, '') "
              + "FROM tracked_stocks WHERE user_id=? AND institution_id=? "
              + "AND COALESCE(is_active,1)=1 AND COALESCE(is_deleted,0)=0")
                .setParameter(1, userId).setParameter(2, institutionId).getResultList();
            Map<String, Object[]> trackedBySym = new LinkedHashMap<>();
            for (Object[] t : tracked) trackedBySym.put(str(t[0]), t);

            for (Object[] r : result) {
                String sym = str(r[0]);
                Object[] t = trackedBySym.get(sym);
                if (t == null) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("symbol", sym);
                m.put("failedJobs", num(r[1]));
                m.put("lastSeen", str(r[2]));
                m.put("errorCode", str(r[3]));
                m.put("errorMessage", str(r[4]));
                m.put("basePrice", num(t[1]));
                m.put("sector", str(t[2]));
                m.put("uploadDate", str(t[3]));
                rows.add(m);
            }

            out.put("success", true);
            out.put("symbols", rows);
            out.put("count", rows.size());
            out.put("minJobs", jobs);
            out.put("trackedActive", tracked.size());
            out.put("reason", "Dhan answers DH-905 for these - it will not serve the instrument, "
                + "which almost always means delisted or a security id that no longer resolves. "
                + "They cost an API call and a rate-limit slot on every backfill and will never "
                + "return data.");
            out.put("note", "Nothing is removed by reading this. Deactivating keeps the row, its "
                + "base price and its upload history, so it can be undone - and you must name the "
                + "symbols to deactivate them.");
        } catch (Exception e) {
            log.warn("[DeadSymbols] Report failed: {}", e.getMessage());
            out.put("success", false);
            out.put("symbols", rows);
            out.put("message", "Could not build the report: " + e.getMessage());
        }
        return out;
    }

    /**
     * Deactivate the named symbols. Named, explicitly - there is no "all" form.
     *
     * @param symbols the exact symbols the user reviewed. An empty list does nothing.
     */
    public Map<String, Object> deactivate(Long userId, Long institutionId, List<String> symbols,
                                          String actor) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> clean = new ArrayList<>();
        if (symbols != null) {
            for (String s : symbols) {
                String u = s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
                if (!u.isBlank() && !clean.contains(u)) clean.add(u);
            }
        }
        if (clean.isEmpty()) {
            out.put("success", false);
            out.put("deactivated", 0);
            out.put("message", "Name the symbols to deactivate. There is no bulk form on purpose: "
                + "a symbol can be refused for a reason that gets fixed, and removing a live "
                + "holding costs more than one more failed API call.");
            return out;
        }
        try {
            StringBuilder in = new StringBuilder();
            for (int i = 0; i < clean.size(); i++) in.append(i == 0 ? "?" : ",?");
            final int[] n = {0};
            tx.executeWithoutResult(s -> {
                var q = em.createNativeQuery(
                    "UPDATE tracked_stocks SET is_active=0, delete_type='DHAN_REFUSED', "
                  + "deleted_by=?, deleted_at=? "
                  + "WHERE user_id=? AND institution_id=? AND COALESCE(is_active,1)=1 "
                  + "AND UPPER(TRIM(symbol)) IN (" + in + ")");
                q.setParameter(1, actor == null ? "system" : actor);
                q.setParameter(2, DateUtil.now());
                q.setParameter(3, userId);
                q.setParameter(4, institutionId);
                for (int i = 0; i < clean.size(); i++) q.setParameter(5 + i, clean.get(i));
                n[0] = q.executeUpdate();
            });
            log.info("[DeadSymbols] Deactivated {} symbol(s) refused by Dhan: {}", n[0],
                String.join(", ", clean.subList(0, Math.min(20, clean.size()))));
            out.put("success", true);
            out.put("deactivated", n[0]);
            out.put("requested", clean.size());
            out.put("message", n[0] + " of " + clean.size() + " symbol(s) deactivated. They keep "
                + "their row, base price and upload history, so this can be undone from Uploaded "
                + "Stock Details. Backfills will stop spending an API call on them.");
        } catch (Exception e) {
            log.warn("[DeadSymbols] Deactivate failed: {}", e.getMessage());
            out.put("success", false);
            out.put("deactivated", 0);
            out.put("message", "Nothing was changed: " + e.getMessage());
        }
        return out;
    }

    /** Undo, by name, for a symbol deactivated by mistake or relisted since. */
    public Map<String, Object> reactivate(Long userId, Long institutionId, List<String> symbols) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> clean = new ArrayList<>();
        if (symbols != null) {
            for (String s : symbols) {
                String u = s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
                if (!u.isBlank() && !clean.contains(u)) clean.add(u);
            }
        }
        if (clean.isEmpty()) {
            out.put("success", false);
            out.put("reactivated", 0);
            out.put("message", "Name the symbols to reactivate.");
            return out;
        }
        try {
            StringBuilder in = new StringBuilder();
            for (int i = 0; i < clean.size(); i++) in.append(i == 0 ? "?" : ",?");
            final int[] n = {0};
            tx.executeWithoutResult(s -> {
                var q = em.createNativeQuery(
                    "UPDATE tracked_stocks SET is_active=1, delete_type=NULL, deleted_by=NULL, "
                  + "deleted_at=NULL WHERE user_id=? AND institution_id=? "
                  + "AND COALESCE(is_deleted,0)=0 AND UPPER(TRIM(symbol)) IN (" + in + ")");
                q.setParameter(1, userId);
                q.setParameter(2, institutionId);
                for (int i = 0; i < clean.size(); i++) q.setParameter(3 + i, clean.get(i));
                n[0] = q.executeUpdate();
            });
            out.put("success", true);
            out.put("reactivated", n[0]);
            out.put("message", n[0] + " symbol(s) reactivated.");
        } catch (Exception e) {
            out.put("success", false);
            out.put("reactivated", 0);
            out.put("message", e.getMessage());
        }
        return out;
    }

    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
}
