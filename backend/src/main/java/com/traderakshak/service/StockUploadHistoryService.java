package com.traderakshak.service;

// =============================================================================
// StockUploadHistoryService — TradeRakshak™
//
// Owns stock_upload_history: the append-only log of every uploaded "strong
// stock" signal, and the two ways of reading it.
//
//   MODE A — getLatestPerSymbol()
//       One row per symbol: its most recent entry_date, whatever date the user
//       is looking at. Used by Today's Top 10, Previous Top 10, the Upload Stock
//       section and the Eligible Stock download. Those screens reach it through
//       tracked_stocks, which syncTrackedStocks() keeps as an exact projection
//       of this query — so the ~50 existing tracked_stocks queries did not have
//       to be rewritten to gain latest-per-symbol semantics.
//
//   MODE B — getByDate() / getAsOfDate()
//       The rows as they stood on a chosen historical date. getByDate is
//       exact-date-only (Previous Chart "related to date"); getAsOfDate is
//       "this date or the most recent flag before it" (backtest reconstruction,
//       where a stock flagged in January is still held in April).
//
// The two must never be swapped: Mode A on a backtest screen reports today's
// signal as though it had been known months ago, which is look-ahead bias and
// silently inflates every backtested return.
// =============================================================================

import com.traderakshak.util.DateUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockUploadHistoryService {

    @PersistenceContext
    private EntityManager em;

    /** One parsed file row, already normalized. entryDate is ISO 'yyyy-MM-dd'. */
    public record UploadRow(String symbol, String entryDate, String marketCapName, String sector) {}

    /** What one recordUpload() call did, for the upload response and the audit log. */
    public record RecordResult(int inserted, int duplicates, int failed, List<String> symbols) {}

    /** Columns every read returns, aliased so the DATE columns arrive as ISO text. */
    private static final String SELECT_COLS =
        "symbol, to_char(entry_date,'YYYY-MM-DD') AS entry_date, marketcap_name, sector, " +
        "base_price, to_char(base_price_date,'YYYY-MM-DD') AS base_price_date, base_price_source, " +
        "upload_batch_id, file_name, to_char(uploaded_at,'YYYY-MM-DD HH24:MI:SS') AS uploaded_at";

    // ==========================================================================
    //  WRITE — append every row, drop only exact repeats
    // ==========================================================================

    /**
     * Appends one upload's rows. Rows are never merged, skipped or overwritten
     * because a symbol already exists on another date — that history is the
     * product. The single exception is a byte-identical row (same tenant, user,
     * symbol, entry_date, market cap and sector), which ux_suh_row_identity
     * rejects: re-uploading yesterday's file adds nothing, so it stores nothing.
     */
    @Transactional
    public RecordResult recordUpload(UUID uploadBatchId, String fileName, List<UploadRow> rows,
                                     Long userId, Long institutionId) {
        int inserted = 0, duplicates = 0, failed = 0;
        List<String> symbols = new ArrayList<>();
        for (UploadRow row : rows) {
            try {
                int affected = em.createNativeQuery(
                    "INSERT INTO stock_upload_history(upload_batch_id,file_name,institution_id,user_id," +
                    "entry_date,symbol,marketcap_name,sector,uploaded_at) " +
                    "VALUES(CAST(? AS UUID),?,?,?,CAST(? AS DATE),?,?,?,now()) " +
                    "ON CONFLICT (institution_id,user_id,symbol,entry_date,marketcap_name,sector) DO NOTHING")
                    .setParameter(1, uploadBatchId.toString())
                    .setParameter(2, fileName)
                    .setParameter(3, scopeInstitution(institutionId))
                    .setParameter(4, userId == null ? 0L : userId)
                    .setParameter(5, row.entryDate())
                    .setParameter(6, row.symbol())
                    .setParameter(7, row.marketCapName() == null ? "" : row.marketCapName())
                    .setParameter(8, row.sector() == null ? "" : row.sector())
                    .executeUpdate();
                if (affected > 0) inserted++; else duplicates++;
                symbols.add(row.symbol());
            } catch (Exception e) {
                failed++;
                log.debug("Upload history insert {} @ {}: {}", row.symbol(), row.entryDate(), e.getMessage());
            }
        }
        return new RecordResult(inserted, duplicates, failed, symbols.stream().distinct().toList());
    }

    // ==========================================================================
    //  MODE A — latest per symbol
    // ==========================================================================

    /**
     * The symbol's most recent appearance, regardless of the date on screen.
     * uploaded_at then id break a tie when the same symbol arrives twice for the
     * same entry_date with different sector/market-cap text: the newest upload wins.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getLatestPerSymbol(Long userId, Long institutionId) {
        StringBuilder sql = new StringBuilder(
            "SELECT DISTINCT ON (symbol) " + SELECT_COLS +
            " FROM stock_upload_history WHERE institution_id=?");
        if (userId != null) sql.append(" AND user_id=?");
        sql.append(" ORDER BY symbol, entry_date DESC, uploaded_at DESC, id DESC");

        Query q = em.createNativeQuery(sql.toString()).setParameter(1, scopeInstitution(institutionId));
        if (userId != null) q.setParameter(2, userId);
        return toMaps(q.getResultList());
    }

    // ==========================================================================
    //  MODE B — as the data stood on a chosen date
    // ==========================================================================

    /** Exactly the rows flagged on {@code date}. Nothing before it, nothing after. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getByDate(String date, Long userId, Long institutionId) {
        String iso = normalizeDate(date);
        if (iso == null) return List.of();
        StringBuilder sql = new StringBuilder(
            "SELECT " + SELECT_COLS + " FROM stock_upload_history " +
            "WHERE institution_id=? AND entry_date = CAST(? AS DATE)");
        if (userId != null) sql.append(" AND user_id=?");
        sql.append(" ORDER BY symbol");

        Query q = em.createNativeQuery(sql.toString())
            .setParameter(1, scopeInstitution(institutionId)).setParameter(2, iso);
        if (userId != null) q.setParameter(3, userId);
        return toMaps(q.getResultList());
    }

    /**
     * Each symbol's most recent flag on or before {@code date} — the state of the
     * book as it actually was that day. This is the one a backtest wants: a stock
     * flagged in January is still in the basket in April, but a stock first
     * flagged in May must not appear in an April run.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getAsOfDate(String date, Long userId, Long institutionId) {
        String iso = normalizeDate(date);
        if (iso == null) return List.of();
        StringBuilder sql = new StringBuilder(
            "SELECT DISTINCT ON (symbol) " + SELECT_COLS + " FROM stock_upload_history " +
            "WHERE institution_id=? AND entry_date <= CAST(? AS DATE)");
        if (userId != null) sql.append(" AND user_id=?");
        sql.append(" ORDER BY symbol, entry_date DESC, uploaded_at DESC, id DESC");

        Query q = em.createNativeQuery(sql.toString())
            .setParameter(1, scopeInstitution(institutionId)).setParameter(2, iso);
        if (userId != null) q.setParameter(3, userId);
        return toMaps(q.getResultList());
    }

    /** Every appearance of one symbol, newest first — the audit view behind both modes. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getSymbolHistory(String symbol, Long userId, Long institutionId) {
        if (symbol == null || symbol.isBlank()) return List.of();
        StringBuilder sql = new StringBuilder(
            "SELECT " + SELECT_COLS + " FROM stock_upload_history " +
            "WHERE institution_id=? AND symbol=?");
        if (userId != null) sql.append(" AND user_id=?");
        sql.append(" ORDER BY entry_date DESC, uploaded_at DESC, id DESC");

        Query q = em.createNativeQuery(sql.toString())
            .setParameter(1, scopeInstitution(institutionId))
            .setParameter(2, symbol.trim().toUpperCase());
        if (userId != null) q.setParameter(3, userId);
        return toMaps(q.getResultList());
    }

    /** Distinct signal dates present in the log, newest first — drives the date picker. */
    @SuppressWarnings("unchecked")
    public List<String> getAvailableDates(Long userId, Long institutionId, int limit) {
        StringBuilder sql = new StringBuilder(
            "SELECT to_char(entry_date,'YYYY-MM-DD') FROM stock_upload_history WHERE institution_id=?");
        if (userId != null) sql.append(" AND user_id=?");
        sql.append(" GROUP BY entry_date ORDER BY entry_date DESC LIMIT ")
           .append(Math.max(1, Math.min(limit, 2000)));

        Query q = em.createNativeQuery(sql.toString()).setParameter(1, scopeInstitution(institutionId));
        if (userId != null) q.setParameter(2, userId);
        List<Object> rows = q.getResultList();
        return rows.stream().map(String::valueOf).toList();
    }

    // ==========================================================================
    //  BASE PRICE — the close on the flagged date, stored per history row
    // ==========================================================================

    /**
     * Fills base_price from the stored daily candles: the close on entry_date, or
     * the most recent close before it when that date was a holiday or is missing
     * from the feed. base_price_date records which day was actually used, so a
     * base taken from a stale candle is visible rather than assumed.
     *
     * <p>The price is pinned to the row's own entry_date, which is what makes a
     * backtest of a past date honest — the same symbol flagged on three dates
     * carries three different base prices.
     */
    @Transactional
    public int backfillBasePrices(Long institutionId) {
        try {
            return em.createNativeQuery(
                "UPDATE stock_upload_history h SET " +
                "  base_price = sub.close_price, " +
                "  base_price_date = to_date(sub.trade_date,'YYYY-MM-DD'), " +
                "  base_price_source = sub.src " +
                "FROM ( " +
                "  SELECT h2.id AS hid, c.close AS close_price, c.trade_date AS trade_date, " +
                "         COALESCE(c.source,'BROKER_DAILY') AS src " +
                "  FROM stock_upload_history h2 " +
                "  JOIN LATERAL ( " +
                "    SELECT bmc.close, bmc.trade_date, bmc.source FROM broker_market_candles bmc " +
                "    WHERE UPPER(COALESCE(bmc.symbol,'')) = UPPER(h2.symbol) " +
                "      AND UPPER(COALESCE(bmc.candle_mode,'')) = 'DAILY' " +
                "      AND bmc.close IS NOT NULL AND bmc.close > 0 " +
                "      AND bmc.trade_date <= to_char(h2.entry_date,'YYYY-MM-DD') " +
                "    ORDER BY bmc.trade_date DESC LIMIT 1) c ON TRUE " +
                "  WHERE h2.institution_id = ? AND (h2.base_price IS NULL OR h2.base_price <= 0) " +
                ") sub WHERE h.id = sub.hid")
                .setParameter(1, scopeInstitution(institutionId))
                .executeUpdate();
        } catch (Exception e) {
            log.warn("Upload-history base price backfill skipped: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Backtest rows for a chosen date: base price on the flagged date against the
     * latest stored close, and the return between them.
     *
     * @param asOf true for "this date or the most recent flag before it", false
     *        for exact-date-only. Both are offered because the Previous Chart and
     *        the backtest engine genuinely want different sets.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getBacktestReturns(String date, boolean asOf,
                                                        Long userId, Long institutionId) {
        String iso = normalizeDate(date);
        if (iso == null) return List.of();

        String selection = asOf
            ? "SELECT DISTINCT ON (symbol) symbol, entry_date, marketcap_name, sector, base_price " +
              "FROM stock_upload_history WHERE institution_id=? AND entry_date <= CAST(? AS DATE)" +
              (userId != null ? " AND user_id=?" : "") +
              " ORDER BY symbol, entry_date DESC, uploaded_at DESC, id DESC"
            : "SELECT symbol, entry_date, marketcap_name, sector, base_price " +
              "FROM stock_upload_history WHERE institution_id=? AND entry_date = CAST(? AS DATE)" +
              (userId != null ? " AND user_id=?" : "");

        String sql =
            "SELECT h.symbol, to_char(h.entry_date,'YYYY-MM-DD'), h.marketcap_name, h.sector, " +
            "       h.base_price, p.close, p.trade_date " +
            "FROM (" + selection + ") h " +
            "JOIN LATERAL ( " +
            "  SELECT bmc.close, bmc.trade_date FROM broker_market_candles bmc " +
            "  WHERE UPPER(COALESCE(bmc.symbol,'')) = UPPER(h.symbol) " +
            "    AND UPPER(COALESCE(bmc.candle_mode,'')) = 'DAILY' " +
            "    AND bmc.close IS NOT NULL AND bmc.close > 0 " +
            "  ORDER BY bmc.trade_date DESC LIMIT 1) p ON TRUE " +
            "WHERE h.base_price IS NOT NULL AND h.base_price > 0 " +
            "ORDER BY h.symbol";

        try {
            Query q = em.createNativeQuery(sql)
                .setParameter(1, scopeInstitution(institutionId)).setParameter(2, iso);
            if (userId != null) q.setParameter(3, userId);

            List<Object[]> rows = q.getResultList();
            List<Map<String, Object>> out = new ArrayList<>(rows.size());
            for (Object[] r : rows) {
                double base    = num(r[4]);
                double current = num(r[5]);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("symbol",             String.valueOf(r[0]));
                m.put("entry_date",         r[1] == null ? "" : String.valueOf(r[1]));
                m.put("marketcap_name",     r[2] == null ? "" : String.valueOf(r[2]));
                m.put("sector",             r[3] == null ? "" : String.valueOf(r[3]));
                m.put("base_price",         base);
                m.put("current_price",      current);
                m.put("current_price_date", r[6] == null ? "" : String.valueOf(r[6]));
                m.put("return_pct", base > 0
                    ? Math.round(((current - base) / base) * 10000.0) / 100.0 : 0.0);
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            log.warn("Backtest returns for {} failed: {}", iso, e.getMessage());
            return List.of();
        }
    }

    // ==========================================================================
    //  MODE A PROJECTION — keep tracked_stocks equal to getLatestPerSymbol()
    // ==========================================================================

    /**
     * Projects the latest history row for each of {@code symbols} into
     * tracked_stocks, the table every current-state screen already reads.
     *
     * <p>Two things this fixes over the previous straight upsert:
     * <ul>
     *   <li>the written row is the symbol's newest entry_date across ALL uploads,
     *       so re-uploading an older file no longer drags a symbol backwards;</li>
     *   <li>base_price is cleared only when the date actually moves. The old
     *       upsert nulled it on every upload, forcing a full Dhan re-fetch even
     *       for symbols whose date had not changed.</li>
     * </ul>
     *
     * @return the symbols now lacking a usable base price, i.e. those the caller
     *         needs to fetch prices for.
     */
    @Transactional
    public List<String> syncTrackedStocks(List<String> symbols, String uploadId, String batchId,
                                          Long userId, Long institutionId) {
        if (symbols == null || symbols.isEmpty()) return List.of();
        String now = DateUtil.now();
        long inst = scopeInstitution(institutionId);
        List<String> distinct = symbols.stream().distinct().toList();

        for (String symbol : distinct) {
            try {
                em.createNativeQuery(
                    "INSERT INTO tracked_stocks(institution_id,user_id,upload_id,symbol,market_cap_name," +
                    "sector,upload_date,is_active,created_at,updated_at,batch_id,is_deleted) " +
                    "SELECT ?,?,?,h.symbol,h.marketcap_name,h.sector," +
                    "       to_char(h.entry_date,'YYYY-MM-DD'),1,?,?,?,0 " +
                    "FROM (SELECT DISTINCT ON (symbol) symbol, entry_date, marketcap_name, sector " +
                    "      FROM stock_upload_history WHERE institution_id=? AND symbol=? " +
                    "      ORDER BY symbol, entry_date DESC, uploaded_at DESC, id DESC) h " +
                    "ON CONFLICT(institution_id,symbol) DO UPDATE SET " +
                    "  upload_id=excluded.upload_id, " +
                    "  upload_date=excluded.upload_date, " +
                    "  market_cap_name=excluded.market_cap_name, " +
                    "  sector=excluded.sector, " +
                    // A base price belongs to one specific upload_date. Keep it while the date
                    // holds; drop it the moment the date moves so the next fetch re-derives it.
                    "  base_price = CASE WHEN tracked_stocks.upload_date IS DISTINCT FROM excluded.upload_date " +
                    "                    THEN NULL ELSE tracked_stocks.base_price END, " +
                    "  base_price_source = CASE WHEN tracked_stocks.upload_date IS DISTINCT FROM excluded.upload_date " +
                    "                    THEN NULL ELSE tracked_stocks.base_price_source END, " +
                    "  return_pct = CASE WHEN tracked_stocks.upload_date IS DISTINCT FROM excluded.upload_date " +
                    "                    THEN 0 ELSE tracked_stocks.return_pct END, " +
                    "  is_active=1, batch_id=excluded.batch_id, is_deleted=0, deleted_at=NULL, " +
                    "  deleted_by=NULL, delete_type=NULL, updated_at=excluded.updated_at")
                    .setParameter(1, inst).setParameter(2, userId)
                    .setParameter(3, uploadId).setParameter(4, now)
                    .setParameter(5, now).setParameter(6, batchId)
                    .setParameter(7, inst).setParameter(8, symbol)
                    .executeUpdate();
            } catch (Exception e) {
                log.debug("tracked_stocks projection for {}: {}", symbol, e.getMessage());
            }
        }
        em.flush();
        em.clear();
        return symbolsMissingBasePrice(distinct, inst);
    }

    @SuppressWarnings("unchecked")
    private List<String> symbolsMissingBasePrice(List<String> symbols, long institutionId) {
        if (symbols.isEmpty()) return List.of();
        try {
            String in = String.join(",", java.util.Collections.nCopies(symbols.size(), "?"));
            Query q = em.createNativeQuery(
                "SELECT symbol FROM tracked_stocks WHERE institution_id=? AND is_active=1 " +
                "AND (base_price IS NULL OR base_price<=0) AND symbol IN (" + in + ")")
                .setParameter(1, institutionId);
            for (int i = 0; i < symbols.size(); i++) q.setParameter(i + 2, symbols.get(i));
            List<Object> rows = q.getResultList();
            return rows.stream().map(String::valueOf).toList();
        } catch (Exception e) {
            log.debug("Base-price gap lookup failed, refetching all {} symbols: {}", symbols.size(), e.getMessage());
            return symbols;
        }
    }

    /** True once at least one history row exists — the guard before Mode B replaces a legacy read. */
    public boolean hasHistory(Long institutionId) {
        try {
            Object r = em.createNativeQuery(
                "SELECT COUNT(*) FROM stock_upload_history WHERE institution_id=?")
                .setParameter(1, scopeInstitution(institutionId)).getSingleResult();
            return r != null && ((Number) r).longValue() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ==========================================================================
    //  HELPERS
    // ==========================================================================

    private static final String[] DATE_FORMATS =
        {"yyyy-MM-dd", "dd-MM-yyyy", "dd/MM/yyyy", "MM/dd/yyyy", "d-MMM-yyyy"};

    /**
     * ISO 'yyyy-MM-dd', or null when the value is not a date.
     *
     * <p>Returning null rather than falling back to today() is deliberate, and is
     * the opposite of the lenient parser used for display elsewhere: an
     * unreadable date silently becoming today would make that row the symbol's
     * newest entry forever, which is exactly the answer Mode A exists to get right.
     */
    public static String normalizeDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        if (value.length() > 10 && value.charAt(4) == '-' && value.charAt(7) == '-') {
            value = value.substring(0, 10);
        }
        // Excel serial dates (the sheet was saved with that column formatted as a number).
        if (value.matches("\\d+(\\.0+)?")) {
            try {
                long serial = Math.round(Double.parseDouble(value));
                if (serial > 25000 && serial < 90000) {
                    return LocalDate.of(1899, 12, 30).plusDays(serial).toString();
                }
            } catch (Exception ignored) { /* not a usable serial — reject below */ }
            return null;
        }
        for (String fmt : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, DateTimeFormatter.ofPattern(fmt)).toString();
            } catch (Exception ignored) { /* try the next format */ }
        }
        return null;
    }

    private long scopeInstitution(Long institutionId) {
        return institutionId == null ? 1L : institutionId;
    }

    private double num(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    private List<Map<String, Object>> toMaps(List<Object[]> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol",            String.valueOf(r[0]));
            m.put("entry_date",        r[1] == null ? "" : String.valueOf(r[1]));
            m.put("marketcap_name",    r[2] == null ? "" : String.valueOf(r[2]));
            m.put("sector",            r[3] == null ? "" : String.valueOf(r[3]));
            m.put("base_price",        r[4] == null ? null : num(r[4]));
            m.put("base_price_date",   r[5] == null ? "" : String.valueOf(r[5]));
            m.put("base_price_source", r[6] == null ? "" : String.valueOf(r[6]));
            m.put("upload_batch_id",   r[7] == null ? "" : String.valueOf(r[7]));
            m.put("file_name",         r[8] == null ? "" : String.valueOf(r[8]));
            m.put("uploaded_at",       r[9] == null ? "" : String.valueOf(r[9]));
            out.add(m);
        }
        return out;
    }
}
