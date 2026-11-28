package com.traderakshak.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.entity.User;
import com.traderakshak.service.AppConfigService;
import com.traderakshak.service.BrokerFundsService;
import com.traderakshak.service.DemoAccountService;
import com.traderakshak.service.AutoStrategySourceService;
import com.traderakshak.service.StockTrackerService;
import com.traderakshak.service.BlockingHttpService;
import com.traderakshak.service.CapitalAllocationService;
import com.traderakshak.service.BrokerageCalculator;
import com.traderakshak.service.StrategyAuditService;
import com.traderakshak.service.BrokerTradeImportService;
import com.traderakshak.service.VolumeBreakoutAutoTradeService;
import com.traderakshak.service.OrderReconciliationService;
import com.traderakshak.service.BrokerOrderStatus;
import com.traderakshak.service.PositionExitRules;
import com.traderakshak.service.PositionMonitorService;
import com.traderakshak.service.PnlDashboardService;
import com.traderakshak.service.TradingGateClient;
import com.traderakshak.util.DateUtil;
import com.traderakshak.util.UserMessage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/trading")
public class TradingController {

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(TradingController.class);
    private static final String DHAN_API = "https://api.dhan.co/v2";

    @PersistenceContext
    private EntityManager em;
    private final DemoAccountService demoAccount;
    private final BrokerFundsService brokerFunds;
    private final BlockingHttpService http;
    private final ObjectMapper mapper;
    private final TradingGateClient tradingGate;
    private final AppConfigService appConfig;
    private final StockTrackerService stockTracker;
    private final BrokerageCalculator brokerage;
    private final CapitalAllocationService allocation;
    private final PnlDashboardService pnl;
    private final PositionMonitorService positionMonitor;
    private final OrderReconciliationService reconciliation;
    private final VolumeBreakoutAutoTradeService autoTrade;
    private final AutoStrategySourceService     autoSources;
    private final BrokerTradeImportService tradeImport;
    private final StrategyAuditService strategyAudit;

    public TradingController(BlockingHttpService http, ObjectMapper mapper, TradingGateClient tradingGate,
                             AppConfigService appConfig, StockTrackerService stockTracker,
                             BrokerageCalculator brokerage, CapitalAllocationService allocation,
                             DemoAccountService demoAccount,
                             BrokerFundsService brokerFunds,
                             PnlDashboardService pnl, PositionMonitorService positionMonitor,
                             OrderReconciliationService reconciliation,
                             VolumeBreakoutAutoTradeService autoTrade,
                             AutoStrategySourceService autoSources,
                             BrokerTradeImportService tradeImport,
                             StrategyAuditService strategyAudit) {
        this.tradeImport = tradeImport;
        this.strategyAudit = strategyAudit;
        this.pnl = pnl;
        this.positionMonitor = positionMonitor;
        this.reconciliation = reconciliation;
        this.autoTrade = autoTrade;
        this.autoSources = autoSources;
        this.tradingGate = tradingGate;
        this.http = http;
        this.mapper = mapper;
        this.appConfig = appConfig;
        this.stockTracker = stockTracker;
        this.brokerage = brokerage;
        this.allocation = allocation;
        this.demoAccount = demoAccount;
        this.brokerFunds = brokerFunds;
    }

    /**
     * Mark still-open journal rows to market, in memory, on every read.
     *
     * <p>A journal row is written once when the order goes out and never touched again - there
     * is no UPDATE against trading_journal anywhere - so net_profit_loss stayed at the 0.00 it
     * was inserted with and the P&amp;L column never moved, however far the price did. All ten
     * rows from 7 Sep 2026 read INR 0 with Win Rate 0%.
     *
     * <p>Computed here rather than written back, because a stored P&amp;L is stale the moment
     * the next tick lands and would have to be re-saved forever to stay honest. The stored
     * column stays the record of what was realised; this is the live view of it.
     *
     * <p>Only open BUY rows are touched. A row that has a sell price is finished and keeps the
     * realised number it was closed with.
     */
    private void applyLivePnl(User user, List<Map<String, Object>> rows) {
        List<String> open = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (isOpenBuy(r)) open.add(text(r.get("symbol")));
        }
        if (open.isEmpty()) return;
        Map<String, Double> live;
        try {
            live = stockTracker.resolveCurrentPricesBatch(open, user.getInstitutionId());
        } catch (Exception e) {
            return;   // No feed: leave the stored numbers rather than showing invented ones.
        }
        for (Map<String, Object> r : rows) {
            if (!isOpenBuy(r)) continue;
            // Keyed the way resolveCurrentPricesBatch keys it - BOSCH-HCIL keeps its hyphen,
            // so anything that strips punctuation here silently finds nothing.
            Double p = live.get(stockTracker.normalizeSymbol(text(r.get("symbol"))));
            double price = p == null ? 0 : p;
            if (price <= 0) continue;
            double buy = dbl(r.get("buyPrice"), 0);
            int qty = integer(r.get("quantity"), 0);
            r.put("currentPrice", round(price));
            r.put("netProfitLoss", round((price - buy) * qty));
            r.put("returnPct", buy > 0 ? round((price - buy) / buy * 100) : 0);
            r.put("livePnl", true);
        }
    }

    /** An order that was bought and not yet sold - the only kind with an unrealised P&amp;L. */
    private boolean isOpenBuy(Map<String, Object> r) {
        return "BUY".equalsIgnoreCase(text(r.get("tradeType")))
            && dbl(r.get("buyPrice"), 0) > 0
            && dbl(r.get("sellPrice"), 0) <= 0;
    }

    /**
     * Fill stop loss, trailing stop and target from Application Settings when the caller did
     * not send them.
     *
     * <p>"Buy All 10" on Today top 10 Ranking Stocks posts allocation and timing only - the
     * tracker has no risk fields on screen - so {@link #orderCalculation} received no
     * percentages and wrote 0.00 into stop_loss, trailing_stop_loss and target_price on all ten
     * journal rows. On 7 Sep 2026 every row read 0.00 for the three of them.
     *
     * <p>The numbers come from trading.default_stop_loss_pct / _target_pct, which were seeded
     * but read by nothing, so the values on the Settings screen had no effect anywhere. Reading
     * them here keeps one place to change them rather than a constant compiled into this class.
     * Anything the caller does send wins, so the Trading desk form is untouched.
     */
    private void applyDefaultRiskPercentages(Map<String, Object> req) {
        if (dbl(req.get("stopLossPct"), 0) <= 0)
            req.put("stopLossPct", appConfig.getDouble("trading.default_stop_loss_pct", 5.0));
        if (dbl(req.get("targetPct"), 0) <= 0)
            req.put("targetPct", appConfig.getDouble("trading.default_target_pct", 10.0));
        if (dbl(req.get("trailingStopLossPct"), 0) <= 0)
            req.put("trailingStopLossPct", appConfig.getDouble("trading.default_trailing_stop_loss_pct", 2.0));
    }

    /**
     * The real account's funds, from the broker.
     *
     * <p>Always answers. When the broker cannot be reached it returns the last successful
     * reading with its timestamp and {@code stale: true}, rather than a zero that would read as
     * an empty account.
     */
    @GetMapping("/broker-funds")
    public ResponseEntity<ApiResponse<Map<String, Object>>> brokerFunds(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(brokerFunds.funds(user)));
    }

    // ---- Demo (paper) account -------------------------------------------------------------
    //
    // Paper trading had no balance: only a deployment cap that nothing debited and nothing
    // persisted, so a paper strategy could spend the same rupee every session. These expose the
    // virtual account that now stands behind it.

    /** The demo account as the Filters panel shows it: balance, buying power, opening balance. */
    @GetMapping("/demo-account")
    public ResponseEntity<ApiResponse<Map<String, Object>>> demoAccount(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            demoAccount.summary(user.getId(), user.getInstitutionId())));
    }

    /** Every movement on the demo account, newest first, with the balance after each. */
    @GetMapping("/demo-account/ledger")
    public ResponseEntity<ApiResponse<Map<String, Object>>> demoAccountLedger(
            @RequestParam(defaultValue = "100") int limit,
            @AuthenticationPrincipal User user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entries", demoAccount.ledger(user.getId(), user.getInstitutionId(), limit));
        // The stored balance beside the ledger total, so a drift between them is visible rather
        // than quietly corrected.
        out.put("reconciliation", demoAccount.recomputeBalance(user.getId(), user.getInstitutionId()));
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<Map<String, Object>>> dashboard(@AuthenticationPrincipal User user) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", portfolioSummary(user));
        data.put("strategies", strategies(user));
        data.put("autoSettings", autoSettings(user));
        data.put("brokers", brokers(user));
        data.put("top10", currentTop10(user));
        data.put("openPositions", paperTrades(user, "open", 20));
        data.put("closedPositions", paperTrades(user, "closed", 20));
        data.put("auditLogs", auditLogs(user, 25));
        data.put("alerts", latestAlerts(user));
        data.put("checklists", checklistTemplates(user));
        data.put("journal", journalRows(user, "", "", "", 25));
        data.put("journalAnalytics", journalAnalytics(user));
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    @GetMapping("/checklists")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getChecklists(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(checklistTemplates(user)));
    }

    @PostMapping("/checklists")
    @Transactional
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> saveChecklist(@RequestBody Map<String, Object> req,
                                                                                @AuthenticationPrincipal User user) {
        Long id = lng(req.get("id"));
        String name = text(req.get("templateName"));
        if (name.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("CHECKLIST-NAME", "Checklist template name is required."));
        }
        if (id == null) {
            em.createNativeQuery("INSERT INTO trading_checklist_templates(user_id,institution_id,template_name,strategy_key,strategy_name,trade_type,applies_to,enabled,override_allowed,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)")
                .setParameter(1, user.getId()).setParameter(2, user.getInstitutionId()).setParameter(3, name)
                .setParameter(4, textOr(req.get("strategyKey"), "stock_tracker")).setParameter(5, textOr(req.get("strategyName"), "Stock Tracker"))
                .setParameter(6, upper(req.get("tradeType"), "ALL")).setParameter(7, textOr(req.get("appliesTo"), "ALL"))
                .setParameter(8, bool(req.get("enabled")) ? 1 : 0).setParameter(9, bool(req.get("overrideAllowed")) ? 1 : 0)
                .setParameter(10, DateUtil.now()).setParameter(11, DateUtil.now()).executeUpdate();
            id = lng(em.createNativeQuery("SELECT id FROM trading_checklist_templates WHERE user_id=? ORDER BY id DESC LIMIT 1").setParameter(1, user.getId()).getSingleResult());
        } else {
            em.createNativeQuery("UPDATE trading_checklist_templates SET template_name=?,strategy_key=?,strategy_name=?,trade_type=?,applies_to=?,enabled=?,override_allowed=?,updated_at=? WHERE id=? AND institution_id=?")
                .setParameter(1, name).setParameter(2, textOr(req.get("strategyKey"), "stock_tracker")).setParameter(3, textOr(req.get("strategyName"), "Stock Tracker"))
                .setParameter(4, upper(req.get("tradeType"), "ALL")).setParameter(5, textOr(req.get("appliesTo"), "ALL"))
                .setParameter(6, bool(req.get("enabled")) ? 1 : 0).setParameter(7, bool(req.get("overrideAllowed")) ? 1 : 0)
                .setParameter(8, DateUtil.now()).setParameter(9, id).setParameter(10, user.getInstitutionId()).executeUpdate();
            em.createNativeQuery("DELETE FROM trading_checklist_items WHERE template_id=?").setParameter(1, id).executeUpdate();
        }
        List<Map<String, Object>> items = asMapList(req.get("items"));
        int order = 1;
        for (Map<String, Object> item : items) {
            String itemText = text(item.get("itemText"));
            if (itemText.isBlank()) continue;
            em.createNativeQuery("INSERT INTO trading_checklist_items(template_id,item_text,mandatory,display_order) VALUES(?,?,?,?)")
                .setParameter(1, id).setParameter(2, itemText).setParameter(3, bool(item.get("mandatory")) ? 1 : 0).setParameter(4, order++).executeUpdate();
        }
        audit(user, textOr(req.get("strategyKey"), "stock_tracker"), textOr(req.get("strategyName"), "Stock Tracker"), "CONFIG", "CHECKLIST_SAVED", "", "", "success", "Trading checklist saved.", "", "", req);
        return ResponseEntity.ok(ApiResponse.ok("Checklist saved.", checklistTemplates(user)));
    }

    @PutMapping("/checklists/{id}/toggle")
    @Transactional
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> toggleChecklist(@PathVariable Long id,
                                                                                  @RequestBody Map<String, Object> req,
                                                                                  @AuthenticationPrincipal User user) {
        em.createNativeQuery("UPDATE trading_checklist_templates SET enabled=?,updated_at=? WHERE id=? AND institution_id=?")
            .setParameter(1, bool(req.get("enabled")) ? 1 : 0).setParameter(2, DateUtil.now()).setParameter(3, id).setParameter(4, user.getInstitutionId()).executeUpdate();
        return ResponseEntity.ok(ApiResponse.ok("Checklist status updated.", checklistTemplates(user)));
    }

    @DeleteMapping("/checklists/{id}")
    @Transactional
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> deleteChecklist(@PathVariable Long id,
                                                                                  @AuthenticationPrincipal User user) {
        em.createNativeQuery("DELETE FROM trading_checklist_templates WHERE id=? AND institution_id=?")
            .setParameter(1, id).setParameter(2, user.getInstitutionId()).executeUpdate();
        return ResponseEntity.ok(ApiResponse.ok("Checklist deleted.", checklistTemplates(user)));
    }

    @PostMapping("/checklists/validate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> validateChecklist(@RequestBody Map<String, Object> req,
                                                                              @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(checklistValidation(req, user)));
    }

    @GetMapping("/journal")
    public ResponseEntity<ApiResponse<Map<String, Object>>> journal(@RequestParam(defaultValue = "") String search,
                                                                    @RequestParam(defaultValue = "") String strategy,
                                                                    @RequestParam(defaultValue = "") String outcome,
                                                                    @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "rows", journalRows(user, search, strategy, outcome, 200),
            "analytics", journalAnalytics(user)
        )));
    }

    @GetMapping("/journal/export")
    public ResponseEntity<byte[]> journalExport(@AuthenticationPrincipal User user) {
        StringBuilder sb = new StringBuilder("trade_id,order_id,date_time,user,strategy,mode,type,symbol,quantity,buy_price,sell_price,stop_loss,trailing_stop_loss,target,capital,margin,brokerage,net_pnl,outcome,exit_reason,system_remarks,user_remarks\n");
        for (Map<String, Object> row : journalRows(user, "", "", "", 10000)) {
            sb.append(csv(row.get("tradeRef"))).append(',').append(csv(row.get("orderId"))).append(',').append(csv(row.get("createdAt"))).append(',')
                .append(csv(row.get("userName"))).append(',').append(csv(row.get("strategyName"))).append(',').append(csv(row.get("tradingMode"))).append(',')
                .append(csv(row.get("tradeType"))).append(',').append(csv(row.get("symbol"))).append(',').append(csv(row.get("quantity"))).append(',')
                .append(csv(row.get("buyPrice"))).append(',').append(csv(row.get("sellPrice"))).append(',').append(csv(row.get("stopLoss"))).append(',')
                .append(csv(row.get("trailingStopLoss"))).append(',').append(csv(row.get("targetPrice"))).append(',').append(csv(row.get("capitalUtilized"))).append(',')
                .append(csv(row.get("marginUtilized"))).append(',').append(csv(row.get("brokerageCharges"))).append(',').append(csv(row.get("netProfitLoss"))).append(',')
                .append(csv(row.get("outcome"))).append(',').append(csv(row.get("exitReason"))).append(',').append(csv(row.get("systemRemarks"))).append(',')
                .append(csv(row.get("userRemarks"))).append('\n');
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDisposition(ContentDisposition.attachment().filename("trading_journal.csv").build());
        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    /**
     * Equal-split allocation preview for a selected set of stocks.
     *
     * <p>Called on every tick and untick, and again immediately before an order is submitted. It
     * is a pure function of (total, selection, current prices) rather than cached state, so the
     * second call against a moved price cannot produce a quantity the first call's price would
     * have afforded but the current one does not.
     */
    /**
     * Section 10's dashboard. Gross and net reported separately throughout, because for a
     * one-session hold the difference between them is the whole question.
     */
    /**
     * Settle real orders against Dhan's own order book.
     *
     * <p>Read-only at the broker: it never places, modifies or cancels anything. One
     * {@code GET /orders} call covers the whole day, and an order whose placement response was
     * lost is found by the correlation id it was sent with.
     */
    /**
     * Import today's executed trades from the broker and reconcile them against the journal.
     *
     * <p>Section 9. {@code dryRun} defaults to TRUE: an endpoint that writes trades into a journal
     * should not do so because something issued a bare POST, and the preview is the point - the
     * broker's trade book is its view of the day, not necessarily the whole story of a position.
     */
    @PostMapping("/broker-trades/import")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importBrokerTrades(
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestBody(required = false) Map<String, Object> req,
            @AuthenticationPrincipal User user) {
        Map<String, Object> result = tradeImport.importTrades(user, dryRun,
            req == null ? null : stringList(req.get("orderIds")));
        return Boolean.FALSE.equals(result.get("success"))
            ? ResponseEntity.badRequest().body(
                ApiResponse.error("TRADE-IMPORT", String.valueOf(result.get("message"))))
            : ResponseEntity.ok(ApiResponse.ok(result));
    }

    /** The audit trail: selections, allocations and order events, newest first. Section 11. */
    @GetMapping("/audit-trail")
    public ResponseEntity<ApiResponse<java.util.List<Map<String, Object>>>> auditTrail(
            @RequestParam(required = false) String strategy,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "200") int limit,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            strategyAudit.trail(user.getId(), strategy, eventType, from, to, limit)));
    }

    @PostMapping("/reconcile")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reconcileOrders(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(reconciliation.reconcile(user)));
    }

    /**
     * Automated entry for the Volume Breakout shortlist.
     *
     * <p>{@code dryRun=true} by default, on purpose: an endpoint that places orders should not do
     * so because someone curled it to see what it did. Pass {@code dryRun=false} to actually
     * place. PAPER mode opens simulated positions; REAL mode records the signal and reports it as
     * awaiting submission rather than reaching around the compliance gate, which runs on the
     * submit endpoint immediately before an order reaches the broker.
     */
    /**
     * Place paper orders for the stocks the user ticked. Requires an explicit confirmation.
     *
     * <p>REAL is refused here by design - it must pass the compliance gate on the submit
     * endpoint - and the response says so rather than failing quietly.
     */
    @PostMapping("/volbreakout/place")
    public ResponseEntity<ApiResponse<Map<String, Object>>> volBreakoutPlace(
            @RequestBody Map<String, Object> req, @AuthenticationPrincipal User user) {
        Map<String, Object> result = autoTrade.placeManual(
            user.getId(), user.getInstitutionId(), symbolList(req.get("symbols")),
            dbl(req.get("totalInvestment"), 0), upper(req.get("mode"), "PAPER"),
            bool(req.get("confirmed")));
        return Boolean.FALSE.equals(result.get("success"))
            ? ResponseEntity.badRequest().body(
                ApiResponse.error("VOLBREAKOUT-PLACE", String.valueOf(result.get("message"))))
            : ResponseEntity.ok(ApiResponse.ok(result));
    }

    /** Manual sell: closes open paper positions in the named stocks at the current price. */
    @PostMapping("/volbreakout/sell")
    public ResponseEntity<ApiResponse<Map<String, Object>>> volBreakoutSell(
            @RequestBody Map<String, Object> req, @AuthenticationPrincipal User user) {
        Map<String, Object> result = autoTrade.sellManual(user.getId(),
            symbolList(req.get("symbols")), upper(req.get("mode"), "PAPER"),
            bool(req.get("confirmed")));
        return Boolean.FALSE.equals(result.get("success"))
            ? ResponseEntity.badRequest().body(
                ApiResponse.error("VOLBREAKOUT-SELL", String.valueOf(result.get("message"))))
            : ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/volbreakout/auto-buy")
    public ResponseEntity<ApiResponse<Map<String, Object>>> volBreakoutAutoBuy(
            @RequestParam(defaultValue = "true") boolean dryRun,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            autoTrade.autoBuy(user.getId(), user.getInstitutionId(), dryRun)));
    }

    /** Automated exit: runs the stop-loss / target / trailing / time-exit sweep now. */
    @PostMapping("/volbreakout/auto-sell")
    public ResponseEntity<ApiResponse<Map<String, Object>>> volBreakoutAutoSell(
            @RequestParam(defaultValue = "true") boolean dryRun,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            autoTrade.autoSell(user.getId(), positionMonitor, dryRun)));
    }

    /**
     * What automation would and would not do right now, and why.
     *
     * <p>Exists because "my automation did nothing" is otherwise unanswerable without reading
     * logs. Every failing condition is listed, not just the first - fixing one and rediscovering
     * the next, a restart at a time, is the worst version of this.
     */
    /**
     * Both modes' automation settings in one response, for the Auto Trading panel.
     *
     * <p>Both, always, rather than only the live one: the panel shows Paper and Real side by
     * side so that switching between them is an informed choice and not a discovery.
     */
    // ── Auto Buy & Sell, for any strategy in the system ──────────────────────
    //
    // The volbreakout endpoints above drive one strategy and will keep doing so. These drive
    // whichever strategy is selected, including anything built in the Strategy Builder, and
    // hold their settings per strategy and per mode so arming one never arms another.

    /** Every strategy automation can be pointed at: the built-in sections and saved builds. */
    @GetMapping("/auto/strategies")
    public ResponseEntity<ApiResponse<Map<String, Object>>> autoStrategies(
            @AuthenticationPrincipal User user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sources", autoSources.sources(user));
        out.put("liveMode", autoTrade.mode());
        out.put("realTradingAuthorized", autoTrade.realTradingAllowed(user.getId()));
        out.put("brokerReady", autoTrade.brokerReady(user.getId()));
        out.put("marketSessionLive", stockTracker.isTradingSessionLive());
        out.put("positionMonitorEnabled", positionMonitor.isEnabled());
        out.put("reconciliationEnabled", reconciliation.isEnabled());
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    /**
     * The stocks a strategy identifies right now.
     *
     * <p>Resolved through the same call the strategy's own screen makes, so the two cannot show
     * different lists for the same strategy.
     */
    @GetMapping("/auto/strategies/stocks")
    public ResponseEntity<ApiResponse<Map<String, Object>>> autoStrategyStocks(
            @RequestParam String key, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(autoSources.resolve(user, key)));
    }

    /** One strategy's automation settings, both modes, so the choice between them is informed. */
    @GetMapping("/auto/strategies/settings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> autoStrategySettings(
            @RequestParam String key, @AuthenticationPrincipal User user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key);
        out.put("paper", autoSources.settings(key, "PAPER"));
        out.put("real",  autoSources.settings(key, "REAL"));
        out.put("liveMode", autoTrade.mode());
        out.put("realTradingAuthorized", autoTrade.realTradingAllowed(user.getId()));
        out.put("brokerReady", autoTrade.brokerReady(user.getId()));
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    @PostMapping("/auto/strategies/settings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> saveAutoStrategySettings(
            @RequestBody Map<String, Object> req, @AuthenticationPrincipal User user) {
        String key = text(req.get("key"));
        if (key.isBlank()) {
            return ResponseEntity.badRequest().body(
                ApiResponse.error("AUTO-KEY", "Which strategy? No key was supplied."));
        }
        Map<String, Object> result = autoSources.saveSettings(
            user.getId(), key, upper(req.get("mode"), "PAPER"), req);
        return Boolean.FALSE.equals(result.get("success"))
            ? ResponseEntity.badRequest().body(
                ApiResponse.error("AUTO-SETTINGS", String.valueOf(result.get("message"))))
            : ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * Buy or sell a strategy's current stocks now.
     *
     * <p>{@code dryRun} is the default: asking what automation would do must never be the thing
     * that makes it happen.
     */
    @PostMapping("/auto/strategies/run")
    public ResponseEntity<ApiResponse<Map<String, Object>>> runAutoStrategy(
            @RequestBody Map<String, Object> req, @AuthenticationPrincipal User user) {
        String key = text(req.get("key"));
        if (key.isBlank()) {
            return ResponseEntity.badRequest().body(
                ApiResponse.error("AUTO-KEY", "Which strategy? No key was supplied."));
        }
        boolean dryRun = !req.containsKey("dryRun") || bool(req.get("dryRun"));
        String mode = upper(req.get("mode"), "PAPER");
        if ("REAL".equals(mode) && !dryRun && !bool(req.get("confirmed"))) {
            return ResponseEntity.badRequest().body(ApiResponse.error("AUTO-CONFIRM",
                "A real order must be confirmed. Nothing was placed."));
        }
        Map<String, Object> result = autoSources.run(user, key, upper(req.get("side"), "BUY"),
                                                     mode, dryRun, bool(req.get("confirmed")));
        return Boolean.FALSE.equals(result.get("success"))
            ? ResponseEntity.badRequest().body(
                ApiResponse.error("AUTO-RUN", String.valueOf(result.get("message"))))
            : ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/volbreakout/settings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> volBreakoutSettings(
            @AuthenticationPrincipal User user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("liveMode", autoTrade.mode());
        out.put("strategyEnabled", stockTracker.isVolumeBreakoutEnabled());
        out.put("paper", autoTrade.settingsFor("PAPER"));
        out.put("real", autoTrade.settingsFor("REAL"));
        // Entry conditions are the screen's, not a per-mode setting: the same shortlist feeds
        // both modes, so a mode that screened differently would not be the same strategy.
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("previousCloseBelowBase", true);
        entry.put("currentAtOrAboveBase", true);
        entry.put("volumeAboveAverage", true);
        entry.put("volumeLookbackSessions", 20);
        entry.put("requireStochasticCross", stockTracker.isStochCrossRequired());
        entry.put("shortlistSize", 5);
        entry.put("finalizeTime", stockTracker.getVolumeBreakoutFinalizeTime());
        out.put("entryConditions", entry);
        // Exit conditions are per mode, because they are the risk settings.
        Map<String, Object> exit = new LinkedHashMap<>();
        exit.put("timeExit", "Sold at the next trading day's close");
        exit.put("realOrderTime", autoTrade.realOrderTime());
        exit.put("stopLossTracked", "per position");
        exit.put("trailingStopTracked", "per position, from that position's own high water mark");
        out.put("exitConditions", exit);
        out.put("realTradingAuthorized", autoTrade.realTradingAllowed(user.getId()));
        out.put("brokerReady", autoTrade.brokerReady(user.getId()));
        out.put("positionMonitorEnabled", positionMonitor.isEnabled());
        out.put("reconciliationEnabled", reconciliation.isEnabled());
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    /** Saves one mode's automation settings. The body names the mode; nothing else is touched. */
    @PostMapping("/volbreakout/settings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> saveVolBreakoutSettings(
            @RequestBody Map<String, Object> req, @AuthenticationPrincipal User user) {
        Map<String, Object> result = autoTrade.saveSettings(
            user.getId(), upper(req.get("mode"), "PAPER"), req);
        return Boolean.FALSE.equals(result.get("success"))
            ? ResponseEntity.badRequest().body(
                ApiResponse.error("VOLBREAKOUT-SETTINGS", String.valueOf(result.get("message"))))
            : ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * Arms the strategy and picks the live mode.
     *
     * <p>Separate from saving settings on purpose. Editing a Real stop loss is not the same
     * decision as arming Real trading, and one request that did both would make the second
     * happen as a side effect of the first.
     */
    @PostMapping("/volbreakout/mode")
    public ResponseEntity<ApiResponse<Map<String, Object>>> setVolBreakoutMode(
            @RequestBody Map<String, Object> req, @AuthenticationPrincipal User user) {
        String wanted = req.get("mode") == null ? null : upper(req.get("mode"), "PAPER");
        Boolean enabled = req.containsKey("enabled") ? bool(req.get("enabled")) : null;
        if ("REAL".equals(wanted) && !bool(req.get("confirmed"))) {
            return ResponseEntity.badRequest().body(ApiResponse.error("VOLBREAKOUT-MODE",
                "Switching to REAL must be confirmed. Nothing was changed."));
        }
        Map<String, Object> result = autoTrade.setLiveMode(user.getId(), wanted, enabled);
        result.put("settings", autoTrade.effectiveSettings());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/volbreakout/auto-status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> volBreakoutAutoStatus(
            @AuthenticationPrincipal User user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("strategyEnabled", stockTracker.isVolumeBreakoutEnabled());
        out.put("autoBuyEnabled", autoTrade.isAutoBuyEnabled());
        out.put("autoSellEnabled", autoTrade.isAutoSellEnabled());
        out.put("mode", autoTrade.mode());
        out.put("marketSessionLive", stockTracker.isTradingSessionLive());
        out.put("positionMonitorEnabled", positionMonitor.isEnabled());
        out.put("reconciliationEnabled", reconciliation.isEnabled());
        out.put("finalizeTime", stockTracker.getVolumeBreakoutFinalizeTime());
        out.put("realOrderTime", autoTrade.realOrderTime());
        out.put("investmentAmount", autoTrade.investmentAmount());
        out.put("maxOpenPositions", autoTrade.maxOpenPositions());
        out.put("maxDailyLoss", autoTrade.maxDailyLoss());
        out.put("stopLossPct", autoTrade.stopLossPct());
        out.put("trailingStopPct", autoTrade.trailingStopPct());
        out.put("targetPct", autoTrade.targetPct());
        // A dry run, so asking what automation would do never causes it to happen.
        out.put("buyPreview", autoTrade.autoBuy(user.getId(), user.getInstitutionId(), true));
        out.put("note", "Entry and exit are both measured at the 15:30 close. A REAL market order "
            + "cannot be filled AT the close - 15:30 is the bell, and the official close is a "
            + "weighted average of the last thirty minutes - so a real order is sent at "
            + autoTrade.realOrderTime() + " and fills at whatever is trading then. Paper fills use "
            + "the official close exactly. The journal keeps both figures.");
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    @GetMapping("/pnl/dashboard")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pnlDashboard(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String strategy,
            @RequestParam(required = false) String mode,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
            pnl.dashboard(user.getId(), from, to, symbol, strategy, mode)));
    }

    /**
     * Run the stop-loss / trailing-stop / target / time-exit sweep now rather than waiting for the
     * next minute. Reports what it closed and why.
     */
    @PostMapping("/positions/sweep")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sweepPositions(
            @AuthenticationPrincipal User user) {
        if (!positionMonitor.isEnabled()) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "success", false, "examined", 0, "closed", List.of(),
                "message", "The position monitor is off. Turn on positionmonitor.enabled in "
                         + "Application Settings to let stop-loss and target rules close positions.")));
        }
        return ResponseEntity.ok(ApiResponse.ok(positionMonitor.sweep(user.getId())));
    }

    /**
     * Open positions with their live mark, high-water mark and the levels each rule currently sits
     * at - so a user can see where a stop actually is rather than inferring it from a percentage.
     */
    @GetMapping("/positions/open")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> openPositions(
            @AuthenticationPrincipal User user) {
        List<Object[]> rows = em.createNativeQuery(
            "SELECT id,symbol,qty,entry_price,entry_date,stop_loss_pct,trailing_sl_level,target_pct,"
          + "high_water_mark,strategy_key,sector FROM paper_trades "
          + "WHERE user_id=? AND status='open' ORDER BY id DESC")
            .setParameter(1, user.getId()).getResultList();

        List<String> symbols = rows.stream().map(r -> text(r[1])).distinct().toList();
        Map<String, Double> prices = symbols.isEmpty()
            ? Map.of() : stockTracker.executablePrices(symbols);

        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            double entry = dbl(r[3], 0);
            double price = prices.getOrDefault(stockTracker.normalizeSymbol(text(r[1])), 0.0);
            double stopPct = dbl(r[5], 0), trailPct = dbl(r[6], 0), targetPct = dbl(r[7], 0);
            double high = PositionExitRules.advanceHighWater(dbl(r[8], 0), entry, price);
            int qty = integer(r[2], 0);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r[0]);
            m.put("symbol", text(r[1]));
            m.put("sector", text(r[10]));
            m.put("quantity", qty);
            m.put("entryPrice", entry);
            m.put("entryDate", text(r[4]));
            m.put("currentPrice", price);
            m.put("strategyKey", text(r[9]));
            m.put("highWaterMark", high);
            // The actual levels, not the percentages. A percentage is what was configured; a price
            // is what will happen, and only one of those is checkable at a glance against a chart.
            m.put("stopLossPrice", stopPct > 0 ? round(entry * (1 - stopPct / 100.0)) : null);
            m.put("trailingStopPrice", trailPct > 0 ? round(high * (1 - trailPct / 100.0)) : null);
            m.put("targetPrice", targetPct > 0 ? round(entry * (1 + targetPct / 100.0)) : null);
            double unreal = price > 0 && entry > 0 ? round((price - entry) * qty) : 0;
            m.put("unrealizedPnl", unreal);
            m.put("unrealizedPct", entry > 0 && price > 0
                ? round((price - entry) / entry * 100) : 0);
            // What it will cost to get out of this and what the position has to clear.
            var c = qty > 0 && entry > 0
                ? brokerage.roundTrip(BrokerageCalculator.Product.DELIVERY, "NSE",
                                      entry, price > 0 ? price : entry, qty) : null;
            m.put("estimatedRoundTripCharges", c == null ? 0 : c.total());
            m.put("netUnrealizedPnl", c == null ? unreal : round(unreal - c.total()));
            out.add(m);
        }
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    @PostMapping("/allocate/preview")
    public ResponseEntity<ApiResponse<Map<String, Object>>> allocatePreview(
            @RequestBody Map<String, Object> req, @AuthenticationPrincipal User user) {
        List<String> symbols = symbolList(req.get("symbols"));
        if (symbols.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "success", false, "legs", List.of(), "selectedCount", 0,
                "message", "Select at least one stock to allocate across.")));
        }
        // Priced here rather than taken from the request body. A client-supplied price is a price
        // from whenever that screen last painted, and sizing an order on it is the stale-quantity
        // problem this preview exists to prevent.
        Map<String, Double> priced = new LinkedHashMap<>();
        Map<String, Double> latest = stockTracker.executablePrices(symbols);
        for (String s : symbols) {
            priced.put(s, latest.getOrDefault(stockTracker.normalizeSymbol(s), 0.0));
        }
        // The balance comes from the account the caller names, which is the whole point of
        // section 1: a Demo allocation is sized against the virtual ledger and a Real one against
        // the broker's own buying power.
        //
        // This previously fell through to portfolioSummary's "availableFunds", a fixed notional
        // of 100,000 minus whatever is open. Measured on the live server that figure had reached
        // -88,580.93, so the preview reported every basket as unaffordable and refused to size
        // anything - the comment that used to be here describes the same failure and tried to
        // dodge it by preferring a configured override that is set to 0 in practice.
        //
        // An explicit availableCash in the request still wins, and so does a configured override,
        // because both are deliberate. What is gone is the silent fall-back to a notional that
        // can go negative.
        String acct = upper(req.get("accountType"), "PAPER");
        boolean real = "REAL".equals(acct) || "LIVE".equals(acct);

        Double accountBalance = null;
        String balanceSource;
        if (real) {
            Map<String, Object> funds = brokerFunds.funds(user);
            // Buying power, not cash plus collateral: it is what an equity delivery order may
            // actually use. Null when the broker could not be read - and a real order must not be
            // sized against a guess, so that stays null rather than becoming a number.
            Object bp = funds.get("buying_power");
            if (bp == null) bp = funds.get("available_balance");
            accountBalance = bp instanceof Number n ? n.doubleValue() : null;
            balanceSource = Boolean.TRUE.equals(funds.get("stale"))
                ? "broker (last known reading, could not be refreshed)"
                : "broker";
            if (accountBalance == null) {
                return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "success", false, "legs", List.of(), "selectedCount", symbols.size(),
                    "accountType", "REAL", "availableBalance", (Object) null,
                    "message", "The broker balance could not be read, so a real order cannot be "
                             + "sized. " + String.valueOf(funds.getOrDefault("message", "")).trim())));
            }
        } else {
            accountBalance = demoAccount.balance(user.getId(), user.getInstitutionId());
            balanceSource = "demo ledger";
        }

        double configured = appConfig.getDouble("volbreakout.available.funds", 0);
        double available = dbl(req.get("availableCash"),
                               configured > 0 ? configured : accountBalance);
        Map<String, Object> result = allocation.allocate(
            dbl(req.get("totalInvestment"), 0), available, priced,
            productOf(req.get("productType")), upper(req.get("exchange"), "NSE"));

        // Section 11's record of stock selection, deselection and capital allocation. Written
        // here because this is the one place that sees both at once - which stocks are ticked and
        // what that produces - and it deduplicates internally, so the preview recalculating on
        // every tick does not turn the trail into a click log.
        strategyAudit.recordSelection(user.getId(), user.getInstitutionId(),
            textOr(req.get("strategyKey"), "volbreakout"), upper(req.get("mode"), "PAPER"),
            symbols, dbl(req.get("totalInvestment"), 0), result);

        // Which account the sizing assumed, and where that balance came from. Stated on every
        // response: a quantity means nothing without knowing whose money it was sized against,
        // and this is what stops a real allocation being read as a paper one.
        result.put("accountType", real ? "REAL" : "PAPER");
        result.put("accountBalance", accountBalance);
        result.put("balanceSource", balanceSource);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * Charges on a trade, itemised, at Dhan's published rates.
     *
     * <p>Exposed on its own because the journal, the allocation strip and the order preview all
     * need the same figure, and because a user ought to be able to check a number the application
     * is about to subtract from their P&amp;L against their own contract note.
     */
    @PostMapping("/charges/preview")
    public ResponseEntity<ApiResponse<Map<String, Object>>> chargesPreview(
            @RequestBody Map<String, Object> req, @AuthenticationPrincipal User user) {
        BrokerageCalculator.Product product = productOf(req.get("productType"));
        String exchange = upper(req.get("exchange"), "NSE");
        int qty = integer(req.get("quantity"), 0);
        double buyPrice = dbl(req.get("buyPrice"), dbl(req.get("price"), 0));
        double sellPrice = dbl(req.get("sellPrice"), 0);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("product", product.name());
        out.put("exchange", exchange);
        if (sellPrice > 0 && buyPrice > 0) {
            var rt = brokerage.roundTrip(product, exchange, buyPrice, sellPrice, qty);
            out.put("roundTrip", rt.asMap());
            // Spelled out because it is the number that decides whether a one-session strategy
            // can work at all: it has to average more than this, gross, just to break even.
            out.put("breakEvenMovePct", rt.totalPct());
        } else {
            String side = upper(req.get("side"), "BUY");
            var leg = brokerage.charges(product,
                "SELL".equals(side) ? BrokerageCalculator.Side.SELL : BrokerageCalculator.Side.BUY,
                exchange, buyPrice > 0 ? buyPrice : dbl(req.get("price"), 0), qty,
                // The DP fee is per ISIN per day, not per order, so a caller splitting an exit
                // across orders must claim it on one of them only.
                bool(req.getOrDefault("lastDeliverySellOfDay", true)));
            out.put("leg", leg.asMap());
            out.put("side", side);
        }
        out.put("note", "Rates from Dhan's published pricing. Equity delivery is zero-brokerage; "
            + "the cost is statutory plus the per-ISIN depository fee. Verify against a contract "
            + "note before relying on net P&L.");
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    /**
     * Delivery unless told otherwise.
     *
     * <p>Defaulting to delivery is deliberate rather than alphabetical: this platform's strategies
     * hold overnight, and mislabelling a delivery trade as intraday understates a round trip by
     * roughly a third - no STT on the buy leg, a fifth of the stamp duty, and no DP fee.
     * Under-reporting cost is the direction that makes a losing strategy look viable.
     */
    private BrokerageCalculator.Product productOf(Object raw) {
        String p = upper(raw, "DELIVERY");
        if (p.startsWith("INTRA") || "MIS".equals(p)) return BrokerageCalculator.Product.INTRADAY;
        if (p.startsWith("MTF")) return BrokerageCalculator.Product.MTF;
        return BrokerageCalculator.Product.DELIVERY;
    }

    @SuppressWarnings("unchecked")
    private List<String> symbolList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                String s = upper(o, "");
                if (!s.isBlank() && !out.contains(s)) out.add(s);
            }
        } else if (raw != null) {
            for (String s : text(raw).split("[,\\s]+")) {
                String u = s.trim().toUpperCase(Locale.ROOT);
                if (!u.isBlank() && !out.contains(u)) out.add(u);
            }
        }
        return out;
    }

    @PostMapping("/order/preview")
    public ResponseEntity<ApiResponse<Map<String, Object>>> previewOrder(@RequestBody Map<String, Object> req,
                                                                         @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(orderCalculation(req)));
    }

    @PostMapping("/order/submit")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitOrder(@RequestBody Map<String, Object> req,
                                                                        @AuthenticationPrincipal User user,
                                                                        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
        if (!bool(req.get("confirmed"))) {
            return ResponseEntity.badRequest().body(ApiResponse.error("TRADING-CONFIRM", "Please confirm the order before submitting."));
        }
        String mode = upper(req.get("mode"), "PAPER");
        String side = upper(req.get("side"), "BUY");
        String symbol = upper(req.get("symbol"), "");
        if (symbol.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("TRADING-SYMBOL", "Symbol is required."));
        }
        Map<String, Object> checklist = checklistValidation(req, user);
        if (Boolean.TRUE.equals(checklist.get("blocked"))) {
            return ResponseEntity.badRequest().body(ApiResponse.error("TRADING-CHECKLIST", "Please complete mandatory checklist items before placing this order."));
        }
        if ("REAL".equals(mode)) {
            return submitRealOrder(req, user, side, symbol, authHeader);
        }
        return submitPaperOrder(req, user, side, symbol);
    }

    @PostMapping("/auto-settings")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> saveAutoSettings(@RequestBody Map<String, Object> req,
                                                                             @AuthenticationPrincipal User user) {
        String strategyKey = text(req.get("strategyKey"));
        if (strategyKey.isBlank()) strategyKey = "stock_tracker";
        String strategyName = text(req.get("strategyName"));
        if (strategyName.isBlank()) strategyName = "Stock Tracker";
        int updated = em.createNativeQuery(
            "UPDATE trading_auto_settings SET strategy_name=?, mode=?, auto_buy=?, auto_sell=?, automated_execution=?, " +
            "buy_time=?, sell_time=?, investment_amount=?, max_capital_allocation=?, max_daily_trades=?, risk_per_trade_pct=?, " +
            "stop_loss_pct=?, trailing_stop_loss_pct=?, target_pct=?, max_daily_loss_limit=?, max_open_positions=?, " +
            "max_stock_allocation=?, max_sector_allocation=?, circuit_filter_protection=?, volume_validation=?, liquidity_validation=?, " +
            "status=?, updated_at=? WHERE user_id=? AND strategy_key=?")
            .setParameter(1, strategyName)
            .setParameter(2, upper(req.get("mode"), "PAPER"))
            .setParameter(3, bool(req.get("autoBuy")) ? 1 : 0)
            .setParameter(4, bool(req.get("autoSell")) ? 1 : 0)
            .setParameter(5, bool(req.get("automatedExecution")) ? 1 : 0)
            .setParameter(6, text(req.get("buyTime")))
            .setParameter(7, text(req.get("sellTime")))
            .setParameter(8, dbl(req.get("investmentAmount"), 0))
            .setParameter(9, dbl(req.get("maxCapitalAllocation"), 0))
            .setParameter(10, integer(req.get("maxDailyTrades"), 0))
            .setParameter(11, dbl(req.get("riskPerTradePct"), 0))
            .setParameter(12, dbl(req.get("stopLossPct"), 0))
            .setParameter(13, dbl(req.get("trailingStopLossPct"), 0))
            .setParameter(14, dbl(req.get("targetPct"), 0))
            .setParameter(15, dbl(req.get("maxDailyLossLimit"), 0))
            .setParameter(16, integer(req.get("maxOpenPositions"), 0))
            .setParameter(17, dbl(req.get("maxStockAllocation"), 0))
            .setParameter(18, dbl(req.get("maxSectorAllocation"), 0))
            .setParameter(19, bool(req.get("circuitFilterProtection")) ? 1 : 0)
            .setParameter(20, bool(req.get("volumeValidation")) ? 1 : 0)
            .setParameter(21, bool(req.get("liquidityValidation")) ? 1 : 0)
            .setParameter(22, bool(req.get("active")) ? "active" : "inactive")
            .setParameter(23, DateUtil.now())
            .setParameter(24, user.getId())
            .setParameter(25, strategyKey)
            .executeUpdate();
        if (updated == 0) {
            em.createNativeQuery(
                "INSERT INTO trading_auto_settings(user_id,institution_id,strategy_key,strategy_name,mode,auto_buy,auto_sell,automated_execution,buy_time,sell_time,investment_amount,max_capital_allocation,max_daily_trades,risk_per_trade_pct,stop_loss_pct,trailing_stop_loss_pct,target_pct,max_daily_loss_limit,max_open_positions,max_stock_allocation,max_sector_allocation,circuit_filter_protection,volume_validation,liquidity_validation,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
                .setParameter(1, user.getId())
                .setParameter(2, user.getInstitutionId())
                .setParameter(3, strategyKey)
                .setParameter(4, strategyName)
                .setParameter(5, upper(req.get("mode"), "PAPER"))
                .setParameter(6, bool(req.get("autoBuy")) ? 1 : 0)
                .setParameter(7, bool(req.get("autoSell")) ? 1 : 0)
                .setParameter(8, bool(req.get("automatedExecution")) ? 1 : 0)
                .setParameter(9, text(req.get("buyTime")))
                .setParameter(10, text(req.get("sellTime")))
                .setParameter(11, dbl(req.get("investmentAmount"), 0))
                .setParameter(12, dbl(req.get("maxCapitalAllocation"), 0))
                .setParameter(13, integer(req.get("maxDailyTrades"), 0))
                .setParameter(14, dbl(req.get("riskPerTradePct"), 0))
                .setParameter(15, dbl(req.get("stopLossPct"), 0))
                .setParameter(16, dbl(req.get("trailingStopLossPct"), 0))
                .setParameter(17, dbl(req.get("targetPct"), 0))
                .setParameter(18, dbl(req.get("maxDailyLossLimit"), 0))
                .setParameter(19, integer(req.get("maxOpenPositions"), 0))
                .setParameter(20, dbl(req.get("maxStockAllocation"), 0))
                .setParameter(21, dbl(req.get("maxSectorAllocation"), 0))
                .setParameter(22, bool(req.get("circuitFilterProtection")) ? 1 : 0)
                .setParameter(23, bool(req.get("volumeValidation")) ? 1 : 0)
                .setParameter(24, bool(req.get("liquidityValidation")) ? 1 : 0)
                .setParameter(25, bool(req.get("active")) ? "active" : "inactive")
                .setParameter(26, DateUtil.now())
                .setParameter(27, DateUtil.now())
                .executeUpdate();
        }
        audit(user, strategyKey, strategyName, upper(req.get("mode"), "PAPER"), "STRATEGY_CONFIG", "", "", "success",
            "Strategy automation settings saved.", "", "", req);
        return ResponseEntity.ok(ApiResponse.ok("Trading settings saved.", Map.of(
            "autoSettings", autoSettings(user),
            "auditLogs", auditLogs(user, 25)
        )));
    }

    @PostMapping("/stock-tracker/run")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> runStockTracker(@RequestBody Map<String, Object> req,
                                                                            @AuthenticationPrincipal User user) {
        String action = upper(req.get("action"), "AUTO_BUY");
        List<Map<String, Object>> top10 = currentTop10(user);
        String message;
        if ("AUTO_BUY".equals(action)) {
            message = "Stock Tracker Auto Buy reviewed " + top10.size() + " Current Top 10 rows. Orders are ready for confirmation.";
        } else {
            message = "Stock Tracker Auto Sell reviewed only positions created by Stock Tracker strategy.";
        }
        audit(user, "stock_tracker", "Stock Tracker", upper(req.get("mode"), "PAPER"), action, "", "", "reviewed",
            message, "", "", Map.of("top10Count", top10.size()));
        return ResponseEntity.ok(ApiResponse.ok(message, Map.of("top10", top10, "auditLogs", auditLogs(user, 25))));
    }

    @PostMapping("/stock-tracker/bulk-order")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> bulkStockTrackerOrder(@RequestBody Map<String, Object> req,
                                                                                  @AuthenticationPrincipal User user,
                                                                                  @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
        if (!bool(req.get("confirmed"))) {
            return ResponseEntity.badRequest().body(ApiResponse.error("TRADING-CONFIRM", "Please confirm before placing all 10 orders."));
        }
        String side = upper(req.get("side"), "BUY");
        if (!"BUY".equals(side) && !"SELL".equals(side)) {
            return ResponseEntity.badRequest().body(ApiResponse.error("TRADING-SIDE", "Side must be BUY or SELL."));
        }
        String mode = upper(req.get("mode"), "PAPER");
        // Before anything is priced or journalled: the tracker sends no risk fields.
        applyDefaultRiskPercentages(req);
        List<Map<String, Object>> top10 = currentTop10(user);
        List<Map<String, Object>> results = new ArrayList<>();
        int success = 0;
        int failed = 0;
        double availableCash = dbl(req.get("availableCash"), dbl(portfolioSummary(user).get("availableFunds"), 100000));
        double perStockInvestment = dbl(req.get("investmentAmountPerStock"), dbl(req.get("investmentAmount"), 0));

        for (Map<String, Object> stock : top10) {
            String symbol = upper(stock.get("symbol"), "");
            double price = dbl(stock.get("price"), 0);
            if (symbol.isBlank() || price <= 0) {
                failed++;
                results.add(Map.of("symbol", symbol, "status", "failed", "message", "Symbol or latest price is missing."));
                journal(user, req, mode, side, symbol, 0, "BUY".equals(side) ? price : 0, "SELL".equals(side) ? price : 0, 0, "Failed Trade", "Validation", "Symbol or latest price missing.");
                continue;
            }
            int qty = integer(req.get("quantity"), 0);
            if (qty <= 0) qty = perStockInvestment > 0 ? (int) Math.floor(perStockInvestment / price) : 1;
            if (qty <= 0) {
                failed++;
                results.add(Map.of("symbol", symbol, "status", "failed", "message", "Quantity is zero."));
                journal(user, req, mode, side, symbol, 0, "BUY".equals(side) ? price : 0, "SELL".equals(side) ? price : 0, 0, "Failed Trade", "Validation", "Quantity is zero.");
                continue;
            }
            double orderValue = price * qty;
            if ("BUY".equals(side) && orderValue > availableCash) {
                failed++;
                results.add(Map.of("symbol", symbol, "status", "failed", "message", "Insufficient available margin."));
                journal(user, req, mode, side, symbol, qty, price, 0, 0, "Failed Trade", "Margin", "Insufficient available margin.");
                continue;
            }
            Map<String, Object> order = new LinkedHashMap<>(req);
            order.put("confirmed", true);
            order.put("mode", mode);
            order.put("side", side);
            order.put("symbol", symbol);
            order.put("sector", stock.get("sector"));
            order.put("price", price);
            order.put("quantity", qty);
            order.put("exchange", textOr(req.get("exchange"), "NSE"));
            order.put("segment", textOr(req.get("segment"), "EQ-SWG"));
            order.put("productType", textOr(req.get("productType"), "CNC"));
            order.put("orderType", textOr(req.get("orderType"), "MARKET"));
            order.put("strategyKey", "stock_tracker");
            order.put("strategyName", "Stock Tracker");
            if ("REAL".equals(mode) && lng(order.get("brokerId")) == null) {
                Long brokerId = firstVerifiedTradingBrokerId(user);
                if (brokerId != null) order.put("brokerId", brokerId);
            }
            try {
                ResponseEntity<ApiResponse<Map<String, Object>>> response = "REAL".equals(mode)
                    ? submitRealOrder(order, user, side, symbol, authHeader)
                    : submitPaperOrder(order, user, side, symbol);
                boolean ok = response.getStatusCode().is2xxSuccessful() && response.getBody() != null && response.getBody().isSuccess();
                if (ok) {
                    success++;
                    availableCash -= "BUY".equals(side) ? orderValue : 0;
                    results.add(Map.of("symbol", symbol, "status", "success", "quantity", qty, "price", price, "response", response.getBody().getData()));
                } else {
                    failed++;
                    String message = response.getBody() != null ? response.getBody().getMessage() : "Order failed.";
                    results.add(Map.of("symbol", symbol, "status", "failed", "quantity", qty, "price", price, "message", message));
                }
            } catch (Exception e) {
                failed++;
                results.add(Map.of("symbol", symbol, "status", "failed", "quantity", qty, "price", price, "message", UserMessage.of(e)));
                journal(user, order, mode, side, symbol, qty, "BUY".equals(side) ? price : 0, "SELL".equals(side) ? price : 0, 0, "Failed Trade", "Exception", UserMessage.of(e));
            }
        }
        String message = side + " All 10 completed. Success: " + success + ", Failed: " + failed + ".";
        audit(user, "stock_tracker", "Stock Tracker", mode, side + "_ALL_10", "", side, failed == 0 ? "success" : "partial",
            message, text(req.get("brokerName")), "", Map.of("results", results));
        return ResponseEntity.ok(ApiResponse.ok(message, Map.of("successCount", success, "failedCount", failed, "results", results, "auditLogs", auditLogs(user, 25))));
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<ApiResponse<Map<String, Object>>> submitPaperOrder(Map<String, Object> req, User user, String side, String symbol) {
        Map<String, Object> calc = orderCalculation(req);
        if ("SELL".equals(side)) {
            List<Object[]> open = em.createNativeQuery(
                "SELECT id,entry_price,qty FROM paper_trades WHERE user_id=? AND UPPER(symbol)=? AND status='open' ORDER BY id DESC LIMIT 1")
                .setParameter(1, user.getId())
                .setParameter(2, symbol)
                .getResultList();
            if (open.isEmpty()) {
                return ResponseEntity.badRequest().body(ApiResponse.error("TRADING-NO-POSITION", "No open paper position found for " + symbol + "."));
            }
            Object[] row = open.get(0);
            double price = dbl(req.get("price"), dbl(row[1], 0));
            int qty = integer(req.get("quantity"), integer(row[2], 0));
            double entry = dbl(row[1], price);
            double pnl = round((price - entry) * qty);
            double pnlPct = entry <= 0 ? 0 : round(((price - entry) / entry) * 100);
            em.createNativeQuery("UPDATE paper_trades SET exit_price=?,exit_date=?,exit_type=?,pnl=?,pnl_pct=?,status='closed' WHERE id=?")
                .setParameter(1, price)
                .setParameter(2, DateUtil.today())
                .setParameter(3, "Trading Module")
                .setParameter(4, pnl)
                .setParameter(5, pnlPct)
                .setParameter(6, row[0])
                .executeUpdate();
            audit(user, text(req.get("strategyKey")), text(req.get("strategyName")), "PAPER", "SELL_EXECUTION", symbol, side, "success",
                "Paper sell completed. P&L " + pnl, "", "", req);
            journal(user, req, "PAPER", "SELL", symbol, qty, 0, price, pnl, pnl >= 0 ? "Successful Trade" : "Failed Trade", "Manual Exit", "Paper sell completed.");
            return ResponseEntity.ok(ApiResponse.ok("Paper sell completed.", Map.of("calculation", calc, "pnl", pnl, "summary", portfolioSummary(user))));
        }

        String tradeId = "TRD-" + user.getId() + "-" + DateUtil.today().replace("-", "") + "-" + (q("SELECT COUNT(*) FROM paper_trades") + 1);
        double price = dbl(req.get("price"), 0);
        int qty = integer(req.get("quantity"), integer(calc.get("quantity"), 1));
        em.createNativeQuery(
            "INSERT INTO paper_trades(trade_id,strategy_id,user_id,institution_id,trade_type,exchange,symbol,sector,segment,qty,entry_price,entry_date,stop_loss,stop_loss_pct,target,target_pct,rr_ratio,trailing_sl_level,trailing_sl_active,portfolio_name,broker_used,status,source) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
            .setParameter(1, tradeId)
            .setParameter(2, null)
            .setParameter(3, user.getId())
            .setParameter(4, user.getInstitutionId())
            .setParameter(5, "BUY")
            .setParameter(6, text(req.getOrDefault("exchange", "NSE")))
            .setParameter(7, symbol)
            .setParameter(8, text(req.get("sector")))
            .setParameter(9, text(req.getOrDefault("segment", "EQ-SWG")))
            .setParameter(10, qty)
            .setParameter(11, price)
            .setParameter(12, DateUtil.today())
            .setParameter(13, calc.get("stopLossPrice"))
            .setParameter(14, dbl(req.get("stopLossPct"), 0))
            .setParameter(15, calc.get("targetPrice"))
            .setParameter(16, dbl(req.get("targetPct"), 0))
            .setParameter(17, calc.get("riskRewardRatio"))
            .setParameter(18, calc.get("trailingStopPrice"))
            .setParameter(19, dbl(req.get("trailingStopLossPct"), 0) > 0 ? 1 : 0)
            .setParameter(20, text(req.getOrDefault("portfolioName", "Default")))
            .setParameter(21, "Paper")
            .setParameter(22, "open")
            .setParameter(23, text(req.getOrDefault("strategyKey", "manual")))
            .executeUpdate();
        audit(user, text(req.get("strategyKey")), text(req.get("strategyName")), "PAPER", "BUY_EXECUTION", symbol, side, "success",
            "Paper buy completed.", "", "", req);
        journal(user, req, "PAPER", "BUY", symbol, qty, price, 0, 0, "Open Trade", "Entry", "Paper buy completed.");
        return ResponseEntity.ok(ApiResponse.ok("Paper buy completed.", Map.of("tradeId", tradeId, "calculation", calc, "summary", portfolioSummary(user))));
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> submitRealOrder(Map<String, Object> req, User user, String side, String symbol, String authHeader) {
        Long brokerId = lng(req.get("brokerId"));
        if (brokerId == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("TRADING-BROKER", "Select a verified trading-enabled broker."));
        }
        List<Object[]> rows = em.createNativeQuery(
            "SELECT id,broker_name,broker_label,client_id,access_token_enc,read_only,is_verified,is_active FROM broker_credentials WHERE id=? AND user_id=?")
            .setParameter(1, brokerId)
            .setParameter(2, user.getId())
            .getResultList();
        if (rows.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("TRADING-BROKER", "Broker connection not found."));
        }
        Object[] broker = rows.get(0);
        if (integer(broker[7], 0) != 1 || integer(broker[6], 0) != 1 || integer(broker[5], 1) == 1) {
            return ResponseEntity.badRequest().body(ApiResponse.error("TRADING-BROKER", "Broker must be active, verified, and Trading Enabled."));
        }
        String brokerName = String.valueOf(broker[1]);
        if (!"dhan".equalsIgnoreCase(brokerName)) {
            audit(user, text(req.get("strategyKey")), text(req.get("strategyName")), "REAL", "BROKER_RESPONSE", symbol, side, "rejected",
                "Real order adapter for " + broker[2] + " is not enabled yet.", String.valueOf(broker[2]), "", req);
            journal(user, req, "REAL", side, symbol, integer(req.get("quantity"), 0), "BUY".equals(side) ? dbl(req.get("price"), 0) : 0, "SELL".equals(side) ? dbl(req.get("price"), 0) : 0, 0, "Failed Trade", "Broker Adapter", "Real adapter is not enabled for " + broker[2] + ".");
            return ResponseEntity.badRequest().body(ApiResponse.error("TRADING-ADAPTER", "This broker connection is saved, but real order placement adapter is not enabled yet."));
        }
        DhanOrderInstrument instrument = resolveDhanOrderInstrument(symbol, text(req.get("securityId")), text(req.get("exchangeSegment")));
        if (instrument == null) {
            String message = "Dhan mapping missing for " + symbol + ". Refresh Dhan Stock List so underlying_symbol maps to a valid Security ID.";
            audit(user, text(req.get("strategyKey")), text(req.get("strategyName")), "REAL", "MAPPING_FAILED", symbol, side, "rejected",
                message, "Dhan", "", req);
            return ResponseEntity.badRequest().body(ApiResponse.error("TRADING-MAPPING", message));
        }
        int quantity = integer(req.get("quantity"), 1);
        if (quantity <= 0) {
            return ResponseEntity.badRequest().body(ApiResponse.error("TRADING-QTY", "Quantity must be greater than zero."));
        }
        String orderType = dhanOrderType(text(req.get("orderType")));
        double price = dbl(req.get("price"), 0);
        double triggerPrice = dbl(req.get("triggerPrice"), 0);
        if (("LIMIT".equals(orderType) || "STOP_LOSS".equals(orderType)) && price <= 0) {
            return ResponseEntity.badRequest().body(ApiResponse.error("TRADING-PRICE", orderType + " order requires a valid price."));
        }
        if ("STOP_LOSS".equals(orderType) && triggerPrice <= 0) {
            return ResponseEntity.badRequest().body(ApiResponse.error("TRADING-TRIGGER", "Stop-loss order requires a valid trigger price."));
        }
        // Volume 3, Sections 2.5 and 2.7 - the gate (eligibility, KYC, broker link, risk
        // acknowledgment) and then the risk limits, evaluated immediately before the order
        // reaches the broker. Both are owned by trep-institution-service; this fails closed.
        TradingGateClient.Decision gate = tradingGate.checkRealOrder(authHeader, "real");
        if (!gate.allowed()) {
            audit(user, text(req.get("strategyKey")), text(req.get("strategyName")), "REAL", "GATE_BLOCKED", symbol, side, "rejected",
                gate.message(), String.valueOf(broker[2]), "", req);
            journal(user, req, "REAL", side, symbol, integer(req.get("quantity"), 0), "BUY".equals(side) ? dbl(req.get("price"), 0) : 0, "SELL".equals(side) ? dbl(req.get("price"), 0) : 0, 0, "Blocked Trade", "Pre-Trade Gate", gate.message());
            return ResponseEntity.badRequest().body(ApiResponse.error(gate.code(), gate.message()));
        }

        try {
            Map<String, String> headers = Map.of(
                "Accept", "application/json",
                "Content-Type", "application/json",
                "access-token", decode(String.valueOf(broker[4])),
                "client-id", String.valueOf(broker[3])
            );
            // The deterministic correlation id is what makes a lost placement response
            // recoverable. If this POST times out the order may well have reached Dhan - the
            // request succeeded and only the reply was lost - and without an id of our own there
            // is no way to ask "did it?". GET /orders/external/{correlation-id} answers it, but
            // only for an id we chose in advance and can reproduce.
            //
            // Derived from the idempotency key, so the same logical order always yields the same
            // id, and shortened to Dhan's 30-character, restricted-character limit by
            // BrokerOrderStatus.correlationId - our own key has colons and is far too long.
            String idemKey = text(req.get("idempotencyKey")).isBlank()
                ? BrokerOrderStatus.idempotencyKey(user.getId(), text(req.get("strategyKey")),
                        symbol, side, DateUtil.today(), lng(req.get("signalId")) == null
                            ? 0 : lng(req.get("signalId")))
                : text(req.get("idempotencyKey"));
            String correlationId = BrokerOrderStatus.correlationId(
                user.getId(), DateUtil.today(), idemKey);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("dhanClientId", String.valueOf(broker[3]));
            body.put("correlationId", correlationId);
            body.put("transactionType", side);
            body.put("exchangeSegment", instrument.exchangeSegment());
            body.put("productType", text(req.getOrDefault("productType", "CNC")));
            body.put("orderType", orderType);
            body.put("validity", "DAY");
            body.put("securityId", instrument.securityId());
            body.put("quantity", quantity);
            body.put("price", price);
            body.put("triggerPrice", triggerPrice);
            String response = http.postJson(DHAN_API + "/orders", headers, body, 15000);
            JsonNode json = mapper.readTree(response);
            audit(user, text(req.get("strategyKey")), text(req.get("strategyName")), "REAL", side + "_EXECUTION", symbol, side, "success",
                "Dhan real order sent.", "Dhan", response, body);
            journal(user, req, "REAL", side, symbol, quantity, "BUY".equals(side) ? price : 0, "SELL".equals(side) ? price : 0, 0, "Order Sent", "Broker Execution", "Dhan real order sent.");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("brokerResponse", json);
            result.put("calculation", orderCalculation(req));
            result.put("resolvedInstrument", instrument.toMap());
            return ResponseEntity.ok(ApiResponse.ok("Real order sent to Dhan.", result));
        } catch (Exception e) {
            audit(user, text(req.get("strategyKey")), text(req.get("strategyName")), "REAL", "FAILED_ORDER", symbol, side, "failed",
                UserMessage.of(e), "Dhan", UserMessage.of(e), req);
            journal(user, req, "REAL", side, symbol, integer(req.get("quantity"), 0), "BUY".equals(side) ? dbl(req.get("price"), 0) : 0, "SELL".equals(side) ? dbl(req.get("price"), 0) : 0, 0, "Failed Trade", "Broker Response", UserMessage.of(e));
            return ResponseEntity.badRequest().body(ApiResponse.error("TRADING-DHAN", UserMessage.of(e, "Dhan order failed.")));
        }
    }

    private Map<String, Object> orderCalculation(Map<String, Object> req) {
        double cash = dbl(req.get("availableCash"), 100000);
        double investment = dbl(req.get("investmentAmount"), 0);
        double price = dbl(req.get("price"), 0);
        int requestedQty = integer(req.get("quantity"), 0);
        int qty = requestedQty > 0 ? requestedQty : (price > 0 && investment > 0 ? (int) Math.floor(investment / price) : 0);
        double orderValue = round(price * qty);
        double stopPct = dbl(req.get("stopLossPct"), 0);
        double trailPct = dbl(req.get("trailingStopLossPct"), 0);
        double targetPct = dbl(req.get("targetPct"), 0);
        double stopPrice = stopPct > 0 ? round(price * (1 - stopPct / 100.0)) : dbl(req.get("stopLossPrice"), 0);
        double trailPrice = trailPct > 0 ? round(price * (1 - trailPct / 100.0)) : 0;
        double targetPrice = targetPct > 0 ? round(price * (1 + targetPct / 100.0)) : dbl(req.get("targetPrice"), 0);
        double risk = stopPrice > 0 ? round((price - stopPrice) * qty) : 0;
        double reward = targetPrice > 0 ? round((targetPrice - price) * qty) : 0;
        double rr = risk > 0 ? round(reward / risk) : 0;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("quantity", qty);
        m.put("orderValue", orderValue);
        m.put("availableCash", cash);
        m.put("cashAfterOrder", round(cash - orderValue));
        m.put("marginUtilizationPct", cash > 0 ? round(orderValue / cash * 100) : 0);
        m.put("stopLossPrice", stopPrice);
        m.put("trailingStopPrice", trailPrice);
        m.put("targetPrice", targetPrice);
        m.put("riskAmount", risk);
        m.put("rewardAmount", reward);
        m.put("riskRewardRatio", rr);
        return m;
    }

    private Map<String, Object> portfolioSummary(User user) {
        Map<String, Object> s = new LinkedHashMap<>();
        double openInvested = qd("SELECT COALESCE(SUM(entry_price * qty),0) FROM paper_trades WHERE user_id=" + user.getId() + " AND status='open'");
        double realized = qd("SELECT COALESCE(SUM(pnl),0) FROM paper_trades WHERE user_id=" + user.getId() + " AND status='closed'");
        s.put("todayPnl", qd("SELECT COALESCE(SUM(pnl),0) FROM paper_trades WHERE user_id=" + user.getId() + " AND exit_date='" + DateUtil.today() + "'"));
        s.put("openPositions", q("SELECT COUNT(*) FROM paper_trades WHERE user_id=" + user.getId() + " AND status='open'"));
        s.put("closedPositions", q("SELECT COUNT(*) FROM paper_trades WHERE user_id=" + user.getId() + " AND status='closed'"));
        s.put("holdings", q("SELECT COUNT(DISTINCT symbol) FROM paper_trades WHERE user_id=" + user.getId() + " AND status='open'"));
        s.put("availableFunds", round(100000 - openInvested));
        s.put("availableMargin", round(100000 - openInvested));
        s.put("marginUtilization", round(openInvested));
        s.put("riskUtilizationPct", openInvested > 0 ? round(openInvested / 100000 * 100) : 0);
        s.put("realizedPnl", round(realized));
        s.put("unrealizedPnl", qd("SELECT COALESCE(SUM(pnl),0) FROM paper_trades WHERE user_id=" + user.getId() + " AND status='open'"));
        s.put("activeStrategies", q("SELECT COUNT(*) FROM trading_auto_settings WHERE user_id=" + user.getId() + " AND status='active'"));
        s.put("autoTradeStatus", q("SELECT COUNT(*) FROM trading_auto_settings WHERE user_id=" + user.getId() + " AND automated_execution=1 AND status='active'") > 0 ? "Active" : "Inactive");
        return s;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> strategies(User user) {
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Object> stockTracker = new LinkedHashMap<>();
        stockTracker.put("id", -1);
        stockTracker.put("key", "stock_tracker");
        stockTracker.put("name", "Stock Tracker");
        stockTracker.put("status", "active");
        stockTracker.put("description", "Auto buy Current Top 10 stocks and sell only Stock Tracker positions by risk rules.");
        list.add(stockTracker);
        List<Object[]> rows = em.createNativeQuery(
            "SELECT id,name,status,COALESCE(description,''),COALESCE(win_rate,0),COALESCE(backtested,0),COALESCE(paper_tested,0),COALESCE(live_approved,0) FROM strategies WHERE (user_id=? OR is_public=1) ORDER BY id DESC LIMIT 50")
            .setParameter(1, user.getId()).getResultList();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r[0]); m.put("key", "strategy_" + r[0]); m.put("name", r[1]); m.put("status", r[2]);
            m.put("description", r[3]); m.put("winRate", r[4]); m.put("backtested", r[5]); m.put("paperTested", r[6]); m.put("liveApproved", r[7]);
            list.add(m);
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> brokers(User user) {
        List<Object[]> rows = em.createNativeQuery(
            "SELECT id,broker_name,broker_label,client_id,read_only,is_verified,is_active FROM broker_credentials WHERE user_id=? AND is_active=1 ORDER BY broker_name")
            .setParameter(1, user.getId()).getResultList();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r[0]); m.put("brokerName", r[1]); m.put("brokerLabel", r[2]); m.put("clientId", r[3]);
            m.put("readOnly", integer(r[4], 1) == 1); m.put("verified", integer(r[5], 0) == 1); m.put("active", integer(r[6], 0) == 1);
            list.add(m);
        }
        return list;
    }

    private Long firstVerifiedTradingBrokerId(User user) {
        try {
            List<?> rows = em.createNativeQuery(
                "SELECT id FROM broker_credentials WHERE user_id=? AND broker_name='dhan' AND is_active=1 AND is_verified=1 AND COALESCE(read_only,0)=0 ORDER BY id DESC LIMIT 1")
                .setParameter(1, user.getId()).getResultList();
            if (rows.isEmpty()) return null;
            return lng(rows.get(0));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The Top 10 that bulk orders are placed against, CALCULATED rather than read back.
     *
     * <p>This used to read {@code tracker_ranking_items} for the newest stored run, with no
     * fallback, and that was wrong in a way that mattered: a stored run carries the
     * {@code current_price} it saw when it ran. Orders were therefore being sized against a price
     * from whenever the last ranking happened - up to five minutes stale during a session, and
     * arbitrarily stale once the per-refresh ranking is switched off, which is now a supported
     * setting. "Buy all ten" on yesterday's prices is not a slow screen, it is a wrong order.
     *
     * <p>The Top 10 screen already computes this list live - {@code getLatestRankingFull} runs
     * {@code buildFallbackTop10Ranking} first and only falls back to stored runs for history. Using
     * the same call makes the orders agree with the screen the user clicked from, which they
     * previously did not.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> currentTop10(User user) {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            Map<String, Object> ranking = stockTracker.getLatestRankingFull(
                user.getId(), user.getInstitutionId(), true);
            List<Map<String, Object>> top =
                (List<Map<String, Object>>) ranking.getOrDefault("current_top10", List.of());
            int rank = 0;
            for (Map<String, Object> s : top) {
                if (++rank > 10) break;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("rank", rank);
                m.put("symbol", s.get("symbol"));
                m.put("sector", s.get("sector"));
                // Named "price" because that is what the order path reads.
                m.put("price", s.get("current_price"));
                m.put("returnPct", s.get("return_pct"));
                m.put("score", s.get("composite_score"));
                list.add(m);
            }
        } catch (Exception e) {
            // Empty, never a stale stored list. A bulk-order flow that silently falls back to old
            // prices is the failure this method was changed to remove, so an error here has to
            // produce nothing to trade rather than something wrong to trade.
            log.warn("Current Top 10 could not be calculated, so no orders will be prepared: {}",
                e.getMessage());
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> paperTrades(User user, String status, int limit) {
        List<Object[]> rows = em.createNativeQuery(
            "SELECT id,symbol,trade_type,qty,entry_price,exit_price,pnl,pnl_pct,sector,source,entry_date,exit_date,status FROM paper_trades WHERE user_id=? AND status=? ORDER BY id DESC LIMIT ?")
            .setParameter(1, user.getId()).setParameter(2, status).setParameter(3, limit).getResultList();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r[0]); m.put("symbol", r[1]); m.put("side", r[2]); m.put("qty", r[3]); m.put("entryPrice", r[4]); m.put("exitPrice", r[5]);
            m.put("pnl", r[6]); m.put("pnlPct", r[7]); m.put("sector", r[8]); m.put("strategyKey", r[9]); m.put("entryDate", r[10]); m.put("exitDate", r[11]); m.put("status", r[12]);
            list.add(m);
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> autoSettings(User user) {
        List<Object[]> rows = em.createNativeQuery("SELECT strategy_key,strategy_name,mode,auto_buy,auto_sell,automated_execution,buy_time,sell_time,investment_amount,max_capital_allocation,max_daily_trades,risk_per_trade_pct,stop_loss_pct,trailing_stop_loss_pct,target_pct,max_daily_loss_limit,max_open_positions,max_stock_allocation,max_sector_allocation,circuit_filter_protection,volume_validation,liquidity_validation,status FROM trading_auto_settings WHERE user_id=? ORDER BY id DESC LIMIT 1")
            .setParameter(1, user.getId()).getResultList();
        if (rows.isEmpty()) {
            return defaultAutoSettings();
        }
        Object[] r = rows.get(0);
        Map<String, Object> m = defaultAutoSettings();
        m.put("strategyKey", r[0]); m.put("strategyName", r[1]); m.put("mode", r[2]); m.put("autoBuy", integer(r[3],0)==1); m.put("autoSell", integer(r[4],0)==1);
        m.put("automatedExecution", integer(r[5],0)==1); m.put("buyTime", r[6]); m.put("sellTime", r[7]); m.put("investmentAmount", r[8]); m.put("maxCapitalAllocation", r[9]);
        m.put("maxDailyTrades", r[10]); m.put("riskPerTradePct", r[11]); m.put("stopLossPct", r[12]); m.put("trailingStopLossPct", r[13]); m.put("targetPct", r[14]);
        m.put("maxDailyLossLimit", r[15]); m.put("maxOpenPositions", r[16]); m.put("maxStockAllocation", r[17]); m.put("maxSectorAllocation", r[18]);
        m.put("circuitFilterProtection", integer(r[19],1)==1); m.put("volumeValidation", integer(r[20],1)==1); m.put("liquidityValidation", integer(r[21],1)==1);
        m.put("active", "active".equalsIgnoreCase(String.valueOf(r[22])));
        return m;
    }

    private Map<String, Object> defaultAutoSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("strategyKey", "stock_tracker"); m.put("strategyName", "Stock Tracker"); m.put("mode", "PAPER");
        m.put("autoBuy", false); m.put("autoSell", false); m.put("automatedExecution", false);
        m.put("buyTime", "09:20"); m.put("sellTime", "15:10"); m.put("investmentAmount", 50000); m.put("maxCapitalAllocation", 200000);
        m.put("maxDailyTrades", 5); m.put("riskPerTradePct", 1.0); m.put("stopLossPct", 3.0); m.put("trailingStopLossPct", 2.0); m.put("targetPct", 6.0);
        m.put("maxDailyLossLimit", 5000); m.put("maxOpenPositions", 10); m.put("maxStockAllocation", 50000); m.put("maxSectorAllocation", 100000);
        m.put("circuitFilterProtection", true); m.put("volumeValidation", true); m.put("liquidityValidation", true); m.put("active", false);
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> auditLogs(User user, int limit) {
        List<Object[]> rows = em.createNativeQuery("SELECT event_type,strategy_name,mode,symbol,side,status,message,broker_name,created_at FROM trading_audit_log WHERE user_id=? ORDER BY id DESC LIMIT ?")
            .setParameter(1, user.getId()).setParameter(2, limit).getResultList();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("eventType", r[0]); m.put("strategyName", r[1]); m.put("mode", r[2]); m.put("symbol", r[3]); m.put("side", r[4]); m.put("status", r[5]); m.put("message", r[6]); m.put("broker", r[7]); m.put("createdAt", r[8]);
            list.add(m);
        }
        return list;
    }

    private List<Map<String, Object>> latestAlerts(User user) {
        List<Map<String, Object>> alerts = new ArrayList<>();
        if (currentTop10(user).isEmpty()) alerts.add(Map.of("type", "Top 10", "message", "Stock Tracker Top 10 is not available yet."));
        if (brokers(user).stream().noneMatch(b -> Boolean.TRUE.equals(b.get("verified")))) alerts.add(Map.of("type", "Broker", "message", "Connect and verify a broker before Real Trading."));
        return alerts;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> checklistTemplates(User user) {
        List<Object[]> rows = em.createNativeQuery(
            "SELECT id,template_name,strategy_key,strategy_name,trade_type,applies_to,enabled,override_allowed,created_at FROM trading_checklist_templates WHERE institution_id=? ORDER BY id DESC")
            .setParameter(1, user.getInstitutionId()).getResultList();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] r : rows) {
            Long id = lng(r[0]);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("templateName", r[1]); m.put("strategyKey", r[2]); m.put("strategyName", r[3]); m.put("tradeType", r[4]);
            m.put("appliesTo", r[5]); m.put("enabled", integer(r[6], 0) == 1); m.put("overrideAllowed", integer(r[7], 0) == 1); m.put("createdAt", r[8]);
            m.put("items", checklistItems(id));
            list.add(m);
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> checklistItems(Long templateId) {
        List<Object[]> rows = em.createNativeQuery("SELECT id,item_text,mandatory,display_order FROM trading_checklist_items WHERE template_id=? ORDER BY display_order,id")
            .setParameter(1, templateId).getResultList();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r[0]); m.put("itemText", r[1]); m.put("mandatory", integer(r[2], 0) == 1); m.put("displayOrder", r[3]);
            items.add(m);
        }
        return items;
    }

    private Map<String, Object> checklistValidation(Map<String, Object> req, User user) {
        String strategyKey = textOr(req.get("strategyKey"), "stock_tracker");
        String side = upper(req.get("side"), "BUY");
        List<Map<String, Object>> templates = matchingChecklists(user, strategyKey, side);
        List<Long> completed = idList(req.get("completedChecklistItemIds"));
        List<Map<String, Object>> missing = new ArrayList<>();
        boolean overrideAllowed = false;
        Long overrideTemplate = null;
        for (Map<String, Object> t : templates) {
            if (Boolean.TRUE.equals(t.get("overrideAllowed"))) overrideAllowed = true;
            overrideTemplate = lng(t.get("id"));
            for (Map<String, Object> item : asMapList(t.get("items"))) {
                Long itemId = lng(item.get("id"));
                if (Boolean.TRUE.equals(item.get("mandatory")) && itemId != null && !completed.contains(itemId)) {
                    missing.add(Map.of("templateName", t.get("templateName"), "itemId", itemId, "itemText", item.get("itemText")));
                }
            }
        }
        String overrideReason = text(req.get("overrideReason"));
        if (!missing.isEmpty() && !overrideReason.isBlank() && overrideAllowed) {
            em.createNativeQuery("INSERT INTO trading_checklist_overrides(template_id,user_id,institution_id,strategy_key,trade_type,symbol,override_reason,trade_details_json,created_at) VALUES(?,?,?,?,?,?,?,?,?)")
                .setParameter(1, overrideTemplate).setParameter(2, user.getId()).setParameter(3, user.getInstitutionId()).setParameter(4, strategyKey)
                .setParameter(5, side).setParameter(6, upper(req.get("symbol"), "")).setParameter(7, overrideReason)
                .setParameter(8, toJson(req)).setParameter(9, DateUtil.now()).executeUpdate();
            return Map.of("blocked", false, "overridden", true, "missing", missing);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("blocked", !missing.isEmpty());
        result.put("overridden", false);
        result.put("missing", missing);
        result.put("templates", templates);
        return result;
    }

    private List<Map<String, Object>> matchingChecklists(User user, String strategyKey, String side) {
        List<Map<String, Object>> matches = new ArrayList<>();
        for (Map<String, Object> t : checklistTemplates(user)) {
            if (!Boolean.TRUE.equals(t.get("enabled"))) continue;
            String tStrategy = text(t.get("strategyKey"));
            String tSide = upper(t.get("tradeType"), "ALL");
            boolean strategyMatches = tStrategy.isBlank() || "ALL".equalsIgnoreCase(tStrategy) || strategyKey.equalsIgnoreCase(tStrategy);
            boolean sideMatches = "ALL".equals(tSide) || side.equalsIgnoreCase(tSide);
            if (strategyMatches && sideMatches) matches.add(t);
        }
        return matches;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> journalRows(User user, String search, String strategy, String outcome, int limit) {
        StringBuilder sql = new StringBuilder("SELECT trade_ref,order_id,created_at,user_name,strategy_name,trading_mode,trade_type,symbol,sector,quantity,buy_price,sell_price,stop_loss,trailing_stop_loss,target_price,capital_utilized,margin_utilized,brokerage_charges,net_profit_loss,outcome,exit_reason,system_remarks,user_remarks FROM trading_journal WHERE user_id=?");
        if (!text(search).isBlank()) sql.append(" AND (UPPER(symbol) LIKE '%").append(escSql(text(search).toUpperCase(Locale.ROOT))).append("%' OR UPPER(stock_name) LIKE '%").append(escSql(text(search).toUpperCase(Locale.ROOT))).append("%')");
        if (!text(strategy).isBlank()) sql.append(" AND strategy_key='").append(escSql(strategy)).append("'");
        if (!text(outcome).isBlank()) sql.append(" AND outcome='").append(escSql(outcome)).append("'");
        sql.append(" ORDER BY id DESC LIMIT ").append(limit);
        List<Object[]> rows = em.createNativeQuery(sql.toString()).setParameter(1, user.getId()).getResultList();
        List<Map<String, Object>> list = new ArrayList<>();
        String[] keys = {"tradeRef", "orderId", "createdAt", "userName", "strategyName", "tradingMode", "tradeType", "symbol", "sector", "quantity", "buyPrice", "sellPrice", "stopLoss", "trailingStopLoss", "targetPrice", "capitalUtilized", "marginUtilized", "brokerageCharges", "netProfitLoss", "outcome", "exitReason", "systemRemarks", "userRemarks"};
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < keys.length; i++) m.put(keys[i], r[i]);
            list.add(m);
        }
        applyLivePnl(user, list);
        return list;
    }

    /**
     * The six tiles above the journal, over the same rows the table shows.
     *
     * <p>These used to be six independent SQL aggregates over net_profit_loss, a column nothing
     * ever updates, so Net P&amp;L and Win Rate read 0 next to a table of live positions. Summing
     * the marked-to-market rows instead keeps the tiles and the rows telling the same story.
     *
     * <p>Win and loss are now decided by the sign of the P&amp;L rather than by the outcome text.
     * Average Profit and Average Loss were always split that way; the rates were not, so a
     * profitable open position counted as neither.
     */
    private Map<String, Object> journalAnalytics(User user) {
        List<Map<String, Object>> rows = journalRows(user, "", "", "", 10000);
        long total = rows.size();
        double netPnl = 0, profitSum = 0, lossSum = 0;
        long wins = 0, losses = 0;
        for (Map<String, Object> r : rows) {
            double pnl = dbl(r.get("netProfitLoss"), 0);
            netPnl += pnl;
            if (pnl > 0) { wins++; profitSum += pnl; }
            else if (pnl < 0) { losses++; lossSum += pnl; }
        }
        double profit = wins > 0 ? profitSum / wins : 0;
        double loss = losses > 0 ? lossSum / losses : 0;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalTrades", total);
        m.put("winRate", total > 0 ? round(wins * 100.0 / total) : 0);
        m.put("lossRate", total > 0 ? round(losses * 100.0 / total) : 0);
        m.put("averageProfit", round(profit));
        m.put("averageLoss", round(loss));
        m.put("netPnl", round(netPnl));
        m.put("strategySuccessRate", total > 0 ? round(wins * 100.0 / total) : 0);
        m.put("riskRewardRatio", Math.abs(loss) > 0 ? round(profit / Math.abs(loss)) : 0);
        return m;
    }

    private void journal(User user, Map<String, Object> req, String mode, String tradeType, String symbol, int qty, double buyPrice, double sellPrice, double pnl, String outcome, String exitReason, String systemRemarks) {
        try {
            String ref = "TJ-" + user.getId() + "-" + DateUtil.today().replace("-", "") + "-" + (q("SELECT COUNT(*) FROM trading_journal") + 1);
            Map<String, Object> calc = orderCalculation(req);
            BrokerageCalculator.Product product = productOf(req.get("productType"));
            String exchange = upper(req.get("exchange"), "NSE");

            // Charges computed here rather than taken from the request body. They used to be
            // dbl(req.get("brokerageCharges"), 0) - whatever the caller sent, and zero for every
            // caller that sent nothing, which was most of them. A one-session delivery round trip
            // costs about 0.30% of the position, so a journal recording zero charges reports a
            // net P&L identical to gross and makes a losing strategy read as a winner.
            //
            // One leg only. This row is one order; the opposite leg gets its own row with its own
            // charges, and adding a round trip here would double-count.
            double price = buyPrice > 0 ? buyPrice : sellPrice;
            double chargeTotal = 0;
            String chargeJson = null;
            if (qty > 0 && price > 0) {
                var leg = brokerage.charges(product,
                    "SELL".equalsIgnoreCase(tradeType)
                        ? BrokerageCalculator.Side.SELL : BrokerageCalculator.Side.BUY,
                    exchange, price, qty, true);
                chargeTotal = leg.total();
                chargeJson = toJsonQuiet(leg.asMap());
            }
            double gross = pnl;
            double net = Math.round((gross - chargeTotal) * 100.0) / 100.0;

            // Only a genuinely placed order carries an idempotency key. journal() is also called
            // for validation failures, margin rejections and exceptions, and giving those a key
            // would have the unique index refuse the user's legitimate retry.
            String idemKey = text(req.get("idempotencyKey"));
            if (idemKey.isBlank()) idemKey = null;

            em.createNativeQuery("INSERT INTO trading_journal(trade_ref,order_id,user_id,institution_id,user_name,strategy_key,strategy_name,trading_mode,trade_type,stock_name,symbol,sector,quantity,buy_price,sell_price,stop_loss,trailing_stop_loss,target_price,capital_utilized,margin_utilized,brokerage_charges,net_profit_loss,exit_reason,outcome,system_remarks,user_remarks,broker_name,broker_response,created_at,updated_at,"
                + "gross_pnl,charges_breakdown,trade_status,filled_qty,pending_qty,broker_order_id,idempotency_key,product_type,signal_at,order_placed_at,is_manual) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
                .setParameter(1, ref).setParameter(2, text(req.get("orderId"))).setParameter(3, user.getId()).setParameter(4, user.getInstitutionId()).setParameter(5, user.getName())
                .setParameter(6, textOr(req.get("strategyKey"), "manual")).setParameter(7, textOr(req.get("strategyName"), "Manual Trade")).setParameter(8, mode).setParameter(9, tradeType)
                .setParameter(10, symbol).setParameter(11, symbol).setParameter(12, text(req.get("sector"))).setParameter(13, qty).setParameter(14, buyPrice).setParameter(15, sellPrice)
                .setParameter(16, calc.get("stopLossPrice")).setParameter(17, calc.get("trailingStopPrice")).setParameter(18, calc.get("targetPrice")).setParameter(19, calc.get("orderValue"))
                .setParameter(20, calc.get("marginUtilizationPct")).setParameter(21, chargeTotal).setParameter(22, net).setParameter(23, exitReason).setParameter(24, outcome)
                .setParameter(25, systemRemarks).setParameter(26, text(req.get("userRemarks"))).setParameter(27, text(req.get("brokerName"))).setParameter(28, text(req.get("brokerResponse")))
                .setParameter(29, DateUtil.now()).setParameter(30, DateUtil.now())
                .setParameter(31, gross)
                .setParameter(32, chargeJson)
                // PLACED, not FILLED. Section 10: a signal is not an order and an order is not a
                // fill. Only the reconciler, having read the broker's own order book, may call
                // something filled.
                .setParameter(33, textOr(req.get("tradeStatus"), "PLACED"))
                .setParameter(34, 0).setParameter(35, qty)
                .setParameter(36, text(req.get("brokerOrderId")))
                .setParameter(37, idemKey)
                .setParameter(38, product.name())
                .setParameter(39, textOr(req.get("signalAt"), DateUtil.now()))
                .setParameter(40, DateUtil.now())
                .setParameter(41, bool(req.get("isManual")) ? 1 : 0)
                .executeUpdate();
        } catch (Exception e) {
            // A unique-key violation here is the idempotency guard doing its job: this logical
            // order is already journalled, and the second attempt being a no-op is the desired
            // outcome. Anything else is a real failure and must not vanish - the previous
            // `catch (Exception ignored) {}` swallowed both alike, so a journal that had stopped
            // recording would have looked exactly like a quiet day.
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("ux_tj_idem") || msg.toLowerCase(Locale.ROOT).contains("duplicate")) {
                log.debug("Journal entry already exists for this order; not recorded twice.");
            } else {
                log.warn("Could not write the trade journal entry for {}: {}", symbol, msg);
            }
        }
    }

    private void audit(User user, String strategyKey, String strategyName, String mode, String eventType, String symbol, String side, String status, String message, String brokerName, String brokerResponse, Object details) {
        try {
            em.createNativeQuery("INSERT INTO trading_audit_log(user_id,institution_id,strategy_key,strategy_name,mode,event_type,symbol,side,status,message,broker_name,broker_response,details_json,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
                .setParameter(1, user.getId()).setParameter(2, user.getInstitutionId()).setParameter(3, strategyKey).setParameter(4, strategyName).setParameter(5, mode)
                .setParameter(6, eventType).setParameter(7, symbol).setParameter(8, side).setParameter(9, status).setParameter(10, message)
                .setParameter(11, brokerName).setParameter(12, brokerResponse).setParameter(13, mapper.writeValueAsString(details)).setParameter(14, DateUtil.now()).executeUpdate();
        } catch (Exception e) {}
    }

    @SuppressWarnings("unchecked")
    private DhanOrderInstrument resolveDhanOrderInstrument(String symbol, String requestedSecurityId, String requestedExchangeSegment) {
        String normalizedSymbol = normalizeDhanSymbol(symbol);
        String securityId = text(requestedSecurityId);
        String exchangeSegment = text(requestedExchangeSegment).toUpperCase(Locale.ROOT);
        if (normalizedSymbol.isBlank()) return null;
        StringBuilder sql = new StringBuilder(
            "SELECT security_id,COALESCE(NULLIF(exchange_segment,''),'NSE_EQ')," +
            "COALESCE(NULLIF(instrument_type,''),NULLIF(instrument,''),'EQUITY'),underlying_symbol," +
            "COALESCE(NULLIF(trading_symbol,''),symbol_name) FROM dhan_instruments " +
            "WHERE security_id IS NOT NULL AND security_id<>'' AND COALESCE(underlying_symbol,'')<>'' " +
            "AND UPPER(COALESCE(exchange_segment,'')) IN ('NSE_EQ','BSE_EQ') ");
        List<Object> params = new ArrayList<>();
        if (!securityId.isBlank()) {
            sql.append("AND security_id=? ");
            params.add(securityId);
        } else {
            sql.append("AND ").append(normalizedDhanSql("underlying_symbol")).append("=? ");
            params.add(normalizedSymbol);
        }
        if (!exchangeSegment.isBlank()) {
            sql.append("AND UPPER(exchange_segment)=? ");
            params.add(exchangeSegment);
        }
        sql.append("ORDER BY CASE WHEN exchange_segment='NSE_EQ' THEN 0 WHEN exchange_segment='BSE_EQ' THEN 1 ELSE 2 END LIMIT 5");
        try {
            var query = em.createNativeQuery(sql.toString());
            for (int i = 0; i < params.size(); i++) query.setParameter(i + 1, params.get(i));
            for (Object[] row : (List<Object[]>) query.getResultList()) {
                String underlying = text(row[3]);
                if (!normalizeDhanSymbol(underlying).equals(normalizedSymbol)) continue;
                return new DhanOrderInstrument(text(row[0]), text(row[1]), text(row[2]), underlying, text(row[4]));
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String normalizeDhanSymbol(String symbol) {
        String s = text(symbol).toUpperCase(Locale.ROOT)
            .replace(".NS", "")
            .replace(".BO", "")
            .replace(" ", "");
        for (String suffix : List.of("-EQ", "-BE", "-BZ", "-SM", "-ST")) {
            if (s.endsWith(suffix)) return s.substring(0, s.length() - suffix.length());
        }
        return s;
    }

    private String normalizedDhanSql(String column) {
        return "UPPER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(TRIM(COALESCE(" + column + ",'')),'.NS',''),'.BO',''),'-EQ',''),'-BE',''),'-BZ',''),'-SM',''),'-ST',''),' ',''))";
    }

    private record DhanOrderInstrument(String securityId, String exchangeSegment, String instrumentType,
                                       String underlyingSymbol, String tradingSymbol) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("securityId", securityId);
            m.put("exchangeSegment", exchangeSegment);
            m.put("instrumentType", instrumentType);
            m.put("underlyingSymbol", underlyingSymbol);
            m.put("tradingSymbol", tradingSymbol);
            return m;
        }
    }

    private String dhanOrderType(String value) {
        String v = upper(value, "MARKET");
        if (v.contains("LIMIT")) return "LIMIT";
        if (v.contains("STOP")) return "STOP_LOSS";
        return "MARKET";
    }
    private String decode(String value) {
        try { return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8); } catch (Exception e) { return value == null ? "" : value; }
    }
    private String textOr(Object v, String fallback) { String s = text(v); return s.isBlank() ? fallback : s; }
    private String csv(Object value) { String s = value == null ? "" : String.valueOf(value); return "\"" + s.replace("\"", "\"\"") + "\""; }
    private String escSql(String s) { return s == null ? "" : s.replace("'", "''"); }
    private String toJson(Object o) { try { return mapper.writeValueAsString(o); } catch (Exception e) { return "{}"; } }
    private String text(Object v) { return v == null ? "" : String.valueOf(v).trim(); }
    /** Accepts a JSON array or a comma/space separated string. */
    @SuppressWarnings("unchecked")
    private static java.util.List<String> stringList(Object raw) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (raw instanceof java.util.List<?> l) {
            for (Object o : l) if (o != null && !String.valueOf(o).isBlank()) out.add(String.valueOf(o).trim());
        } else if (raw != null && !String.valueOf(raw).isBlank()) {
            for (String p : String.valueOf(raw).split("[,\s]+")) if (!p.isBlank()) out.add(p.trim());
        }
        return out;
    }


    /**
     * Serialise a charge breakdown for storage. Returns null on failure rather than throwing: a
     * journal row that loses its itemised charges is worth far more than no journal row at all,
     * and the total is stored in its own column regardless.
     */
    private String toJsonQuiet(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { return null; }
    }
    private String upper(Object v, String fallback) { String s = text(v); return s.isBlank() ? fallback : s.toUpperCase(Locale.ROOT); }
    private boolean bool(Object v) { return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v)) || "1".equals(String.valueOf(v)); }
    private int integer(Object v, int fallback) { try { return v == null ? fallback : ((Number) (v instanceof Number ? v : Double.parseDouble(String.valueOf(v)))).intValue(); } catch (Exception e) { return fallback; } }
    private Long lng(Object v) { try { return v == null || text(v).isBlank() ? null : Long.parseLong(text(v)); } catch (Exception e) { return null; } }
    private double dbl(Object v, double fallback) { try { return v == null || text(v).isBlank() ? fallback : Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return fallback; } }
    private double round(double v) { return Math.round(v * 100.0) / 100.0; }
    private long q(String sql) { try { Object r = em.createNativeQuery(sql).getSingleResult(); return r == null ? 0 : ((Number) r).longValue(); } catch (Exception e) { return 0; } }
    private double qd(String sql) { try { Object r = em.createNativeQuery(sql).getSingleResult(); return r == null ? 0 : ((Number) r).doubleValue(); } catch (Exception e) { return 0; } }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asMapList(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) if (item instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            return out;
        }
        return List.of();
    }

    private List<Long> idList(Object value) {
        List<Long> ids = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                Long id = lng(item);
                if (id != null) ids.add(id);
            }
        }
        return ids;
    }
}
