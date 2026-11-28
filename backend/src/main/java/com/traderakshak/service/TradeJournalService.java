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
 * The Trade Journal: one record of every trade, whatever produced it.
 *
 * <p><b>Why this exists as one service.</b> There were two journals. {@code trading_journal} is
 * strategy-aware and carries mode, stop-loss, target, charges, exit reason and the broker response;
 * {@code trade_journal} is a plain manual log behind a separate screen. Two journals means a
 * question like "what did I make this month" has two answers, and the specification asks for one
 * journal holding paper, real, manual, automated, stop-loss and trailing-stop exits alike.
 *
 * <p>{@code trading_journal} is the system of record, because it already has the fields. The older
 * table is read, never written: nothing historical is lost, and nothing new lands there to widen
 * the split. The union is what the screen shows.
 *
 * <p><b>Charges are computed here, not accepted from the caller.</b> Section 9 asks for brokerage
 * to be auto-calculated, and a manual entry is exactly where a hand-typed zero does most damage -
 * it is the one trade nobody cross-checks against a contract note.
 */
@Service
public class TradeJournalService {

    private static final Logger log = LoggerFactory.getLogger(TradeJournalService.class);

    /** The strategy key the broker importer stamps on rows it creates. */
    static final String BROKER_IMPORT_KEY = "broker_import";

    /**
     * How a trade came to exist: System, Manual or External Broker.
     *
     * <p>Derived rather than stored. Every writer already records who placed the trade - the
     * journal through {@code is_manual}, the simulator through {@code paper_trades.source} - and
     * the importer already stamps its own {@code strategy_key}. A fourth column holding the same
     * fact would be a second source of truth that can drift from the first, and on a financial
     * record a disagreement about who placed a trade is worse than a slightly longer expression.
     */
    static String originOf(boolean isManual, String strategyKey) {
        if (BROKER_IMPORT_KEY.equalsIgnoreCase(strategyKey == null ? "" : strategyKey.trim())) {
            return "EXTERNAL";
        }
        return isManual ? "MANUAL" : "SYSTEM";
    }

    /**
     * REAL or PAPER - never guessed.
     *
     * <p>A row with no mode recorded returns UNKNOWN rather than defaulting to PAPER. Defaulting
     * would quietly file a trade whose account nobody knows under "paper money", which is the
     * safe-sounding half of a distinction the specification requires be kept exact.
     */
    static String accountOf(String tradingMode) {
        String m = tradingMode == null ? "" : tradingMode.trim().toUpperCase(Locale.ROOT);
        if (m.equals("REAL") || m.equals("LIVE")) return "REAL";
        if (m.equals("PAPER") || m.equals("DEMO") || m.equals("VIRTUAL")) return "PAPER";
        return "UNKNOWN";
    }

    /** Human wording for the pair, so the screen and the CSV agree on one vocabulary. */
    static String originLabel(String origin) {
        return switch (origin == null ? "" : origin) {
            case "SYSTEM" -> "System Trade";
            case "MANUAL" -> "Manual Trade";
            case "EXTERNAL" -> "External Broker Trade";
            default -> "Unclassified";
        };
    }

    static String accountLabel(String account) {
        return switch (account == null ? "" : account) {
            case "REAL" -> "Real Trade";
            case "PAPER" -> "Paper Trade";
            default -> "Unclassified";
        };
    }

    @PersistenceContext
    private EntityManager em;

    private final BrokerageCalculator charges;
    private final StockTrackerService tracker;
    private final TransactionTemplate tx;

    public TradeJournalService(BrokerageCalculator charges, StockTrackerService tracker,
                               TransactionTemplate tx) {
        this.charges = charges;
        this.tracker = tracker;
        this.tx = tx;
    }

    // ===================================================================== read

    /**
     * Every journal row for a user, newest first, filterable.
     *
     * @param source ALL, SYSTEM (strategy-generated) or MANUAL. Section 9 requires manual trades
     *               to stay distinguishable from system-generated ones, so this is a filter rather
     *               than a merged blur.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> list(Long userId, String from, String to, String symbol,
                                    String strategy, String mode, String source,
                                    String status, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            StringBuilder w = new StringBuilder(" WHERE user_id=?");
            List<Object> p = new ArrayList<>();
            p.add(userId);
            if (notBlank(from))     { w.append(" AND SUBSTRING(created_at,1,10) >= ?"); p.add(from); }
            if (notBlank(to))       { w.append(" AND SUBSTRING(created_at,1,10) <= ?"); p.add(to); }
            if (notBlank(symbol))   { w.append(" AND UPPER(symbol) LIKE ?"); p.add("%" + symbol.toUpperCase(Locale.ROOT) + "%"); }
            if (notBlank(strategy)) { w.append(" AND strategy_key=?"); p.add(strategy); }
            // Account filter. Mapped through accountOf rather than compared to the raw column,
            // because the codebase writes LIVE as well as REAL and DEMO as well as PAPER, and a
            // literal comparison silently drops whichever spelling the filter did not name.
            if (notBlank(mode)) {
                String acct = accountOf(mode);
                if ("REAL".equals(acct))  w.append(" AND UPPER(COALESCE(trading_mode,'')) IN ('REAL','LIVE')");
                else if ("PAPER".equals(acct)) w.append(" AND UPPER(COALESCE(trading_mode,'')) IN ('PAPER','DEMO','VIRTUAL')");
                else w.append(" AND COALESCE(TRIM(trading_mode),'') = ''");
            }
            if (notBlank(status))   { w.append(" AND UPPER(COALESCE(trade_status,'PLACED'))=?"); p.add(status.toUpperCase(Locale.ROOT)); }

            // Origin filter, the other half of the classification matrix. EXTERNAL is the case
            // that was missing entirely: a broker import is written with is_manual=1, so under the
            // old two-way test it was indistinguishable from a hand-typed entry.
            String src = source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
            String notImport = " AND LOWER(COALESCE(strategy_key,'')) <> '" + BROKER_IMPORT_KEY + "'";
            switch (src) {
                case "SYSTEM"   -> w.append(" AND COALESCE(is_manual,0)=0").append(notImport);
                case "MANUAL"   -> w.append(" AND COALESCE(is_manual,0)=1").append(notImport);
                case "EXTERNAL" -> w.append(" AND LOWER(COALESCE(strategy_key,'')) = '")
                                    .append(BROKER_IMPORT_KEY).append("'");
                default -> { /* ALL, or nothing asked for */ }
            }

            var q = em.createNativeQuery(
                "SELECT id,trade_ref,symbol,sector,strategy_key,strategy_name,trading_mode,trade_type,"
              + "quantity,buy_price,sell_price,stop_loss,trailing_stop_loss,target_price,"
              + "capital_utilized,brokerage_charges,gross_pnl,net_profit_loss,exit_reason,outcome,"
              + "COALESCE(trade_status,'PLACED'),COALESCE(filled_qty,0),COALESCE(pending_qty,0),"
              + "broker_order_id,signal_at,order_placed_at,executed_at,created_at,"
              + "COALESCE(is_manual,0),system_remarks,user_remarks,charges_breakdown,"
              + "COALESCE(product_type,'DELIVERY') "
              + "FROM trading_journal" + w + " ORDER BY id DESC LIMIT " + Math.max(1, Math.min(limit, 2000)));
            for (int i = 0; i < p.size(); i++) q.setParameter(i + 1, p.get(i));
            for (Object[] r : (List<Object[]>) q.getResultList()) rows.add(map(r, "trading_journal"));

            // The older table, read-only. Only when nothing narrower than the user is being asked
            // for - its rows carry no strategy, mode or status, so applying those filters to them
            // would silently drop every legacy row and make a filtered view look emptier than the
            // data is.
            // Legacy rows carry no strategy, mode, status or origin, so ANY narrowing filter must
            // exclude them rather than silently drop them and make a filtered view look emptier
            // than the data is.
            boolean legacyApplicable = !notBlank(strategy) && !notBlank(mode) && !notBlank(status)
                                    && (src.isEmpty() || "ALL".equals(src));
            if (legacyApplicable) rows.addAll(legacyRows(userId, from, to, symbol, limit));

            // Simulated trades. These were missing from the journal altogether: the automation
            // engine and the manual paper path both write to paper_trades, nothing copied them
            // across, and this service read only the two journal tables - so a System/Paper trade
            // could exist and never appear. Verified on the live database, where a closed
            // automated paper trade in JLHL sat in paper_trades with no journal row anywhere.
            //
            // Read here rather than duplicated into trading_journal on write: paper_trades is the
            // master record for a simulated position, and a second copy is a second set of
            // figures that can disagree about what the position cost.
            rows.addAll(paperTradeRows(userId, from, to, symbol, strategy, mode, src, status, limit));

            // Mark open positions to market.
            //
            // Without this an open trade showed a stored gross and net of 0 and a 0.00% return,
            // because those columns are only written when a position CLOSES - so four live
            // positions read as flat and priceless while the prices sat in the database. "Not
            // updated" was exactly right: nothing was updating them, because nothing was meant to
            // until the exit.
            markOpenRowsToMarket(rows);

            rows.sort((a, b) -> String.valueOf(b.get("createdAt"))
                                      .compareTo(String.valueOf(a.get("createdAt"))));

            out.put("success", true);
            out.put("rows", rows);
            out.put("count", rows.size());
            out.put("analytics", analytics(rows));
            // The same figures split by account, and a flag saying when the headline above is a
            // mixture. Real and paper money must never be added into one "Net P&L": the total is
            // the right answer for a view filtered to one account and a misleading one otherwise,
            // so the split is always available and the mixing is always stated.
            out.put("analyticsByAccount", analyticsByAccount(rows));
            out.put("classificationMatrix", classificationMatrix(rows));
            out.put("mixesAccounts", analyticsByAccount(rows).size() > 1);
            out.put("strategies", distinct("strategy_key", userId));
            out.put("note", "One journal. trading_journal is the system of record; the older "
                + "trade_journal is read for history and never written to.");
        } catch (Exception e) {
            log.warn("[TradeJournal] List failed: {}", e.getMessage());
            out.put("success", false);
            out.put("rows", rows);
            out.put("message", "Could not read the journal: " + e.getMessage());
        }
        return out;
    }

    /**
     * Simulated positions, mapped into the journal's own row shape.
     *
     * <p>Read-only. {@code paper_trades} stays the master record for a simulated position; this
     * presents it alongside the real ones so "every trade the system generated" is actually true
     * of what the journal shows.
     *
     * <p>Classification comes from the table's own columns: {@code source} says who placed it
     * (AUTO is the scheduled engine, MANUAL a person), and the account is always PAPER unless the
     * row explicitly says otherwise. Nothing here is inferred from a strategy name.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> paperTradeRows(Long userId, String from, String to,
                                                     String symbol, String strategy, String mode,
                                                     String src, String status, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();

        // A paper trade is never a real one and never an external broker trade, so a filter
        // asking for either of those excludes this source entirely.
        if (notBlank(mode) && !"PAPER".equals(accountOf(mode))) return out;
        if ("EXTERNAL".equals(src)) return out;

        try {
            StringBuilder w = new StringBuilder(" WHERE user_id=?");
            List<Object> p = new ArrayList<>();
            p.add(userId);
            if (notBlank(from))     { w.append(" AND COALESCE(exit_date,entry_date) >= ?"); p.add(from); }
            if (notBlank(to))       { w.append(" AND COALESCE(exit_date,entry_date) <= ?"); p.add(to); }
            if (notBlank(symbol))   { w.append(" AND UPPER(symbol) LIKE ?"); p.add("%" + symbol.toUpperCase(Locale.ROOT) + "%"); }
            if (notBlank(strategy)) { w.append(" AND strategy_key=?"); p.add(strategy); }
            // AUTO is a System trade, anything else a Manual one.
            if ("SYSTEM".equals(src)) w.append(" AND UPPER(COALESCE(source,'AUTO'))='AUTO'");
            if ("MANUAL".equals(src)) w.append(" AND UPPER(COALESCE(source,'AUTO'))<>'AUTO'");
            if (notBlank(status)) {
                String st = status.toUpperCase(Locale.ROOT);
                if ("CLOSED".equals(st) || "FILLED".equals(st)) w.append(" AND LOWER(COALESCE(status,''))='closed'");
                else if ("OPEN".equals(st)) w.append(" AND LOWER(COALESCE(status,''))='open'");
                else return out;   // PLACED, REJECTED and the rest do not exist for a simulation
            }

            var q = em.createNativeQuery(
                "SELECT id,trade_id,symbol,sector,strategy_key,qty,entry_price,exit_price,"
              + "stop_loss,trailing_sl_level,target,COALESCE(total_charges,0),"
              + "COALESCE(gross_pnl,0),COALESCE(pnl,0),COALESCE(pnl_pct,0),status,entry_date,"
              + "exit_date,exit_reason,COALESCE(source,'AUTO'),COALESCE(trade_mode,'virtual'),"
              + "exit_type,signal_id,automation_config "
              + "FROM paper_trades" + w + " ORDER BY id DESC LIMIT " + Math.max(1, Math.min(limit, 2000)));
            for (int i = 0; i < p.size(); i++) q.setParameter(i + 1, p.get(i));

            for (Object[] r : (List<Object[]>) q.getResultList()) {
                boolean closed = "closed".equalsIgnoreCase(str(r[15]));
                boolean manual = !"AUTO".equalsIgnoreCase(str(r[19]));
                String account = accountOf(str(r[20]));
                if ("UNKNOWN".equals(account)) account = "PAPER";   // the table's whole purpose

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r[0]);
                m.put("tradeRef", str(r[1]));
                m.put("symbol", str(r[2]));
                m.put("sector", str(r[3]));
                m.put("strategyKey", str(r[4]));
                m.put("strategyName", str(r[4]).isBlank() ? "Simulated trade" : str(r[4]));
                m.put("tradingMode", account);
                m.put("tradeType", closed ? "SELL" : "BUY");
                m.put("quantity", (int) num(r[5]));
                m.put("buyPrice", num(r[6]));
                m.put("sellPrice", num(r[7]));
                m.put("stopLoss", num(r[8]));
                m.put("trailingStopLoss", num(r[9]));
                m.put("targetPrice", num(r[10]));
                m.put("capitalUtilized", round(num(r[6]) * num(r[5])));
                m.put("charges", num(r[11]));
                m.put("grossPnl", num(r[12]));
                m.put("netPnl", num(r[13]));
                m.put("returnPct", num(r[14]));
                m.put("tradeStatus", closed ? "CLOSED" : "OPEN");
                m.put("filledQty", (int) num(r[5]));
                m.put("pendingQty", 0);
                m.put("entryDate", str(r[16]));
                m.put("exitDate", str(r[17]));
                m.put("createdAt", str(r[16]));
                m.put("exitReason", str(r[18]));
                m.put("outcome", closed ? (num(r[13]) > 0 ? "Win" : num(r[13]) < 0 ? "Loss" : "Scratch")
                                        : "Open Trade");
                m.put("isManual", manual);
                // No broker order exists for a simulated fill, and saying so is the point: an
                // empty order id on a paper row is correct, not missing data.
                m.put("brokerOrderId", "");
                m.put("productType", "DELIVERY");
                // The settings the engine was running under when it placed this. Absent
                // on a manual simulation, which had no automation configuration.
                // Index 23: automation_config is the last column of the SELECT, after signal_id.
                // It was read from 22 and so returned the signal id, which is blank far more
                // often than not - the column looked empty rather than wrong.
                m.put("automationConfig", str(r[23]).isBlank() ? null : str(r[23]));
                m.put("systemRemarks", "Simulated fill. Nothing was sent to the broker.");
                m.put("userRemarks", "");
                m.put("legacy", false);
                m.put("source", "paper_trades");
                m.put("simulated", true);
                classify(m, originOf(manual, str(r[4])), account);
                out.add(m);
            }
        } catch (Exception e) {
            log.warn("[TradeJournal] Simulated trades could not be read: {}", e.getMessage());
        }
        return out;
    }

    /** Stamp the classification pair and its wording onto a row, in one place for every source. */
    private static void classify(Map<String, Object> m, String origin, String account) {
        m.put("tradeOrigin", origin);
        m.put("tradeTypeLabel", originLabel(origin));
        m.put("accountType", account);
        m.put("accountTypeLabel", accountLabel(account));
        m.put("classification", originLabel(origin) + " / " + accountLabel(account));
        String exec = executionStatusOf(str(m.get("tradeStatus")));
        m.put("executionStatus", exec);
        m.put("executionStatusLabel", executionStatusLabel(exec));
    }

    /**
     * The stored trade status expressed in section 8's execution vocabulary.
     *
     * <p>Mapped rather than migrated. The stored values (PLACED, OPEN, FILLED, PARTIAL, CLOSED,
     * REJECTED, CANCELLED, EXPIRED) are written by several services and read by others, and
     * rewriting them in the database to match a new vocabulary would change existing records and
     * every query that matches on them. The requirement is that a clear execution status be
     * reported, and that is what this does.
     *
     * <p>Two of the eight states are not reachable from stored data today: SCHEDULED, which
     * describes an order waiting for its buy time, and SIGNAL_CONFIRMED, which lives in
     * {@code strategy_signals} rather than in the journal. They are part of the vocabulary so a
     * caller can rely on the full set, and are reported honestly as absent rather than faked by
     * mapping some other state onto them.
     */
    static String executionStatusOf(String tradeStatus) {
        String st = tradeStatus == null ? "" : tradeStatus.trim().toUpperCase(Locale.ROOT);
        return switch (st) {
            case "SCHEDULED"                    -> "SCHEDULED";
            case "SIGNAL_CONFIRMED", "SIGNAL"   -> "SIGNAL_CONFIRMED";
            case "PLACED", "SUBMITTED", "PENDING" -> "ORDER_SUBMITTED";
            case "PARTIAL", "PARTIALLY_FILLED"  -> "PARTIALLY_FILLED";
            // An open position is one whose entry actually filled, so it is Executed - not
            // pending. Saying otherwise would report money as uncommitted while it is in a stock.
            case "FILLED", "EXECUTED", "CLOSED", "OPEN" -> "EXECUTED";
            case "REJECTED"                     -> "REJECTED";
            case "CANCELLED", "CANCELED"        -> "CANCELLED";
            case "FAILED", "EXPIRED"            -> "FAILED";
            default -> "";
        };
    }

    static String executionStatusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "SCHEDULED"        -> "Scheduled";
            case "SIGNAL_CONFIRMED" -> "Signal Confirmed";
            case "ORDER_SUBMITTED"  -> "Order Submitted";
            case "PARTIALLY_FILLED" -> "Partially Filled";
            case "EXECUTED"         -> "Executed";
            case "REJECTED"         -> "Rejected";
            case "CANCELLED"        -> "Cancelled";
            case "FAILED"           -> "Failed";
            default -> "Unknown";
        };
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> legacyRows(Long userId, String from, String to,
                                                 String symbol, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            StringBuilder w = new StringBuilder(" WHERE user_id=?");
            List<Object> p = new ArrayList<>();
            p.add(userId);
            if (notBlank(from))   { w.append(" AND COALESCE(sale_date,trade_date) >= ?"); p.add(from); }
            if (notBlank(to))     { w.append(" AND COALESCE(sale_date,trade_date) <= ?"); p.add(to); }
            if (notBlank(symbol)) { w.append(" AND UPPER(symbol) LIKE ?"); p.add("%" + symbol.toUpperCase(Locale.ROOT) + "%"); }
            var q = em.createNativeQuery(
                "SELECT id,trade_id,symbol,sector,trade_source,trade_date,purchase_price,qty_buy,"
              + "sale_date,sell_price,qty_sell,brokerage_est,gross_pnl,net_pnl,return_pct,"
              + "result_status,entry_remark,closing_remark,created_at "
              + "FROM trade_journal" + w + " ORDER BY id DESC LIMIT " + Math.max(1, Math.min(limit, 2000)));
            for (int i = 0; i < p.size(); i++) q.setParameter(i + 1, p.get(i));
            for (Object[] r : (List<Object[]>) q.getResultList()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r[0]);
                m.put("tradeRef", str(r[1]).isBlank() ? "LEGACY-" + str(r[0]) : str(r[1]));
                m.put("symbol", str(r[2]));
                m.put("sector", str(r[3]));
                m.put("strategyKey", "");
                m.put("strategyName", str(r[4]).isBlank() ? "Legacy entry" : str(r[4]));
                // Not "PAPER", which is what this said. These rows record no mode at all, and
                // labelling an unknown account as paper money is a guess that happens to sound
                // harmless.
                m.put("tradingMode", "UNKNOWN");
                m.put("tradeType", num(r[9]) > 0 ? "SELL" : "BUY");
                m.put("quantity", (int) num(r[7]));
                m.put("buyPrice", num(r[6]));
                m.put("sellPrice", num(r[9]));
                m.put("charges", num(r[11]));
                m.put("grossPnl", num(r[12]));
                m.put("netPnl", num(r[13]));
                m.put("returnPct", num(r[14]));
                m.put("outcome", str(r[15]));
                m.put("tradeStatus", num(r[9]) > 0 ? "CLOSED" : "OPEN");
                m.put("entryDate", str(r[5]));
                m.put("exitDate", str(r[8]));
                m.put("createdAt", str(r[18]).isBlank() ? str(r[5]) : str(r[18]));
                m.put("isManual", true);
                m.put("systemRemarks", str(r[16]));
                m.put("userRemarks", str(r[17]));
                // Marked, so a reader can see why these rows have no strategy, mode or lifecycle
                // timestamps rather than wondering whether the data is broken.
                m.put("legacy", true);
                m.put("source", "trade_journal");
                // Deliberately unclassified. These rows predate the classification and record
                // neither who placed the trade nor which account it used; calling them
                // "Manual / Paper" would put invented provenance on a financial record.
                classify(m, "UNKNOWN", "UNKNOWN");
                out.add(m);
            }
        } catch (Exception e) {
            log.debug("[TradeJournal] Legacy read skipped: {}", e.getMessage());
        }
        return out;
    }

    /**
     * Attaches a live price and an unrealised P&amp;L to every row that has not been closed.
     *
     * <p>The stored {@code gross_pnl} and {@code net_profit_loss} are written at exit, so for an
     * open position they are legitimately zero - the figure a reader wants there is not stored
     * anywhere and has to be computed at read time against the current price.
     *
     * <p>Realised and unrealised stay in separate fields. Writing the mark into {@code netPnl}
     * would make an open position indistinguishable from a closed one in every total on the page,
     * and a paper gain would be counted as money taken.
     */
    private void markOpenRowsToMarket(List<Map<String, Object>> rows) {
        List<Map<String, Object>> open = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (num(r.get("sellPrice")) <= 0 && num(r.get("buyPrice")) > 0
                && !"CLOSED".equalsIgnoreCase(String.valueOf(r.get("tradeStatus")))) {
                open.add(r);
            }
        }
        if (open.isEmpty()) return;
        List<String> symbols = open.stream()
            .map(r -> String.valueOf(r.get("symbol"))).distinct().toList();
        Map<String, StockTrackerService.PricePoint> prices;
        try {
            prices = tracker.executablePricePoints(symbols);
        } catch (Exception e) {
            log.debug("[TradeJournal] Live price lookup skipped: {}", e.getMessage());
            return;
        }
        String today = DateUtil.today();
        for (Map<String, Object> r : open) {
            var pp = prices.get(tracker.normalizeSymbol(String.valueOf(r.get("symbol"))));
            double price = pp == null ? 0 : pp.price();
            if (price <= 0) continue;

            // The session this price belongs to, carried through to the screen. A figure labelled
            // "current" that is actually four sessions old is worse than no figure at all: it
            // reads as a live quote and gets acted on.
            r.put("priceAsOf", pp.asOf());
            r.put("priceSource", pp.source());
            r.put("priceStale", pp.asOf() != null && !today.equals(pp.asOf()));
            double buy = num(r.get("buyPrice"));
            int qty = (int) num(r.get("quantity"));
            double invested = buy * qty;
            double gross = round((price - buy) * qty);

            // The entry charge is already paid and stored; the exit charge is not yet incurred but
            // is unavoidable, so it is subtracted too. Otherwise the figure on screen is one the
            // user can never actually realise.
            var product = "INTRADAY".equalsIgnoreCase(String.valueOf(r.get("productType")))
                ? BrokerageCalculator.Product.INTRADAY : BrokerageCalculator.Product.DELIVERY;
            double exitCharge = 0;
            try {
                exitCharge = charges.charges(product, BrokerageCalculator.Side.SELL,
                                             "NSE", price, qty, true).total();
            } catch (Exception ignore) { }
            double netUnreal = round(gross - num(r.get("charges")) - exitCharge);

            r.put("currentPrice", price);
            r.put("unrealizedGross", gross);
            r.put("estimatedExitCharges", round(exitCharge));
            r.put("unrealizedNet", netUnreal);
            r.put("returnPct", invested > 0 ? round(netUnreal / invested * 100) : 0);
            r.put("marked", true);
        }
    }

    private Map<String, Object> map(Object[] r, String source) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r[0]);
        m.put("tradeRef", str(r[1]));
        m.put("symbol", str(r[2]));
        m.put("sector", str(r[3]));
        m.put("strategyKey", str(r[4]));
        m.put("strategyName", str(r[5]));
        m.put("tradingMode", str(r[6]).isBlank() ? "PAPER" : str(r[6]));
        m.put("tradeType", str(r[7]));
        m.put("quantity", (int) num(r[8]));
        m.put("buyPrice", num(r[9]));
        m.put("sellPrice", num(r[10]));
        m.put("stopLoss", num(r[11]));
        m.put("trailingStopLoss", num(r[12]));
        m.put("targetPrice", num(r[13]));
        m.put("capitalUtilized", num(r[14]));
        m.put("charges", num(r[15]));
        m.put("grossPnl", num(r[16]));
        m.put("netPnl", num(r[17]));
        m.put("exitReason", str(r[18]));
        m.put("outcome", str(r[19]));
        m.put("tradeStatus", str(r[20]));
        m.put("filledQty", (int) num(r[21]));
        m.put("pendingQty", (int) num(r[22]));
        m.put("brokerOrderId", str(r[23]));
        m.put("signalAt", str(r[24]));
        m.put("orderPlacedAt", str(r[25]));
        m.put("executedAt", str(r[26]));
        m.put("createdAt", str(r[27]));
        boolean manual = num(r[28]) == 1;
        m.put("isManual", manual);
        classify(m, originOf(manual, str(r[4])), accountOf(str(r[6])));
        m.put("systemRemarks", str(r[29]));
        m.put("userRemarks", str(r[30]));
        m.put("chargesBreakdown", str(r[31]));
        m.put("productType", str(r[32]));
        double invested = num(r[9]) * num(r[8]);
        m.put("returnPct", invested > 0 ? round(num(r[17]) / invested * 100) : 0);
        m.put("legacy", false);
        m.put("source", source);
        return m;
    }

    /**
     * Headline figures over the rows being shown, so they always agree with the table above them.
     *
     * <p>Computed from the filtered set rather than queried separately: a summary that answers a
     * different question from the list under it is worse than no summary.
     */
    private Map<String, Object> analytics(List<Map<String, Object>> rows) {
        int wins = 0, losses = 0, closed = 0, open = 0, partial = 0, rejected = 0;
        double gross = 0, net = 0, chargeTotal = 0, sumWin = 0, sumLoss = 0;
        // Unrealised is totalled separately and never folded into net: a paper gain on an open
        // position is not money taken, and a single "Net P&L" mixing the two would overstate what
        // the account actually holds.
        double unrealized = 0;
        int markedCount = 0;
        for (Map<String, Object> r : rows) {
            String st = String.valueOf(r.get("tradeStatus")).toUpperCase(Locale.ROOT);
            if ("PARTIAL".equals(st)) partial++;
            if ("REJECTED".equals(st) || "CANCELLED".equals(st) || "EXPIRED".equals(st)) { rejected++; continue; }
            double n = num(r.get("netPnl"));
            gross += num(r.get("grossPnl"));
            net += n;
            chargeTotal += num(r.get("charges"));
            boolean isClosed = num(r.get("sellPrice")) > 0 || "CLOSED".equals(st) || "FILLED".equals(st);
            if (isClosed && num(r.get("sellPrice")) > 0) {
                closed++;
                if (n > 0) { wins++; sumWin += n; } else if (n < 0) { losses++; sumLoss += Math.abs(n); }
            } else {
                open++;
                if (Boolean.TRUE.equals(r.get("marked"))) {
                    unrealized += num(r.get("unrealizedNet"));
                    markedCount++;
                }
            }
        }
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("totalTrades", rows.size());
        a.put("closedTrades", closed);
        a.put("openTrades", open);
        a.put("partialTrades", partial);
        a.put("rejectedTrades", rejected);
        a.put("winningTrades", wins);
        a.put("losingTrades", losses);
        a.put("winRate", closed > 0 ? round((double) wins / closed * 100) : 0);
        a.put("grossPnl", round(gross));
        a.put("totalCharges", round(chargeTotal));
        a.put("netPnl", round(net));
        a.put("unrealizedPnl", round(unrealized));
        a.put("markedPositions", markedCount);
        // Stated, because "0 of 4 priced" and "4 positions flat" look identical on screen and
        // mean opposite things - the first is a dead price feed.
        a.put("unpricedOpenPositions", open - markedCount);
        a.put("totalPnl", round(net + unrealized));
        a.put("averageProfit", wins > 0 ? round(sumWin / wins) : 0);
        a.put("averageLoss", losses > 0 ? round(sumLoss / losses) : 0);
        a.put("profitFactor", sumLoss > 0 ? round(sumWin / sumLoss) : null);
        return a;
    }

    /** The headline figures again, one block per account, so the two are never summed together. */
    private Map<String, Object> analyticsByAccount(List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> byAccount = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            byAccount.computeIfAbsent(str(r.get("accountType")), k -> new ArrayList<>()).add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : byAccount.entrySet()) out.put(e.getKey(), analytics(e.getValue()));
        return out;
    }

    /**
     * The classification matrix the specification asks for: origin against account.
     *
     * <p>Counted here rather than in the screen, so the table, the API and the CSV cannot report
     * three different breakdowns of the same rows. Only non-empty cells are returned - a grid of
     * zeros tells a reader nothing about what they actually have.
     */
    private Map<String, Object> classificationMatrix(List<Map<String, Object>> rows) {
        Map<String, Map<String, Integer>> grid = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            grid.computeIfAbsent(str(r.get("tradeOrigin")), k -> new LinkedHashMap<>())
                .merge(str(r.get("accountType")), 1, Integer::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : grid.entrySet()) {
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("label", originLabel(e.getKey()));
            int total = 0;
            for (var a : e.getValue().entrySet()) {
                cell.put(a.getKey(), a.getValue());
                total += a.getValue();
            }
            cell.put("total", total);
            out.put(e.getKey(), cell);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> distinct(String column, Long userId) {
        try {
            List<Object> vals = em.createNativeQuery(
                "SELECT DISTINCT " + column + " FROM trading_journal WHERE user_id=? AND "
              + column + " IS NOT NULL AND " + column + " <> '' ORDER BY 1")
                .setParameter(1, userId).getResultList();
            List<String> out = new ArrayList<>();
            for (Object v : vals) out.add(str(v));
            return out;
        } catch (Exception e) { return List.of(); }
    }

    // ==================================================================== write

    /**
     * Record a trade by hand, with charges computed rather than typed.
     *
     * <p>Writes to {@code trading_journal} with {@code is_manual = 1}, so a manual entry sits in
     * the same journal as everything else and is still identifiable. {@code trade_journal} is left
     * alone - adding to it would re-create the split this consolidation removes.
     *
     * <p>No broker order is placed. Section 9 is explicit, and it is worth being explicit back:
     * recording that you bought something is not an instruction to buy it.
     */
    public Map<String, Object> saveManual(Long userId, Long institutionId, String userName,
                                          Map<String, Object> req) {
        Map<String, Object> out = new LinkedHashMap<>();
        String symbol = str(req.get("symbol")).toUpperCase(Locale.ROOT);
        int qty = (int) num(req.get("quantity"));
        double buy = num(req.get("buyPrice"));
        double sell = num(req.get("sellPrice"));
        String entryRemark = str(req.get("entryRemark"));

        if (symbol.isBlank() || qty <= 0 || buy <= 0) {
            out.put("success", false);
            out.put("message", "Symbol, quantity and entry price are required, and quantity and "
                + "price must both be above zero.");
            return out;
        }
        if (entryRemark.isBlank()) {
            // Kept from the older screen, which made it mandatory. A journal whose entries do not
            // say why the trade was taken cannot be reviewed later, which is most of the point.
            out.put("success", false);
            out.put("message", "An entry remark is required - why was this trade taken?");
            return out;
        }

        BrokerageCalculator.Product product = "INTRADAY".equalsIgnoreCase(str(req.get("productType")))
            ? BrokerageCalculator.Product.INTRADAY : BrokerageCalculator.Product.DELIVERY;
        String exchange = str(req.get("exchange")).isBlank() ? "NSE" : str(req.get("exchange"));

        // Both legs when the trade is closed, the entry leg alone while it is open. Charging a
        // round trip on an open position would understate its current P&L by the exit cost it has
        // not paid yet.
        BrokerageCalculator.Charges c = sell > 0
            ? charges.roundTrip(product, exchange, buy, sell, qty)
            : charges.charges(product, BrokerageCalculator.Side.BUY, exchange, buy, qty, false);

        double gross = sell > 0 ? round((sell - buy) * qty) : 0;
        double net = sell > 0 ? round(gross - c.total()) : 0;
        boolean closed = sell > 0;
        String ref = "TJM-" + userId + "-" + DateUtil.today().replace("-", "") + "-"
                   + Math.abs((symbol + buy + qty + System.currentTimeMillis()).hashCode() % 100000);
        String now = DateUtil.now();
        String entryDate = str(req.get("entryDate")).isBlank() ? DateUtil.today() : str(req.get("entryDate"));

        try {
            tx.executeWithoutResult(s -> em.createNativeQuery(
                "INSERT INTO trading_journal(trade_ref,user_id,institution_id,user_name,strategy_key,"
              + "strategy_name,trading_mode,trade_type,stock_name,symbol,sector,quantity,buy_price,"
              + "sell_price,stop_loss,trailing_stop_loss,target_price,capital_utilized,"
              + "brokerage_charges,gross_pnl,net_profit_loss,exit_reason,outcome,system_remarks,"
              + "user_remarks,created_at,updated_at,charges_breakdown,trade_status,filled_qty,"
              + "pending_qty,is_manual,product_type,signal_at,order_placed_at,executed_at) "
              + "VALUES(?,?,?,?,'manual','Manual Trade',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,?,?,?)")
                .setParameter(1, ref).setParameter(2, userId).setParameter(3, institutionId)
                .setParameter(4, userName == null ? "" : userName)
                .setParameter(5, str(req.get("tradingMode")).isBlank() ? "PAPER"
                                   : str(req.get("tradingMode")).toUpperCase(Locale.ROOT))
                .setParameter(6, closed ? "SELL" : "BUY")
                .setParameter(7, symbol).setParameter(8, symbol)
                .setParameter(9, str(req.get("sector")).isBlank() ? "Miscellaneous" : str(req.get("sector")))
                .setParameter(10, qty).setParameter(11, buy).setParameter(12, sell)
                .setParameter(13, num(req.get("stopLoss")))
                .setParameter(14, num(req.get("trailingStopLoss")))
                .setParameter(15, num(req.get("targetPrice")))
                .setParameter(16, round(buy * qty))
                .setParameter(17, c.total())
                .setParameter(18, gross).setParameter(19, net)
                .setParameter(20, closed ? str(req.get("exitReason")).isBlank() ? "Manual Exit"
                                             : str(req.get("exitReason")) : "")
                .setParameter(21, closed ? (net > 0 ? "Win" : net < 0 ? "Loss" : "Scratch") : "Open Trade")
                .setParameter(22, "Manual entry. Charges computed at Dhan's published rates.")
                .setParameter(23, entryRemark)
                .setParameter(24, now).setParameter(25, now)
                .setParameter(26, toJson(c.asMap()))
                .setParameter(27, closed ? "CLOSED" : "OPEN")
                .setParameter(28, qty).setParameter(29, 0)
                .setParameter(30, product.name())
                .setParameter(31, entryDate + " 00:00:00")
                .setParameter(32, entryDate + " 00:00:00")
                .setParameter(33, closed && notBlank(str(req.get("exitDate")))
                                   ? str(req.get("exitDate")) + " 00:00:00" : (closed ? now : null))
                .executeUpdate());

            out.put("success", true);
            out.put("tradeRef", ref);
            out.put("charges", c.asMap());
            out.put("grossPnl", gross);
            out.put("netPnl", net);
            out.put("message", "Recorded " + symbol + ". Charges computed at "
                + c.total() + " (" + c.totalPct() + "% of the position)"
                + (closed ? ", net P&L " + net : ", position left open")
                + ". No broker order was placed.");
        } catch (Exception e) {
            log.warn("[TradeJournal] Manual save failed: {}", e.getMessage());
            out.put("success", false);
            out.put("message", "Could not record the trade: " + e.getMessage());
        }
        return out;
    }

    /**
     * Close an open manual position, computing the exit charges and net P&L.
     *
     * <p>Only ever touches a manual row. A system-generated trade is closed by the thing that owns
     * it - the position monitor or the broker - and letting a screen overwrite its exit would make
     * the journal disagree with the position it describes.
     */
    public Map<String, Object> closeManual(Long userId, long id, double exitPrice,
                                           String exitDate, String closingRemark) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (exitPrice <= 0) {
            out.put("success", false);
            out.put("message", "An exit price above zero is required.");
            return out;
        }
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                "SELECT quantity,buy_price,COALESCE(product_type,'DELIVERY'),symbol "
              + "FROM trading_journal WHERE id=? AND user_id=? AND COALESCE(is_manual,0)=1 "
              + "AND COALESCE(trade_status,'')<>'CLOSED'")
                .setParameter(1, id).setParameter(2, userId).getResultList();
            if (rows.isEmpty()) {
                out.put("success", false);
                out.put("message", "No open manual trade with that id. A system trade cannot be "
                    + "closed from here - it is closed by whatever opened it.");
                return out;
            }
            Object[] r = rows.get(0);
            int qty = (int) num(r[0]);
            double buy = num(r[1]);
            var product = "INTRADAY".equalsIgnoreCase(str(r[2]))
                ? BrokerageCalculator.Product.INTRADAY : BrokerageCalculator.Product.DELIVERY;
            var c = charges.roundTrip(product, "NSE", buy, exitPrice, qty);
            double gross = round((exitPrice - buy) * qty);
            double net = round(gross - c.total());
            String when = notBlank(exitDate) ? exitDate : DateUtil.today();

            tx.executeWithoutResult(s -> em.createNativeQuery(
                "UPDATE trading_journal SET sell_price=?, trade_type='SELL', trade_status='CLOSED', "
              + "gross_pnl=?, brokerage_charges=?, net_profit_loss=?, charges_breakdown=?, "
              + "outcome=?, exit_reason=?, executed_at=?, user_remarks=CASE WHEN ?='' THEN user_remarks "
              + "ELSE ? END, updated_at=? WHERE id=?")
                .setParameter(1, exitPrice)
                .setParameter(2, gross).setParameter(3, c.total()).setParameter(4, net)
                .setParameter(5, toJson(c.asMap()))
                .setParameter(6, net > 0 ? "Win" : net < 0 ? "Loss" : "Scratch")
                .setParameter(7, "Manual Exit")
                .setParameter(8, when + " 00:00:00")
                .setParameter(9, closingRemark == null ? "" : closingRemark)
                .setParameter(10, closingRemark == null ? "" : closingRemark)
                .setParameter(11, DateUtil.now())
                .setParameter(12, id)
                .executeUpdate());

            out.put("success", true);
            out.put("grossPnl", gross);
            out.put("charges", c.asMap());
            out.put("netPnl", net);
            out.put("message", "Closed " + str(r[3]) + " at " + exitPrice + ". Gross " + gross
                + ", charges " + c.total() + ", net " + net + ".");
        } catch (Exception e) {
            log.warn("[TradeJournal] Manual close failed: {}", e.getMessage());
            out.put("success", false);
            out.put("message", "Could not close the trade: " + e.getMessage());
        }
        return out;
    }

    /**
     * Open manual positions, marked to market.
     *
     * <p>Section 9 asks for manual positions to be trackable with a current price and unrealised
     * P&L, which needs a live price joined on at read time rather than a stored figure that ages.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> openManualPositions(Long userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            List<Object[]> rows = em.createNativeQuery(
                "SELECT id,trade_ref,symbol,sector,quantity,buy_price,stop_loss,target_price,"
              + "brokerage_charges,created_at,COALESCE(product_type,'DELIVERY'),user_remarks "
              + "FROM trading_journal WHERE user_id=? AND COALESCE(is_manual,0)=1 "
              + "AND COALESCE(trade_status,'')<>'CLOSED' AND COALESCE(sell_price,0)<=0 "
              + "ORDER BY id DESC")
                .setParameter(1, userId).getResultList();
            List<String> symbols = new ArrayList<>();
            for (Object[] r : rows) symbols.add(str(r[2]));
            Map<String, StockTrackerService.PricePoint> prices = symbols.isEmpty()
                ? Map.of() : tracker.executablePricePoints(symbols);

            double totalInvested = 0, totalUnrealized = 0;
            for (Object[] r : rows) {
                String sym = str(r[2]);
                int qty = (int) num(r[4]);
                double buy = num(r[5]);
                var pp = prices.get(tracker.normalizeSymbol(sym));
                double price = pp == null ? 0 : pp.price();
                var product = "INTRADAY".equalsIgnoreCase(str(r[10]))
                    ? BrokerageCalculator.Product.INTRADAY : BrokerageCalculator.Product.DELIVERY;
                double invested = round(buy * qty);
                double unreal = price > 0 ? round((price - buy) * qty) : 0;
                // What it will still cost to get out, so the figure on screen is the one the user
                // would actually keep.
                var exitLeg = price > 0
                    ? charges.charges(product, BrokerageCalculator.Side.SELL, "NSE", price, qty, true)
                    : null;
                double netUnreal = exitLeg == null ? unreal : round(unreal - exitLeg.total());

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r[0]);
                m.put("tradeRef", str(r[1]));
                m.put("symbol", sym);
                m.put("sector", str(r[3]));
                m.put("quantity", qty);
                m.put("buyPrice", buy);
                m.put("currentPrice", price > 0 ? price : null);
                m.put("stopLoss", num(r[6]));
                m.put("targetPrice", num(r[7]));
                m.put("entryCharges", num(r[8]));
                m.put("estimatedExitCharges", exitLeg == null ? null : exitLeg.total());
                m.put("invested", invested);
                m.put("unrealizedPnl", price > 0 ? unreal : null);
                m.put("netUnrealizedPnl", price > 0 ? netUnreal : null);
                m.put("unrealizedPct", price > 0 && invested > 0 ? round(unreal / invested * 100) : null);
                m.put("priceAsOf", pp == null ? null : pp.asOf());
                m.put("priceSource", pp == null ? null : pp.source());
                m.put("priceStale", pp != null && pp.asOf() != null && !DateUtil.today().equals(pp.asOf()));
                m.put("entryDate", str(r[9]));
                m.put("entryRemark", str(r[11]));
                m.put("productType", str(r[10]));
                list.add(m);
                totalInvested += invested;
                if (price > 0) totalUnrealized += netUnreal;
            }
            out.put("success", true);
            out.put("positions", list);
            out.put("count", list.size());
            out.put("totalInvested", round(totalInvested));
            out.put("totalNetUnrealized", round(totalUnrealized));
        } catch (Exception e) {
            log.warn("[TradeJournal] Open manual positions failed: {}", e.getMessage());
            out.put("success", false);
            out.put("positions", list);
            out.put("message", e.getMessage());
        }
        return out;
    }

    /**
     * Symbol suggestions for the manual-entry box.
     *
     * <p>Searches the instrument master, not the uploaded list: a trade can legitimately be
     * recorded for a stock that was never uploaded, and restricting the search to the tracked
     * universe would make the journal unable to describe trades the user actually took.
     *
     * <p>A prefix match sorts above a contained match, so typing "TAT" offers TATAMOTORS before
     * SWARAJTAT. Within each group the shorter symbol wins, which puts the parent company above
     * its longer-named relatives.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchSymbols(String query, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        String q = query == null ? "" : query.trim().toUpperCase(Locale.ROOT);
        if (q.length() < 1) return out;
        int cap = Math.max(1, Math.min(limit, 50));
        try {
            List<Object[]> rows = em.createNativeQuery(
                "SELECT sym, name, seg FROM ("
              + "  SELECT DISTINCT UPPER(TRIM(COALESCE(underlying_symbol,''))) sym, "
              + "         COALESCE(trading_symbol,'') name, COALESCE(exchange_segment,'') seg "
              + "  FROM dhan_instruments "
              + "  WHERE UPPER(COALESCE(exchange_segment,'')) = 'NSE_EQ' "
              + "    AND UPPER(COALESCE(instrument,'')) = 'EQUITY' "
              + "    AND UPPER(TRIM(COALESCE(underlying_symbol,''))) LIKE ? "
              + ") t ORDER BY CASE WHEN sym LIKE ? THEN 0 ELSE 1 END, LENGTH(sym), sym "
              + "LIMIT " + cap)
                .setParameter(1, "%" + q + "%")
                .setParameter(2, q + "%")
                .getResultList();
            for (Object[] r : rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("symbol", str(r[0]));
                m.put("name", str(r[1]));
                m.put("segment", str(r[2]));
                out.add(m);
            }
        } catch (Exception e) {
            log.debug("[TradeJournal] Symbol search skipped: {}", e.getMessage());
        }
        return out;
    }

    /** Delete a manual entry. Only manual rows: a system trade is a record of something that happened. */
    public Map<String, Object> deleteManual(Long userId, long id) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            final int[] n = {0};
            tx.executeWithoutResult(s -> n[0] = em.createNativeQuery(
                "DELETE FROM trading_journal WHERE id=? AND user_id=? AND COALESCE(is_manual,0)=1")
                .setParameter(1, id).setParameter(2, userId).executeUpdate());
            out.put("success", n[0] > 0);
            out.put("message", n[0] > 0 ? "Entry removed."
                : "Nothing removed. Only manual entries can be deleted - a system-generated trade "
                + "is a record of something that actually happened.");
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", e.getMessage());
        }
        return out;
    }

    /** The journal as CSV, with the same filters the screen is showing. */
    @SuppressWarnings("unchecked")
    public byte[] exportCsv(Long userId, String from, String to, String symbol,
                            String strategy, String mode, String source, String status) {
        Map<String, Object> data = list(userId, from, to, symbol, strategy, mode, source, status, 2000);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.getOrDefault("rows", List.of());
        StringBuilder csv = new StringBuilder("﻿");
        csv.append("TradeRakshak(TM) - Trade Journal\n");
        csv.append("Every trade: paper, real, manual, automated, stop-loss and trailing-stop exits.\n");
        csv.append("Charges are computed at Dhan's published rates. Net P&L is gross minus charges.\n");
        csv.append("Rows: ").append(rows.size()).append("\n\n");
        csv.append("Trade Ref,Trade Type,Account Type,Symbol,Sector,Strategy,Mode,Side,Product,Status,Qty,Filled,Pending,"
                 + "Entry Price,Exit Price,Stop Loss,Trailing SL,Target,Capital,Charges,Gross P&L,"
                 + "Net P&L,Return %,Exit Reason,Outcome,Manual,Broker Order ID,Signal At,"
                 + "Order Placed At,Executed At,Created At,Entry Remark,System Remarks\n");
        for (Map<String, Object> r : rows) {
            csv.append(q(r.get("tradeRef"))).append(',')
               .append(q(r.get("tradeTypeLabel"))).append(',')
               .append(q(r.get("accountTypeLabel"))).append(',')
               .append(q(r.get("symbol"))).append(',')
               .append(q(r.get("sector"))).append(',').append(q(r.get("strategyName"))).append(',')
               .append(q(r.get("tradingMode"))).append(',').append(q(r.get("tradeType"))).append(',')
               .append(q(r.get("productType"))).append(',').append(q(r.get("tradeStatus"))).append(',')
               .append(n(r.get("quantity"))).append(',').append(n(r.get("filledQty"))).append(',')
               .append(n(r.get("pendingQty"))).append(',')
               .append(n(r.get("buyPrice"))).append(',').append(n(r.get("sellPrice"))).append(',')
               .append(n(r.get("stopLoss"))).append(',').append(n(r.get("trailingStopLoss"))).append(',')
               .append(n(r.get("targetPrice"))).append(',').append(n(r.get("capitalUtilized"))).append(',')
               .append(n(r.get("charges"))).append(',').append(n(r.get("grossPnl"))).append(',')
               .append(n(r.get("netPnl"))).append(',').append(n(r.get("returnPct"))).append(',')
               .append(q(r.get("exitReason"))).append(',').append(q(r.get("outcome"))).append(',')
               .append(Boolean.TRUE.equals(r.get("isManual")) ? "Yes" : "No").append(',')
               .append(q(r.get("brokerOrderId"))).append(',').append(q(r.get("signalAt"))).append(',')
               .append(q(r.get("orderPlacedAt"))).append(',').append(q(r.get("executedAt"))).append(',')
               .append(q(r.get("createdAt"))).append(',').append(q(r.get("userRemarks"))).append(',')
               .append(q(r.get("systemRemarks"))).append('\n');
        }
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String toJson(Object o) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o); }
        catch (Exception e) { return null; }
    }

    private static String q(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        return s.contains(",") || s.contains("\"") || s.contains("\n")
            ? '"' + s.replace("\"", "\"\"").replace("\n", " ") + '"' : s;
    }
    private static String n(Object v) { return v == null ? "" : String.valueOf(v); }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static double num(Object o) {
        if (o instanceof Number x) return x.doubleValue();
        try { return o == null || String.valueOf(o).isBlank() ? 0 : Double.parseDouble(String.valueOf(o)); }
        catch (Exception e) { return 0; }
    }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
