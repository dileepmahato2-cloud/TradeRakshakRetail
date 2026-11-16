package com.traderakshak.service;

import com.traderakshak.util.DateUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads NSE index constituents so the master data can answer "which indices is this stock in".
 *
 * <p>Dhan's instrument list names the indices themselves but says nothing about their members,
 * so membership has to come from NSE, which publishes one CSV per index. Each file is a plain
 * list of the stocks currently in that index — the header is
 * {@code Company Name, Industry, Symbol, Series, ISIN Code}.
 *
 * <p>A refresh rewrites one index at a time and is safe to re-run. Stocks that have dropped out
 * at a rebalance are marked inactive rather than deleted, so a membership that mattered when a
 * past ranking ran can still be seen afterwards.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexMembershipService {

    private static final String BASE = "https://nsearchives.nseindia.com/content/indices/";

    /**
     * The indices worth carrying: the broad-market ladder the ranking universe sits on, plus the
     * sector indices, which give NSE's own definition of what sector a stock trades in rather
     * than the free-text label already on tracked_stocks.
     */
    private static final List<String[]> INDICES = List.of(
        // broad market
        new String[]{"NIFTY_50",           "NIFTY 50",            "ind_nifty50list.csv"},
        new String[]{"NIFTY_100",          "NIFTY 100",           "ind_nifty100list.csv"},
        new String[]{"NIFTY_200",          "NIFTY 200",           "ind_nifty200list.csv"},
        new String[]{"NIFTY_500",          "NIFTY 500",           "ind_nifty500list.csv"},
        new String[]{"NIFTY_MIDCAP_150",   "NIFTY MIDCAP 150",    "ind_niftymidcap150list.csv"},
        new String[]{"NIFTY_SMALLCAP_250", "NIFTY SMALLCAP 250",  "ind_niftysmallcap250list.csv"},
        new String[]{"NIFTY_NEXT_50",      "NIFTY NEXT 50",       "ind_niftynext50list.csv"},
        new String[]{"NIFTY_MICROCAP_250", "NIFTY MICROCAP 250",  "ind_niftymicrocap250_list.csv"},
        // sectors
        new String[]{"NIFTY_BANK",         "NIFTY BANK",          "ind_niftybanklist.csv"},
        new String[]{"NIFTY_IT",           "NIFTY IT",            "ind_niftyitlist.csv"},
        new String[]{"NIFTY_AUTO",         "NIFTY AUTO",          "ind_niftyautolist.csv"},
        new String[]{"NIFTY_PHARMA",       "NIFTY PHARMA",        "ind_niftypharmalist.csv"},
        new String[]{"NIFTY_FMCG",         "NIFTY FMCG",          "ind_niftyfmcglist.csv"},
        new String[]{"NIFTY_METAL",        "NIFTY METAL",         "ind_niftymetallist.csv"},
        new String[]{"NIFTY_ENERGY",       "NIFTY ENERGY",        "ind_niftyenergylist.csv"},
        new String[]{"NIFTY_REALTY",       "NIFTY REALTY",        "ind_niftyrealtylist.csv"},
        new String[]{"NIFTY_MEDIA",        "NIFTY MEDIA",         "ind_niftymedialist.csv"},
        new String[]{"NIFTY_PSU_BANK",     "NIFTY PSU BANK",      "ind_niftypsubanklist.csv"},
        new String[]{"NIFTY_PVT_BANK",     "NIFTY PRIVATE BANK",  "ind_nifty_privatebanklist.csv"},
        new String[]{"NIFTY_FIN_SERVICE",  "NIFTY FINANCIAL SERVICES", "ind_niftyfinancelist.csv"},
        new String[]{"NIFTY_HEALTHCARE",   "NIFTY HEALTHCARE",    "ind_niftyhealthcarelist.csv"},
        new String[]{"NIFTY_CONSR_DURBL",  "NIFTY CONSUMER DURABLES", "ind_niftyconsumerdurableslist.csv"},
        new String[]{"NIFTY_OIL_GAS",      "NIFTY OIL AND GAS",   "ind_niftyoilgaslist.csv"},
        new String[]{"NIFTY_INFRA",        "NIFTY INFRASTRUCTURE", "ind_niftyinfralist.csv"}
    );

    private final NseExchangeFetchService nseFetch;
    private final PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;

    /** Every index this loader knows about, for the screen to list before anything is fetched. */
    public List<Map<String, Object>> knownIndices() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] ix : INDICES) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index_key", ix[0]);
            m.put("index_name", ix[1]);
            m.put("file", ix[2]);
            out.add(m);
        }
        return out;
    }

    /**
     * Refreshes constituents from NSE.
     *
     * @param onlyIndexKey one index to refresh, or null for all of them
     */
    public Map<String, Object> refresh(String onlyIndexKey) {
        long started = System.currentTimeMillis();
        String asOn = DateUtil.today();
        List<Map<String, Object>> perIndex = new ArrayList<>();
        int okCount = 0, failCount = 0, totalRows = 0;

        for (String[] ix : INDICES) {
            String key = ix[0], name = ix[1], file = ix[2];
            if (onlyIndexKey != null && !onlyIndexKey.isBlank()
                && !onlyIndexKey.equalsIgnoreCase(key)) continue;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index_key", key);
            row.put("index_name", name);
            try {
                String csv = nseFetch.fetchIndexConstituentCsv(BASE + file);
                List<String[]> parsed = parseConstituents(csv);
                if (parsed.isEmpty()) throw new IllegalStateException("empty or unrecognised CSV");

                int written = writeIndex(key, name, parsed, asOn);
                totalRows += written;
                okCount++;
                row.put("status", "ok");
                row.put("stocks", written);
            } catch (Exception e) {
                failCount++;
                row.put("status", "failed");
                row.put("stocks", 0);
                row.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
                // One index failing must not abandon the other twenty-three - NSE rotates and
                // occasionally renames these files, and a single 404 is not a reason to leave
                // the whole mapping unbuilt.
                log.warn("Index constituents for {} could not be loaded: {}", key, e.getMessage());
            }
            perIndex.add(row);
        }

        syncStockMasterFlags();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", failCount == 0);
        out.put("indices_ok", okCount);
        out.put("indices_failed", failCount);
        out.put("memberships_written", totalRows);
        out.put("as_on", asOn);
        out.put("took_ms", System.currentTimeMillis() - started);
        out.put("per_index", perIndex);
        out.put("message", okCount + " index list(s) loaded, " + totalRows + " membership row(s)."
            + (failCount > 0 ? " " + failCount + " list(s) could not be fetched - see per_index." : ""));
        return out;
    }

    /**
     * Replaces one index's membership.
     *
     * <p>Done as deactivate-then-upsert inside one transaction: a stock that left the index at
     * the last rebalance ends up is_active=0, a stock still in it is reactivated, and the index
     * is never briefly empty for anything reading concurrently.
     */
    private int writeIndex(String key, String name, List<String[]> rows, String asOn) {
        return new TransactionTemplate(transactionManager).execute(tx -> {
            em.createNativeQuery(
                "UPDATE stock_index_membership SET is_active=0, updated_at=CURRENT_TIMESTAMP "
              + "WHERE index_key=?")
                .setParameter(1, key).executeUpdate();

            int n = 0;
            for (String[] r : rows) {
                String symbol = r[2];
                if (symbol == null || symbol.isBlank()) continue;
                em.createNativeQuery(
                    "INSERT INTO stock_index_membership"
                  + "(symbol,index_key,index_name,company_name,industry,series,isin,source,as_on_date,is_active,updated_at) "
                  + "VALUES (?,?,?,?,?,?,?,'NSE',?,1,CURRENT_TIMESTAMP) "
                  + "ON CONFLICT (symbol,index_key) DO UPDATE SET "
                  + "index_name=EXCLUDED.index_name, company_name=EXCLUDED.company_name, "
                  + "industry=EXCLUDED.industry, series=EXCLUDED.series, isin=EXCLUDED.isin, "
                  + "as_on_date=EXCLUDED.as_on_date, is_active=1, updated_at=CURRENT_TIMESTAMP")
                    .setParameter(1, symbol)
                    .setParameter(2, key)
                    .setParameter(3, name)
                    .setParameter(4, r[0])
                    .setParameter(5, r[1])
                    .setParameter(6, r[3])
                    .setParameter(7, r[4])
                    .setParameter(8, asOn)
                    .executeUpdate();
                n++;
            }
            return n;
        });
    }

    /**
     * Keeps stock_master's two existing flags in step with the membership table.
     *
     * <p>They were in the schema and 0 on all 23,427 rows. Anything already reading them should
     * start seeing the truth rather than be broken or left stale.
     */
    private void syncStockMasterFlags() {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                for (String[] pair : new String[][]{
                        {"nifty50_flag", "NIFTY_50"}, {"nifty500_flag", "NIFTY_500"}}) {
                    em.createNativeQuery(
                        "UPDATE stock_master SET " + pair[0] + " = CASE WHEN EXISTS ("
                      + "  SELECT 1 FROM stock_index_membership m WHERE m.index_key=? "
                      + "  AND m.is_active=1 AND UPPER(TRIM(m.symbol))=UPPER(TRIM(stock_master.symbol))"
                      + ") THEN 1 ELSE 0 END")
                        .setParameter(1, pair[1]).executeUpdate();
                }
            });
        } catch (Exception e) {
            log.warn("stock_master index flags could not be synced: {}", e.getMessage());
        }
    }

    /**
     * Parses an NSE constituent CSV into {company, industry, symbol, series, isin}.
     *
     * <p>Column order is read from the header rather than assumed - NSE has moved these around
     * between files, and a fixed position silently loads industry names into the symbol column.
     */
    private List<String[]> parseConstituents(String csv) {
        List<String[]> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        String[] lines = csv.split("\\r?\\n");
        if (lines.length < 2) return out;

        List<String> header = splitCsvLine(lines[0]);
        int cCompany = indexOfHeader(header, "company name", "company");
        int cIndustry = indexOfHeader(header, "industry");
        int cSymbol = indexOfHeader(header, "symbol");
        int cSeries = indexOfHeader(header, "series");
        int cIsin = indexOfHeader(header, "isin code", "isin");
        if (cSymbol < 0) return out;   // without a symbol column there is nothing to map

        for (int i = 1; i < lines.length; i++) {
            if (lines[i] == null || lines[i].isBlank()) continue;
            List<String> f = splitCsvLine(lines[i]);
            String symbol = at(f, cSymbol);
            if (symbol.isEmpty()) continue;
            out.add(new String[]{
                at(f, cCompany), at(f, cIndustry),
                symbol.toUpperCase(Locale.ROOT), at(f, cSeries), at(f, cIsin)
            });
        }
        return out;
    }

    private int indexOfHeader(List<String> header, String... names) {
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i).trim().toLowerCase(Locale.ROOT).replace("\"", "");
            for (String n : names) if (h.equals(n)) return i;
        }
        return -1;
    }

    private String at(List<String> f, int i) {
        if (i < 0 || i >= f.size()) return "";
        return f.get(i).trim().replaceAll("^\"|\"$", "").trim();
    }

    /** Comma split that respects quoted company names, which routinely contain commas. */
    private List<String> splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (char ch : line.toCharArray()) {
            if (ch == '"') inQuotes = !inQuotes;
            else if (ch == ',' && !inQuotes) { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }

    // ---------------- reads ----------------

    /** Which indices a stock belongs to. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> indicesForSymbol(String symbol) {
        List<Object[]> rows = em.createNativeQuery(
            "SELECT index_key,index_name,industry,as_on_date FROM stock_index_membership "
          + "WHERE is_active=1 AND UPPER(TRIM(symbol))=UPPER(TRIM(?)) ORDER BY index_name")
            .setParameter(1, symbol == null ? "" : symbol).getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index_key", r[0]);
            m.put("index_name", r[1]);
            m.put("industry", r[2]);
            m.put("as_on", r[3]);
            out.add(m);
        }
        return out;
    }

    /** What is loaded right now, per index. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> summary() {
        List<Object[]> rows = em.createNativeQuery(
            "SELECT index_key, MAX(index_name), COUNT(*) FILTER (WHERE is_active=1), "
          + "COUNT(*) FILTER (WHERE is_active=0), MAX(as_on_date) "
          + "FROM stock_index_membership GROUP BY index_key ORDER BY MAX(index_name)")
            .getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index_key", r[0]);
            m.put("index_name", r[1]);
            m.put("stocks", r[2]);
            m.put("dropped", r[3]);
            m.put("as_on", r[4]);
            out.add(m);
        }
        return out;
    }
}
