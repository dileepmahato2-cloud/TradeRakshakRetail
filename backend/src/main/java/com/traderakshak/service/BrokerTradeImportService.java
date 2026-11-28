package com.traderakshak.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traderakshak.entity.User;
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
 * Imports the day's executed trades from the broker and reconciles them against the journal.
 *
 * <p>Specification section 9: "Where supported by the broker API, provide an option to import and
 * reconcile manually executed broker trades." Dhan supports it - {@code GET /v2/trades} returns
 * every trade filled today - and the case it solves is a real one: a trade placed in Dhan's own
 * app or web terminal never passes through this application at all, so the journal has no idea it
 * happened and every P&amp;L figure is short by it.
 *
 * <p><b>Preview first, always.</b> {@code dryRun} defaults to true and the caller has to ask for
 * the write. An import that reaches into the journal on a bare request is one accidental click
 * from inventing trades a user never took, and the trade book is the broker's view of the day -
 * not necessarily the whole story of a position opened earlier.
 *
 * <p><b>Matching.</b> A broker trade is considered already known when the journal holds a row with
 * the same {@code broker_order_id}. That is the only identifier both sides genuinely share: symbol
 * and quantity collide constantly, and matching on them would silently merge two real trades in
 * the same stock. Anything unmatched is offered as a NEW manual entry, flagged as broker-imported
 * so it stays distinguishable from both system trades and hand-typed ones.
 *
 * <p>Read-only at the broker. It never places, modifies or cancels anything.
 */
@Service
public class BrokerTradeImportService {

    private static final Logger log = LoggerFactory.getLogger(BrokerTradeImportService.class);
    private static final String DHAN_API = "https://api.dhan.co/v2";
    private static final int TIMEOUT_MS = 15000;

    @PersistenceContext
    private EntityManager em;

    private final BlockingHttpService http;
    private final ObjectMapper mapper;
    private final DhanBrokerService broker;
    private final BrokerageCalculator charges;
    private final DhanTokenStatus tokenStatus;
    private final TransactionTemplate tx;

    public BrokerTradeImportService(BlockingHttpService http, ObjectMapper mapper,
                                    DhanBrokerService broker, BrokerageCalculator charges,
                                    DhanTokenStatus tokenStatus, TransactionTemplate tx) {
        this.http = http;
        this.mapper = mapper;
        this.broker = broker;
        this.charges = charges;
        this.tokenStatus = tokenStatus;
        this.tx = tx;
    }

    /**
     * @param dryRun true (the default) returns what WOULD be imported and writes nothing.
     */
    public Map<String, Object> importTrades(User user, boolean dryRun) {
        return importTrades(user, dryRun, null);
    }

    /**
     * @param onlyOrderIds when given, only these broker order ids are written. Null or empty means
     *        every unmatched trade. Selective by design: the broker trade book is its view of the
     *        whole day, and some of those fills may belong to activity the user does not want
     *        mixed into this journal.
     */
    public Map<String, Object> importTrades(User user, boolean dryRun, List<String> onlyOrderIds) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        if (user == null) {
            out.put("success", false);
            out.put("message", "No user.");
            return out;
        }

        // Ask the stored expiry before spending a call Dhan will answer with an empty body.
        String expired = tokenStatus.expiredMessage();
        if (expired != null) {
            out.put("success", false);
            out.put("trades", rows);
            out.put("message", expired);
            return out;
        }

        DhanBrokerService.Credential cred = broker.activeDhanCredential(user);
        if (cred == null) {
            out.put("success", false);
            out.put("trades", rows);
            out.put("message", "No verified Dhan connection, so the broker's trade book cannot "
                + "be read. Connect and verify Dhan in Broker first.");
            return out;
        }

        JsonNode book;
        try {
            String body = http.get(DHAN_API + "/trades", Map.of(
                "Accept", "application/json",
                "Content-Type", "application/json",
                "access-token", cred.accessToken(),
                "client-id", cred.clientId()), TIMEOUT_MS);
            book = mapper.readTree(body);
        } catch (Exception e) {
            log.warn("[TradeImport] Could not read the Dhan trade book: {}", e.getMessage());
            out.put("success", false);
            out.put("trades", rows);
            out.put("message", "Could not read the broker's trade book: " + e.getMessage());
            return out;
        }
        if (book == null || !book.isArray()) {
            out.put("success", true);
            out.put("trades", rows);
            out.put("imported", 0);
            out.put("message", "The broker returned no trades for today.");
            return out;
        }

        List<String> knownOrderIds = knownBrokerOrderIds(user.getId());
        int imported = 0, alreadyKnown = 0;

        for (JsonNode t : book) {
            String orderId = text(t, "orderId");
            String symbol = text(t, "tradingSymbol").toUpperCase(Locale.ROOT);
            String side = text(t, "transactionType").toUpperCase(Locale.ROOT);
            int qty = intOf(t, "tradedQuantity");
            double price = dblOf(t, "tradedPrice");
            String product = text(t, "productType");
            String when = text(t, "exchangeTime").isBlank()
                ? text(t, "createTime") : text(t, "exchangeTime");

            boolean known = !orderId.isBlank() && knownOrderIds.contains(orderId);
            if (known) alreadyKnown++;

            // Charges computed here rather than taken from the broker: the trade book does not
            // carry them, and a journal row with zero charges reports a net P&L nobody received.
            var product0 = "INTRADAY".equalsIgnoreCase(product)
                ? BrokerageCalculator.Product.INTRADAY : BrokerageCalculator.Product.DELIVERY;
            double chargeTotal = qty > 0 && price > 0
                ? charges.charges(product0,
                    "SELL".equals(side) ? BrokerageCalculator.Side.SELL : BrokerageCalculator.Side.BUY,
                    "NSE", price, qty, "SELL".equals(side)).total()
                : 0;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("brokerOrderId", orderId);
            m.put("exchangeTradeId", text(t, "exchangeTradeId"));
            m.put("symbol", symbol);
            m.put("side", side);
            m.put("quantity", qty);
            m.put("price", price);
            m.put("productType", product0.name());
            m.put("tradedAt", when);
            m.put("estimatedCharges", round(chargeTotal));
            m.put("alreadyInJournal", known);
            rows.add(m);

            boolean picked = onlyOrderIds == null || onlyOrderIds.isEmpty()
                          || onlyOrderIds.contains(orderId);
            m.put("selectedForImport", picked);
            if (!dryRun && picked && !known && qty > 0 && price > 0 && !symbol.isBlank()) {
                if (writeImported(user, symbol, side, qty, price, product0.name(),
                                  orderId, when, chargeTotal, text(t, "exchangeTradeId"))) {
                    imported++;
                }
            }
        }

        out.put("success", true);
        out.put("dryRun", dryRun);
        out.put("trades", rows);
        out.put("brokerTradeCount", rows.size());
        out.put("alreadyInJournal", alreadyKnown);
        out.put("imported", imported);
        out.put("message", dryRun
            ? rows.size() + " broker trade(s) found, " + alreadyKnown + " already in the journal. "
              + "Nothing was written - this was a preview."
            : imported + " trade(s) imported, " + alreadyKnown + " already present and skipped.");
        if (!dryRun && imported > 0) {
            log.info("[TradeImport] Imported {} broker trade(s) for user {}", imported, user.getId());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> knownBrokerOrderIds(Long userId) {
        try {
            List<Object> ids = em.createNativeQuery(
                "SELECT DISTINCT broker_order_id FROM trading_journal "
              + "WHERE user_id=? AND broker_order_id IS NOT NULL AND broker_order_id <> ''")
                .setParameter(1, userId).getResultList();
            List<String> out = new ArrayList<>();
            for (Object o : ids) out.add(String.valueOf(o).trim());
            return out;
        } catch (Exception e) {
            // An empty list means nothing is treated as known, so a repeated import would offer
            // the same trades again rather than silently writing duplicates - the safe direction,
            // because the caller sees them in the preview before anything is written.
            log.debug("[TradeImport] Known-order lookup skipped: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Writes one imported trade as a REAL journal row.
     *
     * <p>Marked {@code is_manual = 1} and {@code strategy_key = 'broker_import'}: it was not
     * generated by a strategy in this application, and section 9 requires imported trades to stay
     * distinguishable. The idempotency key is derived from the broker's own order id, so the
     * unique index refuses a second import of the same trade even if the known-id lookup failed.
     */
    private boolean writeImported(User user, String symbol, String side, int qty, double price,
                                  String product, String orderId, String when, double chargeTotal,
                                  String exchangeTradeId) {
        try {
            String ref = "TJB-" + user.getId() + "-" + (orderId.isBlank()
                ? Math.abs((symbol + qty + price).hashCode()) : orderId);
            String idem = orderId.isBlank() ? null : "BRK:" + user.getId() + ":" + orderId;
            String now = DateUtil.now();
            final int[] n = {0};
            tx.executeWithoutResult(s -> n[0] = em.createNativeQuery(
                "INSERT INTO trading_journal(trade_ref,user_id,institution_id,user_name,strategy_key,"
              + "strategy_name,trading_mode,trade_type,stock_name,symbol,quantity,buy_price,"
              + "sell_price,brokerage_charges,gross_pnl,net_profit_loss,outcome,system_remarks,"
              + "created_at,updated_at,trade_status,filled_qty,pending_qty,broker_order_id,"
              + "is_manual,product_type,signal_at,order_placed_at,executed_at,idempotency_key,"
              // Section 6 items 9 and 4: when this was reconciled, and that it came from the
              // broker's own trade book rather than from anything this application placed. The
              // trade book is the primary source for an executed trade, so it owns the reference.
              + "reconciled_at,reconciliation_source,source_reference) "
              + "VALUES(?,?,?,?,'broker_import','Broker Import','REAL',?,?,?,?,?,?,?,0,0,?,?,?,?,"
              + "'FILLED',?,0,?,1,?,?,?,?,?,now(),'DHAN_TRADE_BOOK',?) "
              + "ON CONFLICT (idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING")
                .setParameter(1, ref).setParameter(2, user.getId())
                .setParameter(3, user.getInstitutionId()).setParameter(4, user.getName())
                .setParameter(5, side)
                .setParameter(6, symbol).setParameter(7, symbol)
                .setParameter(8, qty)
                .setParameter(9, "SELL".equals(side) ? 0 : price)
                .setParameter(10, "SELL".equals(side) ? price : 0)
                .setParameter(11, round(chargeTotal))
                .setParameter(12, "Imported")
                .setParameter(13, "Imported from the Dhan trade book. Charges calculated at "
                    + "Dhan's published rates; the trade book does not carry them.")
                .setParameter(14, now).setParameter(15, now)
                .setParameter(16, qty)
                .setParameter(17, orderId)
                .setParameter(18, product)
                .setParameter(19, when).setParameter(20, when).setParameter(21, when)
                .setParameter(22, idem)
                // The exchange's own trade id where the broker supplies one, falling back to the
                // order id. Prefixed so the two can never be mistaken for each other, and unique
                // per account - ux_tj_source_reference refuses a second import of the same
                // execution at the database, not merely in this method's own matching.
                .setParameter(23, exchangeTradeId != null && !exchangeTradeId.isBlank()
                    ? "TRADE:" + exchangeTradeId
                    : (orderId.isBlank() ? null : "ORDER:" + orderId))
                .executeUpdate());
            return n[0] > 0;
        } catch (Exception e) {
            log.warn("[TradeImport] Could not import {} {}: {}", side, symbol, e.getMessage());
            return false;
        }
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return v == null || v.isNull() ? "" : v.asText("").trim();
    }
    private static int intOf(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return v == null || v.isNull() ? 0 : v.asInt(0);
    }
    private static double dblOf(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return v == null || v.isNull() ? 0 : v.asDouble(0);
    }
    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
