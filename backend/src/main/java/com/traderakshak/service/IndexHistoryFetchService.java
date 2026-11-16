package com.traderakshak.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads daily index history from NSE's all-index close file.
 *
 * <p>One file per trading day carries every index at once — 135 of them in Aug 2025, 165 by Aug
 * 2026 — so a year of history is ~250 requests rather than one long download per index. It also
 * brings P/E, P/B and dividend yield, which the broker candle feed does not have at all.
 *
 * <p>Chosen over fetching each index separately through Dhan: 119 instruments × 13 monthly
 * batches against roughly 250 single files, and the Dhan route was leaving the headline indices
 * (NIFTY, BANKNIFTY, NIFTY 500) empty anyway.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexHistoryFetchService {

    private static final String URL =
        "https://nsearchives.nseindia.com/content/indices/ind_close_all_";
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("ddMMyyyy");
    private static final DateTimeFormatter NSE_DATE =
        DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH);

    private final NseExchangeFetchService nseFetch;
    private final PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;

    /**
     * Fetches every trading day in the range.
     *
     * <p>Weekends are skipped without a request. A holiday looks identical to a weekend from the
     * outside — the file simply is not published — so a 404 is recorded as "no file" and the run
     * carries on; treating it as an error would abort a year's load on the first Diwali.
     */
    public Map<String, Object> fetchRange(String fromDate, String toDate) {
        long started = System.currentTimeMillis();
        LocalDate from = LocalDate.parse(fromDate);
        LocalDate to = LocalDate.parse(toDate);
        if (to.isBefore(from)) { LocalDate s = from; from = to; to = s; }

        int daysFetched = 0, daysNoFile = 0, daysFailed = 0, rowsWritten = 0;
        List<String> failures = new ArrayList<>();

        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            int dow = d.getDayOfWeek().getValue();
            if (dow >= 6) continue;                       // no session, no file to ask for

            String url = URL + d.format(FILE_DATE) + ".csv";
            try {
                String csv = nseFetch.fetchIndexConstituentCsv(url);
                List<Map<String, Object>> rows = parse(csv, d);
                if (rows.isEmpty()) { daysNoFile++; continue; }
                rowsWritten += write(rows);
                daysFetched++;
            } catch (Exception e) {
                String msg = String.valueOf(e.getMessage());
                if (msg.contains("404")) {
                    daysNoFile++;                          // market holiday
                } else {
                    daysFailed++;
                    if (failures.size() < 10) failures.add(d + ": " + msg);
                    log.warn("Index close file for {} failed: {}", d, msg);
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", daysFailed == 0);
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("days_fetched", daysFetched);
        out.put("days_no_file", daysNoFile);
        out.put("days_failed", daysFailed);
        out.put("rows_written", rowsWritten);
        out.put("failures", failures);
        out.put("took_ms", System.currentTimeMillis() - started);
        out.put("message", daysFetched + " trading day(s) loaded, " + rowsWritten
            + " index row(s). " + daysNoFile + " day(s) had no file (holidays)."
            + (daysFailed > 0 ? " " + daysFailed + " day(s) failed." : ""));
        return out;
    }

    /** Upserts on the table's existing (symbol, trade_date) key, so a re-run corrects rather than duplicates. */
    private int write(List<Map<String, Object>> rows) {
        return new TransactionTemplate(transactionManager).execute(tx -> {
            int n = 0;
            for (Map<String, Object> r : rows) {
                em.createNativeQuery(
                    "INSERT INTO index_data(index_name,symbol,trade_date,open,high,low,close,"
                  + "prev_close,change_pct,volume,turnover,pe,pb,div_yield,source) "
                  + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'NSE') "
                  + "ON CONFLICT (symbol,trade_date) DO UPDATE SET "
                  + "index_name=EXCLUDED.index_name, open=EXCLUDED.open, high=EXCLUDED.high, "
                  + "low=EXCLUDED.low, close=EXCLUDED.close, prev_close=EXCLUDED.prev_close, "
                  + "change_pct=EXCLUDED.change_pct, volume=EXCLUDED.volume, "
                  + "turnover=EXCLUDED.turnover, pe=EXCLUDED.pe, pb=EXCLUDED.pb, "
                  + "div_yield=EXCLUDED.div_yield, source='NSE'")
                    .setParameter(1, r.get("index_name"))
                    .setParameter(2, r.get("symbol"))
                    .setParameter(3, r.get("trade_date"))
                    .setParameter(4, r.get("open"))
                    .setParameter(5, r.get("high"))
                    .setParameter(6, r.get("low"))
                    .setParameter(7, r.get("close"))
                    .setParameter(8, r.get("prev_close"))
                    .setParameter(9, r.get("change_pct"))
                    .setParameter(10, r.get("volume"))
                    .setParameter(11, r.get("turnover"))
                    .setParameter(12, r.get("pe"))
                    .setParameter(13, r.get("pb"))
                    .setParameter(14, r.get("div_yield"))
                    .executeUpdate();
                n++;
            }
            return n;
        });
    }

    /**
     * Parses the all-index CSV.
     *
     * <p>Columns are located by header name. NSE has widened this file over time — 135 indices in
     * Aug 2025, 165 now — and reading fixed positions would silently load turnover into volume the
     * first time a column is inserted.
     */
    private List<Map<String, Object>> parse(String csv, LocalDate expected) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        String[] lines = csv.split("\\r?\\n");
        if (lines.length < 2) return out;

        List<String> h = split(lines[0]);
        int cName = col(h, "index name"), cDate = col(h, "index date");
        int cOpen = col(h, "open index value"), cHigh = col(h, "high index value");
        int cLow = col(h, "low index value"), cClose = col(h, "closing index value");
        int cChg = col(h, "points change"), cPct = col(h, "change(%)", "change (%)", "change%");
        int cVol = col(h, "volume"), cTurn = col(h, "turnover (rs. cr.)", "turnover");
        int cPe = col(h, "p/e"), cPb = col(h, "p/b"), cDy = col(h, "div yield");
        if (cName < 0 || cClose < 0) return out;   // not the file we expect - a block page, say

        for (int i = 1; i < lines.length; i++) {
            if (lines[i] == null || lines[i].isBlank()) continue;
            List<String> f = split(lines[i]);
            String name = txt(f, cName);
            if (name.isEmpty()) continue;
            Double close = num(f, cClose);
            if (close == null) continue;           // a row with no close is not a quote

            String date = expected.toString();
            String raw = txt(f, cDate);
            if (!raw.isEmpty()) {
                try { date = LocalDate.parse(raw, NSE_DATE).toString(); } catch (Exception ignored) {}
            }
            Double chg = num(f, cChg);

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("index_name", name);
            r.put("symbol", key(name));
            r.put("trade_date", date);
            r.put("open", num(f, cOpen));
            r.put("high", num(f, cHigh));
            r.put("low", num(f, cLow));
            r.put("close", close);
            // NSE gives the points move, not the previous close. Deriving it here means the
            // stored row can answer "what did it close at yesterday" without a self-join.
            r.put("prev_close", chg == null ? null : Math.round((close - chg) * 100.0) / 100.0);
            r.put("change_pct", num(f, cPct));
            r.put("volume", num(f, cVol));
            r.put("turnover", num(f, cTurn));
            r.put("pe", num(f, cPe));
            r.put("pb", num(f, cPb));
            r.put("div_yield", num(f, cDy));
            out.add(r);
        }
        return out;
    }

    /**
     * A stable key for an index name.
     *
     * <p>NSE writes "Nifty 50" where Dhan lists "NIFTY", and NSE's own capitalisation drifts
     * between files ("NIFTY BANK" and "Nifty Bank" both appear). Upper-casing and stripping
     * spaces gives one key per index, which is what the unique constraint needs.
     */
    private String key(String name) {
        return name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private int col(List<String> header, String... names) {
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i).trim().toLowerCase(Locale.ROOT).replace("\"", "");
            for (String n : names) if (h.equals(n)) return i;
        }
        return -1;
    }

    private String txt(List<String> f, int i) {
        if (i < 0 || i >= f.size()) return "";
        return f.get(i).trim().replaceAll("^\"|\"$", "").trim();
    }

    private Double num(List<String> f, int i) {
        String s = txt(f, i).replace(",", "");
        if (s.isEmpty() || s.equals("-") || s.equalsIgnoreCase("NA")) return null;
        try { return Double.parseDouble(s); } catch (Exception e) { return null; }
    }

    private List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (char ch : line.toCharArray()) {
            if (ch == '"') q = !q;
            else if (ch == ',' && !q) { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }

    /** What index history is stored, so the screen can show the gap before a fetch is started. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> coverage() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Object[] r = (Object[]) em.createNativeQuery(
                "SELECT COUNT(*), COUNT(DISTINCT symbol), COUNT(DISTINCT trade_date), "
              + "MIN(trade_date), MAX(trade_date) FROM index_data").getSingleResult();
            out.put("rows", r[0]);
            out.put("indices", r[1]);
            out.put("trading_days", r[2]);
            out.put("first_date", r[3]);
            out.put("last_date", r[4]);
        } catch (Exception e) {
            out.put("rows", 0);
            out.put("error", e.getMessage());
        }
        return out;
    }
}
