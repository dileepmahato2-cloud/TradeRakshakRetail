package com.traderakshak.service;

// =============================================================================
// StrategyBuilderService — manages all strategy operations:
// create, save conditions, run scan, version, copy, marketplace, live alerts,
// AI generation, performance health monitoring
// =============================================================================

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traderakshak.entity.Strategy;
import com.traderakshak.entity.User;
import com.traderakshak.exception.TradeRakshakException;
import com.traderakshak.repository.StrategyRepository;
import com.traderakshak.util.DateUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyBuilderService {

    private final StrategyRepository          stratRepo;
    private final StrategyConditionEvaluator  evaluator;
    private final BacktestEngine              backtestEngine;
    private final SectorTrendService          sectorService;
    private final AuditService                auditService;
    private final AppConfigService            appConfig;
    private final ObjectMapper                objectMapper;
    private final NextDayPredictionService    nextDayPredictionService;

    @PersistenceContext
    private EntityManager em;

    // ── Strategy CRUD ─────────────────────────────────────────────────────────

    @Transactional
    public Strategy createStrategy(User user, Map<String, Object> req) {
        String name = (String) req.get("name");
        if (name == null || name.isBlank())
            throw new TradeRakshakException("STR-001", "Strategy name is required.");

        int builderLevel = getInt(req, "builderLevel", 1);

        Strategy strategy = Strategy.builder()
            .userId(user.getId())
            .institutionId(user.getInstitutionId())
            .name(name)
            .description((String) req.getOrDefault("description", ""))
            .builderLevel(builderLevel)
            .segment((String) req.getOrDefault("segment", "EQ-SWG"))
            .strategyCategory((String) req.getOrDefault("strategyCategory", "swing"))
            .strategyType((String) req.getOrDefault("strategyType", "technical"))
            .timeframe((String) req.getOrDefault("timeframe", "Daily"))
            .universe((String) req.getOrDefault("universe", "NSE_500"))
            .conditionsJson(toJson(req.get("conditions")))
            .entryConditionsJson((String) req.getOrDefault("entryLogicText", ""))
            .exitConditionsJson((String) req.getOrDefault("exitLogicText", ""))
            .riskManagementJson(toJson(req.get("riskManagement")))
            .positionSizingJson(toJson(req.get("positionSizing")))
            .multiTimeframeJson(toJson(req.get("multiTimeframe")))
            .visualJson(toJson(req.get("visualBlocks")))
            .stopLossPct(getDouble(req, "stopLossPct", appConfig.getDouble("strategy.default_stop_loss_pct", 5.0)))
            .targetPct(getDouble(req, "targetPct", appConfig.getDouble("strategy.default_target_pct", 10.0)))
            .rrMin(getDouble(req, "rrMin", appConfig.getDouble("strategy.default_rr_min", 2.0)))
            .maxPositions(getInt(req, "maxPositions", appConfig.getInt("strategy.default_max_positions", 5)))
            .maxPerSector(getInt(req, "maxPerSector", appConfig.getInt("strategy.default_max_per_sector", 2)))
            .riskPerTradePct(getDouble(req, "riskPerTradePct", appConfig.getDouble("strategy.default_risk_per_trade_pct", 2.0)))
            .holdingDays(getInt(req, "holdingDays", appConfig.getInt("strategy.default_holding_days", 22)))
            .tradeWindowStart((String) req.getOrDefault("tradeWindowStart", "15:10"))
            .tradeWindowEnd((String) req.getOrDefault("tradeWindowEnd", "15:20"))
            .useAiFilter(getInt(req, "useAiFilter", 0))
            .aiMinScore(getDouble(req, "aiMinScore", 60.0))
            .tags((String) req.getOrDefault("tags", ""))
            .workflowStatus("draft")
            .version(1)
            .status("active")
            .createdAt(DateUtil.now())
            .updatedAt(DateUtil.now())
            .build();

        // Fix: ensure target >= SL × RR
        if (strategy.getTargetPct() < strategy.getStopLossPct() * strategy.getRrMin()) {
            strategy.setTargetPct(Math.round(strategy.getStopLossPct() * strategy.getRrMin() * 100.0) / 100.0);
        }

        Strategy saved = stratRepo.save(strategy);

        // Save individual conditions
        saveConditions(saved.getId(), req);

        // Save first version snapshot
        saveVersion(saved, "Initial version");

        auditService.logActivity(user.getId(), "StrategyBuilder", "Create", "success",
            "Strategy: " + name, "", "");

        return saved;
    }

    @Transactional
    public Strategy updateStrategy(User user, Long id, Map<String, Object> req) {
        Strategy existing = stratRepo.findById(id)
            .orElseThrow(() -> new TradeRakshakException("STR-404", "Strategy not found."));

        if (!existing.getUserId().equals(user.getId()) && !user.isAdmin())
            throw new TradeRakshakException("STR-403", "Not authorised to edit this strategy.");

        // Save version before update
        saveVersion(existing, "Before update v" + (existing.getVersion() != null ? existing.getVersion() + 1 : 2));

        existing.setName((String) req.getOrDefault("name", existing.getName()));
        existing.setDescription((String) req.getOrDefault("description", existing.getDescription()));
        existing.setConditionsJson(toJson(req.getOrDefault("conditions", null)));
        existing.setEntryConditionsJson((String) req.getOrDefault("entryLogicText", existing.getEntryConditionsJson()));
        existing.setExitConditionsJson((String) req.getOrDefault("exitLogicText", existing.getExitConditionsJson()));
        existing.setRiskManagementJson(toJson(req.getOrDefault("riskManagement", null)));
        existing.setStopLossPct(getDouble(req, "stopLossPct", existing.getStopLossPct() != null ? existing.getStopLossPct() : 5.0));
        existing.setTargetPct(getDouble(req, "targetPct", existing.getTargetPct() != null ? existing.getTargetPct() : 10.0));
        existing.setRrMin(getDouble(req, "rrMin", existing.getRrMin() != null ? existing.getRrMin() : 2.0));
        existing.setMaxPositions(getInt(req, "maxPositions", existing.getMaxPositions() != null ? existing.getMaxPositions() : 5));
        existing.setTags((String) req.getOrDefault("tags", existing.getTags()));
        existing.setVersion((existing.getVersion() != null ? existing.getVersion() : 1) + 1);
        existing.setUpdatedAt(DateUtil.now());

        // Re-save conditions
        em.createNativeQuery("DELETE FROM strategy_conditions WHERE strategy_id=:id")
            .setParameter("id", id).executeUpdate();
        saveConditions(id, req);

        return stratRepo.save(existing);
    }

    // ── Run Screener (scan universe for signals) ──────────────────────────────

    @Transactional
    public Map<String, Object> runScreener(Strategy strategy, int maxResults) {
        long start = System.currentTimeMillis();
        List<String> symbols = getUniverse(strategy.getUniverse(), strategy.getSegment());
        List<Map<String, Object>> conditions = loadConditions(strategy.getId(), "entry");

        if (conditions.isEmpty() && strategy.getConditionsJson() != null) {
            // Fallback: parse conditions from JSON
            conditions = parseConditionsJson(strategy.getConditionsJson());
        }

        List<Map<String, Object>> results = new ArrayList<>();

        for (String sym : symbols) {
            try {
                boolean pass = evaluator.evaluate(conditions, sym, "NSE", "entry");
                if (!pass) continue;

                // Get latest data for the result row
                Object[] row = getLatestData(sym, "NSE");
                if (row == null) continue;

                double cmp      = row[0] != null ? ((Number)row[0]).doubleValue() : 0;
                double prevClose= row[1] != null ? ((Number)row[1]).doubleValue() : cmp;
                double chgPct   = prevClose > 0 ? Math.round((cmp-prevClose)/prevClose*10000.0)/100.0 : 0;
                double vol      = row[2] != null ? ((Number)row[2]).doubleValue() : 0;
                double delPct   = row[3] != null ? ((Number)row[3]).doubleValue() : 0;
                double sl       = Math.round(cmp * (1 - strategy.getStopLossPct()/100) * 100) / 100.0;
                double tgt      = Math.round(cmp * (1 + strategy.getTargetPct()/100) * 100) / 100.0;
                double rr       = cmp > sl ? Math.round((tgt-cmp)/(cmp-sl)*100)/100.0 : 0;

                Map<String, Object> res = new LinkedHashMap<>();
                res.put("symbol",      sym);
                res.put("cmp",         cmp);
                res.put("changePct",   chgPct);
                res.put("volume",      vol);
                res.put("deliveryPct", delPct);
                res.put("stopLoss",    sl);
                res.put("target",      tgt);
                res.put("rrRatio",     rr);
                res.put("strategyId",  strategy.getId());
                res.put("scanDate",    DateUtil.today());
                results.add(res);

                if (results.size() >= maxResults) break;
            } catch (Exception ignored) {}
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("Screener '{}': {} matches from {} symbols in {}ms",
                 strategy.getName(), results.size(), symbols.size(), elapsed);

        // Persist results
        persistScreenerResults(strategy.getId(), results);

        return Map.of(
            "strategy",    strategy.getName(),
            "results",     results,
            "totalScanned",symbols.size(),
            "matched",     results.size(),
            "scanTime",    elapsed + "ms",
            "scanDate",    DateUtil.today()
        );
    }

    // ── Backtest ──────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> backtest(Strategy strategy, String fromDate, String toDate,
                                         double initialCapital, double slippage, double brokerage) {
        double slPct  = strategy.getStopLossPct()  != null ? strategy.getStopLossPct()  : slippage;
        double tgtPct = strategy.getTargetPct()    != null ? strategy.getTargetPct()    : 10.0;
        Map<String, Object> result = backtestEngine.run(
            strategy.getId(), fromDate, toDate, initialCapital,
            1, slPct, tgtPct, brokerage, strategy.getUniverse());

        // Persist to backtests table
        try {
            em.createNativeQuery(
                "INSERT INTO backtests(strategy_id,user_id,institution_id,backtest_name,exchange_scope," +
                "segment,from_date,to_date,initial_capital,slippage_pct,brokerage_pct," +
                "total_trades,wins,losses,win_rate,net_pnl,gross_profit,gross_loss," +
                "profit_factor,max_drawdown,cagr,sharpe_ratio,sortino_ratio,calmar_ratio," +
                "expectancy,avg_profit_per_trade,avg_loss_per_trade,best_trade,worst_trade," +
                "avg_holding_days,total_days,monthly_pnl_json,status,created_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
                .setParameter(1, strategy.getId()).setParameter(2, strategy.getUserId())
                .setParameter(3, strategy.getInstitutionId())
                .setParameter(4, strategy.getName() + " — Backtest " + DateUtil.today())
                .setParameter(5, "NSE").setParameter(6, strategy.getSegment())
                .setParameter(7, fromDate).setParameter(8, toDate)
                .setParameter(9, initialCapital).setParameter(10, slippage).setParameter(11, brokerage)
                .setParameter(12, result.get("totalTrades")).setParameter(13, result.get("wins"))
                .setParameter(14, result.get("losses")).setParameter(15, result.get("winRate"))
                .setParameter(16, result.get("netPnl")).setParameter(17, result.get("grossProfit"))
                .setParameter(18, result.get("grossLoss")).setParameter(19, result.get("profitFactor"))
                .setParameter(20, result.get("maxDrawdown")).setParameter(21, result.get("cagr"))
                .setParameter(22, result.get("sharpeRatio")).setParameter(23, result.get("sortinoRatio"))
                .setParameter(24, result.get("calmarRatio")).setParameter(25, result.get("expectancy"))
                .setParameter(26, result.get("avgProfitPerTrade")).setParameter(27, result.get("avgLossPerTrade"))
                .setParameter(28, result.get("bestTrade")).setParameter(29, result.get("worstTrade"))
                .setParameter(30, result.get("avgHoldingDays")).setParameter(31, result.get("totalDays"))
                .setParameter(32, toJson(result.get("monthlyPnl"))).setParameter(33, "completed")
                .setParameter(34, DateUtil.now()).executeUpdate();

            // Update strategy win rate
            strategy.setWinRate((Double) result.get("winRate"));
            strategy.setBacktested(1);
            strategy.setWorkflowStatus("backtesting");
            strategy.setUpdatedAt(DateUtil.now());
            stratRepo.save(strategy);

        } catch (Exception e) {
            log.error("Backtest persist error: {}", e.getMessage());
        }

        return result;
    }

    // ── AI Strategy Generation ────────────────────────────────────────────────

    public Map<String, Object> generateAiStrategy(User user, String prompt, String segment,
                                                    String category, Double targetWinRate,
                                                    String universe) {
        if (nextDayPredictionService.isNextDayPredictionPrompt(prompt)) {
            Map<String, Object> generated = nextDayPredictionService.generatedStrategyPayload(
                prompt, segment, category, universe, targetWinRate);
            logAiRequest(user, prompt, segment, category, targetWinRate, universe);
            return generated;
        }

        // Parse user intent from prompt
        String lp = prompt == null ? "" : prompt.toLowerCase();
        boolean swing = lp.contains("swing");
        boolean intra = lp.contains("intraday");
        boolean weekly = lp.contains("weekly");
        boolean momentum = lp.contains("momentum") || lp.contains("rsi");
        boolean trend = lp.contains("trend") || lp.contains("ema");
        boolean breakout = lp.contains("breakout");
        boolean sector = lp.contains("sector");

        // Generate conditions based on intent
        List<Map<String, Object>> conditions = new ArrayList<>();
        List<String> entryRules = new ArrayList<>();
        List<String> exitRules  = new ArrayList<>();

        // Always add sector AI filter if sector mentioned
        if (sector) {
            conditions.add(Map.of("group","entry","operator","AND",
                "indicatorLeft","SECTOR_SCORE","comparator",">=","valueRight",65));
            entryRules.add("Sector Score ≥ 65 (AI sector rotation)");
        }

        // RSI momentum
        if (momentum || (!trend && !breakout)) {
            conditions.add(Map.of("group","entry","operator","AND",
                "indicatorLeft","RSI","paramsLeft",Map.of("period",14),"comparator",">=","valueRight",45));
            conditions.add(Map.of("group","entry","operator","AND",
                "indicatorLeft","RSI","paramsLeft",Map.of("period",14),"comparator","<=","valueRight",65));
            entryRules.add("RSI(14) between 45-65 (healthy momentum, not overbought)");
        }

        // Trend alignment
        if (trend || swing || weekly) {
            conditions.add(Map.of("group","entry","operator","AND",
                "indicatorLeft","EMA","paramsLeft",Map.of("period",20),
                "comparator",">","indicatorRight","EMA","paramsRight",Map.of("period",50)));
            conditions.add(Map.of("group","entry","operator","AND",
                "indicatorLeft","CLOSE","comparator",">","indicatorRight","EMA","paramsRight",Map.of("period",20)));
            entryRules.add("EMA(20) > EMA(50) — uptrend confirmed");
            entryRules.add("Close > EMA(20) — price above support");
        }

        // Breakout
        if (breakout) {
            conditions.add(Map.of("group","entry","operator","AND",
                "indicatorLeft","CLOSE","comparator","CROSSABOVE","indicatorRight","PREV_HIGH"));
            entryRules.add("Close CROSSABOVE 20-day high (breakout)");
        }

        // Volume confirmation (always)
        conditions.add(Map.of("group","entry","operator","AND",
            "indicatorLeft","VOLUME","comparator",">","indicatorRight","VOLUME_SMA",
            "paramsRight",Map.of("period",20),"multiplier",1.5));
        entryRules.add("Volume > 1.5× 20-day SMA (institutional confirmation)");

        // Delivery for equity
        if (!intra && (segment == null || segment.contains("EQ"))) {
            conditions.add(Map.of("group","entry","operator","AND",
                "indicatorLeft","DELIVERY_PCT","comparator",">=","valueRight",50));
            entryRules.add("Delivery% ≥ 50% (genuine buying)");
        }

        // Exit rules
        exitRules.add("RSI(14) > 72 OR EMA(20) < EMA(50)");
        exitRules.add("Trailing SL: 2× ATR below highest high");

        // Risk management
        Map<String, Object> riskMgmt = Map.of(
            "riskPerTrade", targetWinRate != null && targetWinRate >= 65 ? 1.5 : 2.0,
            "maxPositions", intra ? 3 : 8,
            "dailyLossLimit", intra ? 2 : 3,
            "trailingSL", "atr",
            "atrMultiplier", 2.0
        );

        double sl  = intra ? 0.8 : swing ? 4.0 : 5.0;
        double tgt = intra ? 1.5 : swing ? 10.0 : 12.0;

        // Build response
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategyName", "AI: " + (swing?"Swing ":intra?"Intraday ":"") +
                   (sector?"Sector Rotation ":momentum?"Momentum ":trend?"Trend ":breakout?"Breakout ":"") +
                   "Strategy");
        result.put("prompt",          prompt);
        result.put("segment",         segment != null ? segment : "EQ-SWG");
        result.put("category",        category != null ? category : (intra?"intraday":"swing"));
        result.put("timeframe",       weekly ? "Weekly" : intra ? "15min" : "Daily");
        result.put("universe",        universe != null ? universe : "NSE_500");
        result.put("conditions",      conditions);
        result.put("entryRules",      entryRules);
        result.put("exitRules",       exitRules);
        result.put("riskManagement",  riskMgmt);
        result.put("stopLossPct",     sl);
        result.put("targetPct",       tgt);
        result.put("rrMin",           tgt / sl);
        result.put("maxPositions",    intra ? 3 : 8);
        result.put("riskPerTradePct", riskMgmt.get("riskPerTrade"));
        result.put("estimatedWinRate",targetWinRate != null ? targetWinRate : 62.0);
        result.put("aiGenerated",     true);
        result.put("explanation",     buildExplanation(entryRules, exitRules));
        result.put("generatedAt",     DateUtil.now());

        logAiRequest(user, prompt, segment, category, targetWinRate, universe);

        return result;
    }

    private void logAiRequest(User user, String prompt, String segment, String category,
                              Double targetWinRate, String universe) {
        try {
            em.createNativeQuery(
                "INSERT INTO ai_strategy_requests(user_id,institution_id,prompt,segment,category,target_win_rate,universe,status,created_at) VALUES(?,?,?,?,?,?,?,'completed',?)")
                .setParameter(1, user.getId()).setParameter(2, user.getInstitutionId())
                .setParameter(3, prompt).setParameter(4, segment).setParameter(5, category)
                .setParameter(6, targetWinRate).setParameter(7, universe)
                .setParameter(8, DateUtil.now()).executeUpdate();
        } catch (Exception e) { log.debug("AI log error: {}", e.getMessage()); }
    }

    // ── Live Alert Management ─────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> createAlert(User user, Long strategyId, Map<String, Object> req) {
        Strategy strategy = stratRepo.findById(strategyId).orElseThrow();
        try {
            em.createNativeQuery(
                "INSERT INTO live_alerts(strategy_id,user_id,institution_id,alert_name,universe,segment," +
                "timeframe,frequency,channels_json,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")
                .setParameter(1, strategyId).setParameter(2, user.getId())
                .setParameter(3, user.getInstitutionId())
                .setParameter(4, req.getOrDefault("alertName", strategy.getName() + " Alert"))
                .setParameter(5, req.getOrDefault("universe", strategy.getUniverse()))
                .setParameter(6, req.getOrDefault("segment",  strategy.getSegment()))
                .setParameter(7, req.getOrDefault("timeframe", strategy.getTimeframe()))
                .setParameter(8, req.getOrDefault("frequency", "eod"))
                .setParameter(9, toJson(req.getOrDefault("channels", List.of("in_app"))))
                .setParameter(10, "active")
                .setParameter(11, DateUtil.now()).setParameter(12, DateUtil.now())
                .executeUpdate();
        } catch (Exception e) {
            throw new TradeRakshakException("ALT-500", "Failed to create alert: " + e.getMessage());
        }
        return Map.of("created", true, "strategy", strategy.getName(),
                      "message", "Alert active! You will receive signals when conditions are met.");
    }

    // ── Strategy Versioning ───────────────────────────────────────────────────

    @Transactional
    public void saveVersion(Strategy strategy, String changeSummary) {
        try {
            em.createNativeQuery(
                "INSERT INTO strategy_versions(strategy_id,version,conditions_json,risk_json," +
                "change_summary,win_rate,created_by,created_at) VALUES(?,?,?,?,?,?,?,?)")
                .setParameter(1, strategy.getId())
                .setParameter(2, strategy.getVersion() != null ? strategy.getVersion() : 1)
                .setParameter(3, strategy.getConditionsJson())
                .setParameter(4, strategy.getRiskManagementJson())
                .setParameter(5, changeSummary).setParameter(6, strategy.getWinRate())
                .setParameter(7, strategy.getUserId()).setParameter(8, DateUtil.now())
                .executeUpdate();
        } catch (Exception e) { log.debug("Save version error: {}", e.getMessage()); }
    }

    // ── Marketplace ───────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> publishToMarketplace(User user, Long strategyId, Map<String, Object> req) {
        Strategy s = stratRepo.findById(strategyId).orElseThrow();
        if (!s.getUserId().equals(user.getId()))
            throw new TradeRakshakException("MKT-403", "Not authorised.");

        try {
            em.createNativeQuery(
                "INSERT INTO marketplace_listings(strategy_id,seller_user_id,institution_id," +
                "listing_title,listing_desc,price,is_free,win_rate_claimed,category,tags," +
                "status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT (strategy_id) DO UPDATE SET seller_user_id=EXCLUDED.seller_user_id, " +
                "institution_id=EXCLUDED.institution_id, listing_title=EXCLUDED.listing_title, listing_desc=EXCLUDED.listing_desc, " +
                "price=EXCLUDED.price, is_free=EXCLUDED.is_free, win_rate_claimed=EXCLUDED.win_rate_claimed, " +
                "category=EXCLUDED.category, tags=EXCLUDED.tags, status=EXCLUDED.status, updated_at=EXCLUDED.updated_at")
                .setParameter(1, strategyId).setParameter(2, user.getId())
                .setParameter(3, user.getInstitutionId())
                .setParameter(4, req.getOrDefault("title", s.getName()))
                .setParameter(5, req.getOrDefault("description", s.getDescription()))
                .setParameter(6, req.getOrDefault("price", 0)).setParameter(7, req.getOrDefault("isFree", 1))
                .setParameter(8, s.getWinRate()).setParameter(9, s.getStrategyCategory())
                .setParameter(10, s.getTags()).setParameter(11, "pending")
                .setParameter(12, DateUtil.now()).setParameter(13, DateUtil.now())
                .executeUpdate();
        } catch (Exception e) {
            throw new TradeRakshakException("MKT-500", "Publish failed: " + e.getMessage());
        }
        s.setIsMarketplace(1); s.setUpdatedAt(DateUtil.now());
        stratRepo.save(s);
        return Map.of("published", true, "status", "pending_approval",
                      "message", "Strategy submitted for review. Will be visible after approval.");
    }

    @Transactional
    public Strategy copyStrategy(User buyer, Long listingId) {
        Object[] listing = (Object[]) em.createNativeQuery(
            "SELECT ml.strategy_id, ml.price, s.* FROM marketplace_listings ml " +
            "JOIN strategies s ON s.id=ml.strategy_id WHERE ml.id=:id AND ml.status='approved'")
            .setParameter("id", listingId).getSingleResult();

        Strategy original = stratRepo.findById(((Number)listing[0]).longValue()).orElseThrow();

        // Copy strategy for the buyer
        Strategy copy = Strategy.builder()
            .userId(buyer.getId()).institutionId(buyer.getInstitutionId())
            .name(original.getName() + " (Copy)")
            .description(original.getDescription())
            .builderLevel(original.getBuilderLevel())
            .segment(original.getSegment()).strategyCategory(original.getStrategyCategory())
            .timeframe(original.getTimeframe()).universe(original.getUniverse())
            .conditionsJson(original.getConditionsJson())
            .entryConditionsJson(original.getEntryConditionsJson())
            .exitConditionsJson(original.getExitConditionsJson())
            .riskManagementJson(original.getRiskManagementJson())
            .stopLossPct(original.getStopLossPct()).targetPct(original.getTargetPct())
            .rrMin(original.getRrMin()).maxPositions(original.getMaxPositions())
            .riskPerTradePct(original.getRiskPerTradePct())
            .parentId(original.getId())
            .workflowStatus("draft").version(1).status("active")
            .createdAt(DateUtil.now()).updatedAt(DateUtil.now())
            .build();
        Strategy saved = stratRepo.save(copy);

        // Log purchase
        try {
            em.createNativeQuery("INSERT INTO marketplace_purchases(listing_id,strategy_id,buyer_user_id,copied_strategy_id,purchased_at) VALUES(?,?,?,?,?)")
                .setParameter(1, listingId).setParameter(2, original.getId())
                .setParameter(3, buyer.getId()).setParameter(4, saved.getId())
                .setParameter(5, DateUtil.now()).executeUpdate();
            em.createNativeQuery("UPDATE marketplace_listings SET total_copies=total_copies+1 WHERE id=:id")
                .setParameter("id", listingId).executeUpdate();
        } catch (Exception ignored) {}

        return saved;
    }

    // ── Strategy Health Monitoring ────────────────────────────────────────────

    public Map<String, Object> getStrategyHealth(Long strategyId) {
        Strategy s = stratRepo.findById(strategyId).orElse(null);
        if (s == null) return Map.of("error", "Strategy not found");

        // Compute health based on recent performance vs backtest
        double backtestWR = s.getWinRate() != null ? s.getWinRate() : 0;
        double recentWR   = getRecentWinRate(strategyId, 3); // last 3 months
        double decay      = backtestWR > 0 ? Math.max(0, backtestWR - recentWR) : 0;

        String status = decay < 5 ? "healthy" : decay < 15 ? "degrading" : "critical";
        int score     = (int) Math.max(0, 100 - decay * 3);
        String color  = score >= 80 ? "green" : score >= 60 ? "yellow" : "red";

        java.util.Map<String,Object> _hs = new java.util.LinkedHashMap<>();
        _hs.put("strategyId", strategyId);
        _hs.put("strategyName", s.getName());
        _hs.put("backtestWR", backtestWR);
        _hs.put("recentWR", recentWR);
        _hs.put("decay", Math.round(decay * 10) / 10.0);
        _hs.put("healthScore", score);
        _hs.put("healthStatus", status);
        _hs.put("color", color);
        _hs.put("recommendation", score >= 80 ? "Strategy performing as expected."
            : score >= 60 ? "Strategy showing mild decay — monitor closely."
            : "Strategy performance has degraded significantly — consider reoptimizing.");
        _hs.put("computedAt", DateUtil.now());
        return _hs;
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void saveConditions(Long stratId, Map<String, Object> req) {
        Object conds = req.get("conditions");
        if (!(conds instanceof List)) return;
        List<Map<String, Object>> list = (List<Map<String, Object>>) conds;
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> c = list.get(i);
            try {
                em.createNativeQuery(
                    "INSERT INTO strategy_conditions(strategy_id,condition_group,group_operator," +
                    "condition_order,operator,indicator_left,params_left,comparator," +
                    "indicator_right,params_right,value_right,multiplier,created_at) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")
                    .setParameter(1, stratId)
                    .setParameter(2, c.getOrDefault("group","entry"))
                    .setParameter(3, c.getOrDefault("groupOperator","AND"))
                    .setParameter(4, i)
                    .setParameter(5, c.getOrDefault("operator","AND"))
                    .setParameter(6, c.get("indicatorLeft"))
                    .setParameter(7, toJson(c.get("paramsLeft")))
                    .setParameter(8, c.get("comparator"))
                    .setParameter(9, c.get("indicatorRight"))
                    .setParameter(10, toJson(c.get("paramsRight")))
                    .setParameter(11, c.getOrDefault("valueRight",0))
                    .setParameter(12, c.getOrDefault("multiplier",1.0))
                    .setParameter(13, DateUtil.now())
                    .executeUpdate();
            } catch (Exception e) { log.debug("Save condition error: {}", e.getMessage()); }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadConditions(Long stratId, String group) {
        List<Object[]> rows = em.createNativeQuery(
            "SELECT condition_group, operator, indicator_left, params_left, comparator, " +
            "indicator_right, params_right, value_right, multiplier, group_operator " +
            "FROM strategy_conditions WHERE strategy_id=:id ORDER BY condition_order")
            .setParameter("id", stratId).getResultList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> c = new HashMap<>();
            c.put("group",          r[0]); c.put("operator",     r[1]);
            c.put("indicatorLeft",  r[2]); c.put("paramsLeft",   parseJson(r[3]));
            c.put("comparator",     r[4]); c.put("indicatorRight",r[5]);
            c.put("paramsRight",    parseJson(r[6]));
            c.put("valueRight",     r[7] != null ? ((Number)r[7]).doubleValue() : 0);
            c.put("multiplier",     r[8] != null ? ((Number)r[8]).doubleValue() : 1.0);
            c.put("groupOperator",  r[9]);
            result.add(c);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseConditionsJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, List.class); }
        catch (Exception e) { return List.of(); }
    }

    private List<String> getUniverse(String universe, String segment) {
        String q;
        if      ("NSE_50".equals(universe))  q = "nifty50_flag=1";
        else if ("NSE_500".equals(universe)) q = "nifty500_flag=1";
        else q = "segment='Equity'";
        try {
            @SuppressWarnings("unchecked")
            List<String> syms = em.createNativeQuery(
                "SELECT DISTINCT symbol FROM stock_master WHERE " + q +
                " AND exchange='NSE' AND status='active' LIMIT 500").getResultList();
            return syms;
        } catch (Exception e) { return List.of(); }
    }

    private Object[] getLatestData(String sym, String ex) {
        try {
            return (Object[]) em.createNativeQuery(
                "SELECT close, prev_close, volume, delivery_pct FROM market_data " +
                "WHERE symbol=:s AND exchange=:e ORDER BY trade_date DESC LIMIT 1")
                .setParameter("s",sym).setParameter("e",ex).getSingleResult();
        } catch (Exception e) { return null; }
    }

    @Transactional
    private void persistScreenerResults(Long stratId, List<Map<String, Object>> results) {
        for (Map<String, Object> r : results) {
            try {
                em.createNativeQuery(
                    "INSERT INTO scanner_results(strategy_id,exchange,symbol,cmp,change_pct," +
                    "volume,delivery_pct,stop_loss_price,stop_loss_pct,target_price,target_pct," +
                    "rr_ratio,status,scan_date) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'active',?)")
                    .setParameter(1,stratId).setParameter(2,"NSE").setParameter(3,r.get("symbol"))
                    .setParameter(4,r.get("cmp")).setParameter(5,r.get("changePct"))
                    .setParameter(6,r.get("volume")).setParameter(7,r.get("deliveryPct"))
                    .setParameter(8,r.get("stopLoss")).setParameter(9,((Number)r.get("stopLoss")).doubleValue())
                    .setParameter(10,r.get("target")).setParameter(11,((Number)r.get("target")).doubleValue())
                    .setParameter(12,r.get("rrRatio")).setParameter(13,DateUtil.today())
                    .executeUpdate();
            } catch (Exception ignored) {}
        }
    }

    private double getRecentWinRate(Long stratId, int months) {
        try {
            Object r = em.createNativeQuery(
                "SELECT AVG(win_rate) FROM strategy_performance WHERE strategy_id=:id ORDER BY period DESC LIMIT :m")
                .setParameter("id",stratId).setParameter("m",months).getSingleResult();
            return r != null ? ((Number)r).doubleValue() : 0;
        } catch (Exception e) { return 0; }
    }

    private String buildExplanation(List<String> entryRules, List<String> exitRules) {
        StringBuilder sb = new StringBuilder("This AI-generated strategy uses:\n\nENTRY CONDITIONS:\n");
        for (int i = 0; i < entryRules.size(); i++)
            sb.append((i+1)+". "+entryRules.get(i)+"\n");
        sb.append("\nEXIT CONDITIONS:\n");
        for (int i = 0; i < exitRules.size(); i++)
            sb.append((i+1)+". "+exitRules.get(i)+"\n");
        sb.append("\n⚠ R4: TradeRakshak™ is NOT a SEBI-registered Investment Advisor. For educational purposes only.");
        return sb.toString();
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String s) return s;
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(Object obj) {
        if (obj == null) return Map.of();
        if (obj instanceof Map) return (Map<String,Object>) obj;
        try { return objectMapper.readValue(obj.toString(), Map.class); }
        catch (Exception e) { return Map.of(); }
    }

    private int    getInt(Map<?,?> m, String k, int def)       { Object v = m.get(k); return v == null ? def : ((Number)v).intValue(); }
    private double getDouble(Map<?,?> m, String k, double def) { Object v = m.get(k); return v == null ? def : ((Number)v).doubleValue(); }
}
