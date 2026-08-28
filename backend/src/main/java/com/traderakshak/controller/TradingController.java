package com.traderakshak.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.entity.User;
import com.traderakshak.service.BlockingHttpService;
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
    private static final String DHAN_API = "https://api.dhan.co/v2";

    @PersistenceContext
    private EntityManager em;
    private final BlockingHttpService http;
    private final ObjectMapper mapper;
    private final TradingGateClient tradingGate;

    public TradingController(BlockingHttpService http, ObjectMapper mapper, TradingGateClient tradingGate) {
        this.tradingGate = tradingGate;
        this.http = http;
        this.mapper = mapper;
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
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("dhanClientId", String.valueOf(broker[3]));
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> currentTop10(User user) {
        List<Object[]> rows = em.createNativeQuery(
            "SELECT i.rank_position,i.symbol,i.sector,i.current_price,i.return_pct,i.composite_score " +
            "FROM tracker_ranking_items i JOIN tracker_rankings r ON r.run_id=i.run_id " +
            "WHERE r.institution_id=? AND r.user_id=? AND i.user_id=? AND i.is_current_top10=1 " +
            "AND i.run_id=(SELECT run_id FROM tracker_rankings WHERE institution_id=? AND user_id=? ORDER BY run_date DESC,id DESC LIMIT 1) " +
            "ORDER BY i.rank_position LIMIT 10")
            .setParameter(1, user.getInstitutionId())
            .setParameter(2, user.getId())
            .setParameter(3, user.getId())
            .setParameter(4, user.getInstitutionId())
            .setParameter(5, user.getId()).getResultList();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", r[0]); m.put("symbol", r[1]); m.put("sector", r[2]); m.put("price", r[3]); m.put("returnPct", r[4]); m.put("score", r[5]);
            list.add(m);
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
        return list;
    }

    private Map<String, Object> journalAnalytics(User user) {
        long total = q("SELECT COUNT(*) FROM trading_journal WHERE user_id=" + user.getId());
        long wins = q("SELECT COUNT(*) FROM trading_journal WHERE user_id=" + user.getId() + " AND outcome IN ('Successful Trade','Target Achieved','Order Sent')");
        long losses = q("SELECT COUNT(*) FROM trading_journal WHERE user_id=" + user.getId() + " AND outcome IN ('Failed Trade','Stop Loss Hit')");
        double profit = qd("SELECT COALESCE(AVG(NULLIF(net_profit_loss,0)),0) FROM trading_journal WHERE user_id=" + user.getId() + " AND net_profit_loss>0");
        double loss = qd("SELECT COALESCE(AVG(NULLIF(net_profit_loss,0)),0) FROM trading_journal WHERE user_id=" + user.getId() + " AND net_profit_loss<0");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalTrades", total);
        m.put("winRate", total > 0 ? round(wins * 100.0 / total) : 0);
        m.put("lossRate", total > 0 ? round(losses * 100.0 / total) : 0);
        m.put("averageProfit", round(profit));
        m.put("averageLoss", round(loss));
        m.put("netPnl", round(qd("SELECT COALESCE(SUM(net_profit_loss),0) FROM trading_journal WHERE user_id=" + user.getId())));
        m.put("strategySuccessRate", total > 0 ? round(wins * 100.0 / total) : 0);
        m.put("riskRewardRatio", Math.abs(loss) > 0 ? round(profit / Math.abs(loss)) : 0);
        return m;
    }

    private void journal(User user, Map<String, Object> req, String mode, String tradeType, String symbol, int qty, double buyPrice, double sellPrice, double pnl, String outcome, String exitReason, String systemRemarks) {
        try {
            String ref = "TJ-" + user.getId() + "-" + DateUtil.today().replace("-", "") + "-" + (q("SELECT COUNT(*) FROM trading_journal") + 1);
            Map<String, Object> calc = orderCalculation(req);
            em.createNativeQuery("INSERT INTO trading_journal(trade_ref,order_id,user_id,institution_id,user_name,strategy_key,strategy_name,trading_mode,trade_type,stock_name,symbol,sector,quantity,buy_price,sell_price,stop_loss,trailing_stop_loss,target_price,capital_utilized,margin_utilized,brokerage_charges,net_profit_loss,exit_reason,outcome,system_remarks,user_remarks,broker_name,broker_response,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
                .setParameter(1, ref).setParameter(2, text(req.get("orderId"))).setParameter(3, user.getId()).setParameter(4, user.getInstitutionId()).setParameter(5, user.getName())
                .setParameter(6, textOr(req.get("strategyKey"), "manual")).setParameter(7, textOr(req.get("strategyName"), "Manual Trade")).setParameter(8, mode).setParameter(9, tradeType)
                .setParameter(10, symbol).setParameter(11, symbol).setParameter(12, text(req.get("sector"))).setParameter(13, qty).setParameter(14, buyPrice).setParameter(15, sellPrice)
                .setParameter(16, calc.get("stopLossPrice")).setParameter(17, calc.get("trailingStopPrice")).setParameter(18, calc.get("targetPrice")).setParameter(19, calc.get("orderValue"))
                .setParameter(20, calc.get("marginUtilizationPct")).setParameter(21, dbl(req.get("brokerageCharges"), 0)).setParameter(22, pnl).setParameter(23, exitReason).setParameter(24, outcome)
                .setParameter(25, systemRemarks).setParameter(26, text(req.get("userRemarks"))).setParameter(27, text(req.get("brokerName"))).setParameter(28, text(req.get("brokerResponse")))
                .setParameter(29, DateUtil.now()).setParameter(30, DateUtil.now()).executeUpdate();
        } catch (Exception ignored) {}
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
