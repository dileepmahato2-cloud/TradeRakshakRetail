package com.traderakshak.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traderakshak.entity.User;
import com.traderakshak.util.DateUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settles what actually happened to real orders after they were sent.
 *
 * <p><b>The gap this closes.</b> {@code submitRealOrder} posted to Dhan, stored the response and
 * stopped. An order that came back PENDING stayed PENDING in the journal for ever, whatever the
 * exchange went on to do with it - so a rejected order read as live, a partial fill read as
 * complete, and the position sizes in the journal were whatever had been *requested* rather than
 * whatever had been *filled*.
 *
 * <p><b>One call, not one per order.</b> Dhan's {@code GET /orders} returns the whole day's order
 * book in a single response, so a sweep costs one request however many orders are open. Polling
 * {@code GET /orders/{id}} per order would multiply that by the order count for no extra
 * information.
 *
 * <p><b>The lost-response case, which is the one that actually frightens people.</b> If the HTTP
 * call placing an order times out, the order may well have reached Dhan - the request succeeded and
 * only the reply was lost. The journal then holds a row with no {@code broker_order_id} and no way
 * to tell whether money moved. Every order is therefore sent with a deterministic
 * {@code correlationId} (see {@link BrokerOrderStatus#correlationId}), and this service adopts the
 * real order id by matching on it. Without that, the only recovery is a human reading the Dhan
 * order book.
 *
 * <p>Read-only against the broker. It never places, modifies or cancels anything.
 */
@Service
public class OrderReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(OrderReconciliationService.class);
    private static final String DHAN_API = "https://api.dhan.co/v2";
    private static final int TIMEOUT_MS = 15000;

    @PersistenceContext
    private EntityManager em;

    private final MarketCalendarService marketCalendar;

    private final BlockingHttpService http;
    private final ObjectMapper mapper;
    private final DhanBrokerService broker;
    private final AppConfigService appConfig;
    private final TransactionTemplate tx;

    public OrderReconciliationService(BlockingHttpService http, ObjectMapper mapper,
                                      DhanBrokerService broker, AppConfigService appConfig,
                                      TransactionTemplate tx, MarketCalendarService marketCalendar) {
        this.http = http;
        this.mapper = mapper;
        this.broker = broker;
        this.appConfig = appConfig;
        this.tx = tx;
        this.marketCalendar = marketCalendar;
    }

    public boolean isEnabled() {
        return appConfig.getBoolean("reconciliation.enabled", true);
    }

    /**
     * Reconcile every non-terminal real order for one user against Dhan's order book.
     *
     * @return what changed, per order, so the result is auditable rather than a count.
     */
    public Map<String, Object> reconcile(User user) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> changes = new ArrayList<>();
        if (user == null) {
            out.put("success", false);
            out.put("message", "No user.");
            return out;
        }
        try {
            List<Object[]> openRows = pendingRealOrders(user.getId());
            if (openRows.isEmpty()) {
                out.put("success", true);
                out.put("examined", 0);
                out.put("changes", List.of());
                out.put("message", "No real orders are awaiting settlement.");
                return out;
            }

            DhanBrokerService.Credential cred = broker.activeDhanCredential(user);
            if (cred == null) {
                // Not an error to shout about: a user with no live broker link simply has nothing
                // to reconcile against, and the rows stay as they are rather than being guessed at.
                out.put("success", false);
                out.put("examined", openRows.size());
                out.put("changes", List.of());
                out.put("message", "No verified Dhan connection, so the broker's order book "
                    + "cannot be read. " + openRows.size() + " order(s) remain unsettled.");
                return out;
            }

            Map<String, JsonNode> byOrderId = new LinkedHashMap<>();
            Map<String, JsonNode> byCorrelation = new LinkedHashMap<>();
            JsonNode book = fetchOrderBook(cred);
            if (book == null || !book.isArray()) {
                out.put("success", false);
                out.put("examined", openRows.size());
                out.put("changes", List.of());
                out.put("message", "Dhan returned no order book, so nothing was changed.");
                return out;
            }
            for (JsonNode o : book) {
                String oid = text(o, "orderId");
                String cid = text(o, "correlationId");
                if (!oid.isBlank()) byOrderId.put(oid, o);
                if (!cid.isBlank()) byCorrelation.put(cid, o);
            }

            for (Object[] r : openRows) {
                long id = lng(r[0]);
                String symbol = str(r[1]);
                int orderQty = (int) num(r[2]);
                String brokerOrderId = str(r[3]);
                String idemKey = str(r[4]);
                String tradeDate = str(r[5]);
                String currentStatus = str(r[6]);

                JsonNode o = null;
                String matchedBy = "";
                if (!brokerOrderId.isBlank()) {
                    o = byOrderId.get(brokerOrderId);
                    matchedBy = "orderId";
                }
                if (o == null) {
                    // The lost-response path. The id is derived from the stored idempotency key, so
                    // it is the same one that was sent.
                    String cid = BrokerOrderStatus.correlationId(user.getId(), tradeDate, idemKey);
                    o = byCorrelation.get(cid);
                    matchedBy = "correlationId";
                    if (o == null) {
                        o = fetchByCorrelation(cred, cid);
                        matchedBy = "correlationId (direct)";
                    }
                }
                if (o == null) {
                    // Absent from today's book. Said plainly rather than marked terminal: the most
                    // likely reasons are that the order never reached Dhan, or that it belongs to
                    // an earlier day, and neither justifies writing an outcome we did not observe.
                    changes.add(note(id, symbol, currentStatus, currentStatus,
                        "Not present in today's Dhan order book. Left unchanged - it may never "
                      + "have reached the broker, or it may belong to an earlier session."));
                    continue;
                }

                String brokerStatus = text(o, "orderStatus");
                int filled = intOf(o, "filledQty");
                int remaining = intOf(o, "remainingQuantity");
                double avgPrice = dblOf(o, "averageTradedPrice");
                String newOrderId = text(o, "orderId");
                String err = text(o, "omsErrorDescription");
                if (err.isBlank()) err = text(o, "omsErrorCode");

                // Trust the broker's remaining quantity when it gives one; otherwise derive it.
                int pending = remaining > 0 ? remaining : Math.max(0, orderQty - filled);
                String tradeStatus = BrokerOrderStatus.tradeStatus(brokerStatus, orderQty, filled);
                if (tradeStatus.equals(currentStatus) && brokerOrderId.equals(newOrderId)) continue;

                applyUpdate(id, newOrderId, brokerStatus, tradeStatus, filled, pending, avgPrice, err);
                changes.add(note(id, symbol, currentStatus, tradeStatus,
                    "Matched by " + matchedBy + ". Dhan says " + brokerStatus
                  + "; filled " + filled + " of " + orderQty + ", " + pending + " pending"
                  + (avgPrice > 0 ? " at an average of " + avgPrice : "")
                  + (err.isBlank() ? "" : ". Broker reason: " + err)));
            }

            out.put("success", true);
            out.put("examined", openRows.size());
            out.put("changes", changes);
            out.put("message", changes.isEmpty()
                ? "All " + openRows.size() + " order(s) already matched the broker's order book."
                : changes.size() + " of " + openRows.size() + " order(s) updated.");
            if (!changes.isEmpty()) {
                log.info("[Reconcile] Updated {} of {} real order(s) for user {}",
                    changes.size(), openRows.size(), user.getId());
            }
        } catch (Exception e) {
            log.warn("[Reconcile] Failed: {}", e.getMessage());
            out.put("success", false);
            out.put("changes", changes);
            out.put("message", "Reconciliation failed, so nothing was assumed: " + e.getMessage());
        }
        return out;
    }

    /**
     * Real orders that have not reached a settled state.
     *
     * <p>Includes rows with no {@code broker_order_id} at all, because those are exactly the
     * lost-response cases that most need resolving - excluding them would make the one situation
     * the correlation id exists for unreachable.
     */
    @SuppressWarnings("unchecked")
    private List<Object[]> pendingRealOrders(Long userId) {
        try {
            return em.createNativeQuery(
                "SELECT id,symbol,COALESCE(quantity,0),COALESCE(broker_order_id,''),"
              + "COALESCE(idempotency_key,''),COALESCE(SUBSTRING(created_at,1,10),''),"
              + "COALESCE(trade_status,'PLACED') FROM trading_journal "
              + "WHERE user_id=? AND UPPER(COALESCE(trading_mode,''))='REAL' "
              + "AND COALESCE(trade_status,'PLACED') NOT IN ('FILLED','REJECTED','CANCELLED','EXPIRED') "
              + "ORDER BY id DESC LIMIT 200")
                .setParameter(1, userId)
                .getResultList();
        } catch (Exception e) {
            log.debug("[Reconcile] Pending order lookup skipped: {}", e.getMessage());
            return List.of();
        }
    }

    private void applyUpdate(long id, String orderId, String brokerStatus, String tradeStatus,
                             int filled, int pending, double avgPrice, String err) {
        tx.executeWithoutResult(s -> em.createNativeQuery(
            "UPDATE trading_journal SET broker_order_id=COALESCE(NULLIF(?,''),broker_order_id), "
          + "trade_status=?, filled_qty=?, pending_qty=?, "
          // Only stamped when something actually executed, so executed_at means "this filled"
          // rather than "we last looked at it".
          + "executed_at=CASE WHEN ? > 0 AND executed_at IS NULL THEN ? ELSE executed_at END, "
          + "sell_price=CASE WHEN UPPER(COALESCE(trade_type,''))='SELL' AND ? > 0 THEN ? ELSE sell_price END, "
          + "buy_price=CASE WHEN UPPER(COALESCE(trade_type,''))<>'SELL' AND ? > 0 THEN ? ELSE buy_price END, "
          + "system_remarks=CASE WHEN ?='' THEN system_remarks ELSE ? END, updated_at=?, "
          // When this row was last checked against the broker, and against what. Distinct from
          // updated_at, which moves for any reason: this answers "has this been reconciled, and
          // how recently", which is what a repeated sync and a reader both need.
          + "reconciled_at=now(), reconciliation_source='DHAN_ORDER_BOOK', "
          // The order book is the authority for an order's STATUS. The trade book is the
          // authority for a fill, and sets source_reference itself - so this does not overwrite
          // one that is already there.
          + "source_reference=COALESCE(source_reference, NULLIF(?,'')) "
          + "WHERE id=?")
            .setParameter(1, orderId == null ? "" : orderId)
            .setParameter(2, tradeStatus)
            .setParameter(3, filled)
            .setParameter(4, pending)
            .setParameter(5, filled).setParameter(6, DateUtil.now())
            // The average traded price is what was actually paid, which is not always what was
            // requested. Overwriting the requested price with it is the point: a journal that
            // keeps the requested price reports a P&L nobody received.
            .setParameter(7, avgPrice).setParameter(8, avgPrice)
            .setParameter(9, avgPrice).setParameter(10, avgPrice)
            .setParameter(11, err == null ? "" : err).setParameter(12, err == null ? "" : err)
            .setParameter(13, DateUtil.now())
            // The broker order id doubles as the source reference when nothing more specific is
            // known; prefixed so a reader can tell which book it came from.
            .setParameter(14, orderId == null || orderId.isBlank() ? "" : "ORDER:" + orderId)
            .setParameter(15, id)
            .executeUpdate());
    }

    private JsonNode fetchOrderBook(DhanBrokerService.Credential cred) {
        try {
            String body = http.get(DHAN_API + "/orders", headers(cred), TIMEOUT_MS);
            return mapper.readTree(body);
        } catch (Exception e) {
            log.warn("[Reconcile] Could not read the Dhan order book: {}", e.getMessage());
            return null;
        }
    }

    private JsonNode fetchByCorrelation(DhanBrokerService.Credential cred, String correlationId) {
        try {
            String body = http.get(DHAN_API + "/orders/external/" + correlationId,
                                   headers(cred), TIMEOUT_MS);
            JsonNode n = mapper.readTree(body);
            return n != null && n.has("orderId") ? n : null;
        } catch (Exception e) {
            // A 404 here is the normal answer for an order that never reached Dhan, so this is
            // debug rather than a warning.
            log.debug("[Reconcile] No order for correlation id {}: {}", correlationId, e.getMessage());
            return null;
        }
    }

    private Map<String, String> headers(DhanBrokerService.Credential cred) {
        return Map.of(
            "Accept", "application/json",
            "Content-Type", "application/json",
            "access-token", cred.accessToken(),
            "client-id", cred.clientId());
    }

    /**
     * Sweep every five minutes during and just after market hours.
     *
     * <p>Until 16:00 rather than 15:30: an order placed near the close can settle after it, and a
     * reconciler that stops at the bell leaves the day's last orders unsettled overnight.
     *
     * <p>Per user, because each has their own broker credential and Dhan's order book is scoped to
     * one client id - there is no account-wide view to read instead.
     */
    @Scheduled(cron = "0 */5 9-16 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledSweep() {
        // Outbound calls are confined to the automation window: a trading day between 08:00
        // and 16:00 IST. Outside it - overnight, weekends, exchange holidays - this stands down.
        if (!marketCalendar.isAutomationWindowOpen()) {
            log.debug("[{}] Skipped: {}.", "OrderRecon", marketCalendar.automationWindowReason());
            return;
        }
        if (!isEnabled()) return;
        try {
            // One column, so Hibernate hands back the values themselves - List<Long> - not
            // List<Object[]>. Iterating them as Object[] threw ClassCastException on the first
            // row of every sweep, which the catch below logged as a warning and swallowed. The
            // reconciliation sweep had therefore never run: 5 failures every 5 minutes in the
            // log, and no rejected or part-filled order was ever settled.
            @SuppressWarnings("unchecked")
            List<Object> users = em.createNativeQuery(
                "SELECT DISTINCT user_id FROM trading_journal "
              + "WHERE UPPER(COALESCE(trading_mode,''))='REAL' "
              + "AND COALESCE(trade_status,'PLACED') NOT IN ('FILLED','REJECTED','CANCELLED','EXPIRED') "
              + "LIMIT 50").getResultList();
            for (Object row : users) {
                // Defensive on the shape: a column list that grows later would start arriving
                // as Object[] again, and this should keep working rather than start throwing.
                Object cell = row instanceof Object[] arr ? (arr.length > 0 ? arr[0] : null) : row;
                Long uid = cell instanceof Number n ? n.longValue() : null;
                if (uid == null) continue;
                User u = em.find(User.class, uid);
                if (u == null) continue;
                reconcile(u);
            }
        } catch (Exception e) {
            log.warn("[Reconcile] Scheduled sweep failed: {}", e.getMessage());
        }
    }

    private static Map<String, Object> note(long id, String symbol, String from, String to, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("journalId", id);
        m.put("symbol", symbol);
        m.put("from", from);
        m.put("to", to);
        m.put("detail", detail);
        return m;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? "" : v.asText("").trim();
    }
    private static int intOf(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? 0 : v.asInt(0);
    }
    private static double dblOf(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? 0 : v.asDouble(0);
    }
    private static long lng(Object o) { return o instanceof Number n ? n.longValue() : 0; }
    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
}
