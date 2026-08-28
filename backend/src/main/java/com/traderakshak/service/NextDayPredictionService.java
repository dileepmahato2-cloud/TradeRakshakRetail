package com.traderakshak.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traderakshak.entity.User;
import com.traderakshak.util.DateUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class NextDayPredictionService {

    private static final String MODEL_VERSION = "3_15_NEXT_DAY_V1";
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    public boolean isNextDayPredictionPrompt(String prompt) {
        if (prompt == null) return false;
        String p = prompt.toLowerCase(Locale.ROOT);
        return (p.contains("3:15") || p.contains("315") || p.contains("next-day") || p.contains("next day"))
            && (p.contains("market direction") || p.contains("gap") || p.contains("index score") || p.contains("stock scoring"));
    }

    public Map<String, Object> modelSpec() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("modelVersion", MODEL_VERSION);
        spec.put("name", "3:15 PM Next-Day Market Direction and Stock Scoring Engine");
        spec.put("purpose", "Score index direction and top stocks after 3:15 PM for next-day opening bias, gap probability, and trade watchlist preparation.");
        spec.put("runTime", "15:15");
        spec.put("dataPolicy", "When required live inputs are missing, the engine returns neutral/blank values and shows missing inputs instead of creating false signals.");
        spec.put("indexScoring", List.of(
            Map.of("factor", "Intraday Momentum", "points", "+2 / -2", "rule", "Price above VWAP and in top 15% of day range is bullish; below VWAP and bottom 15% is bearish."),
            Map.of("factor", "PCR Change", "points", "+2 / -2", "rule", "PCR change above +10% from 2:30 PM to 3:15 PM is bullish; below -10% is bearish."),
            Map.of("factor", "Institutional Volume Spike", "points", "+2 / -2", "rule", "3:00-3:15 volume above 2x average with green candle is bullish; red candle is bearish."),
            Map.of("factor", "Market Breadth", "points", "+1 / -1", "rule", "Advance/Decline ratio above 2.5 is bullish; below 0.4 is bearish."),
            Map.of("factor", "Global Sentiment", "points", "+1 / -1", "rule", "FTSE, DAX, and S&P futures alignment above threshold is bullish; broad weakness is bearish.")
        ));
        spec.put("indexClassification", Map.of(
            "+7 to +8", "Strong Bullish",
            "+5 to +6", "Bullish",
            "+2 to +4", "Mild Bullish",
            "-1 to +1", "Neutral",
            "-2 to -4", "Mild Bearish",
            "-5 to -6", "Bearish",
            "-7 to -8", "Strong Bearish"
        ));
        spec.put("stockScoring", List.of(
            Map.of("factor", "Intraday Strength", "points", "+2 / -2"),
            Map.of("factor", "Last 15m Volume", "points", "+2 / -2"),
            Map.of("factor", "Delivery % vs 20D Avg", "points", "+2 / -2"),
            Map.of("factor", "Sector Strength", "points", "+1 / -1"),
            Map.of("factor", "F&O OI", "points", "+1 / -1"),
            Map.of("factor", "Relative Strength vs Nifty", "points", "+2 / -2")
        ));
        spec.put("stockClassification", Map.of(
            "+9 to +10", "Strong Buy",
            "+7 to +8", "Buy",
            "+4 to +6", "Watchlist Buy",
            "-3 to +3", "No Trade",
            "-4 to -6", "Watchlist Sell",
            "-7 to -8", "Sell",
            "-9 to -10", "Strong Sell"
        ));
        spec.put("confidenceBands", Map.of(
            "90-100", "Very High",
            "80-89", "High",
            "70-79", "Medium",
            "0-69", "Low"
        ));
        spec.put("backtestingPeriods", List.of(250, 500, 1250));
        spec.put("adminDashboard", List.of(
            "Today index score",
            "Top 20 stocks",
            "Strong Buy and Strong Sell groups",
            "Confidence score",
            "Sector heat map",
            "Accumulation / distribution",
            "Historical accuracy",
            "Excel and PDF download",
            "Backtesting dashboard"
        ));
        return spec;
    }

    public Map<String, Object> generatedStrategyPayload(String prompt, String segment, String category, String universe, Double targetWinRate) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategyName", "AI: 3:15 PM Next-Day Prediction Engine");
        result.put("prompt", prompt);
        result.put("segment", segment != null ? segment : "EQ-SWG");
        result.put("category", "next_day_prediction");
        result.put("timeframe", "3:15 PM close preparation");
        result.put("universe", universe != null ? universe : "NSE_500");
        result.put("conditions", List.of());
        result.put("entryRules", List.of(
            "Run only after 3:15 PM using index, breadth, volume, PCR, global sentiment, sector, delivery, F&O OI, and relative-strength inputs.",
            "Only show Buy/Sell candidates when the stock score reaches the configured classification band.",
            "If data is missing for a user or subscription level, keep output blank/neutral and show missing inputs."
        ));
        result.put("exitRules", List.of(
            "Do not place trades directly from prediction score without risk rules.",
            "Invalidate next-day bias when opening price contradicts the signal with high volume."
        ));
        result.put("riskManagement", Map.of(
            "riskPerTrade", 1.0,
            "maxPositions", 5,
            "dailyLossLimit", 2.0,
            "requiresBacktest", true
        ));
        result.put("stopLossPct", 1.0);
        result.put("targetPct", 2.0);
        result.put("rrMin", 2.0);
        result.put("maxPositions", 5);
        result.put("riskPerTradePct", 1.0);
        result.put("estimatedWinRate", targetWinRate != null ? targetWinRate : 0);
        result.put("aiGenerated", true);
        result.put("nextDayPredictionModel", modelSpec());
        result.put("sampleRun", generate(null, Map.of("universe", universe != null ? universe : "NSE_500"), false));
        result.put("explanation", "This AI generator created the requested 3:15 PM scoring model. Live values remain neutral until market, broker, F&O, delivery, breadth, and global sentiment inputs are available for the selected user.");
        result.put("generatedAt", DateUtil.now());
        return result;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> generate(User user, Map<String, Object> req, boolean persist) {
        Map<String, Object> indexInputs = map(req.get("indexInputs"));
        List<Map<String, Object>> stockInputs = listOfMaps(req.get("stockInputs"));

        Map<String, Object> indexScore = scoreIndex(indexInputs);
        List<Map<String, Object>> stockScores = new ArrayList<>();
        for (Map<String, Object> stock : stockInputs) {
            stockScores.add(scoreStock(stock));
        }
        stockScores.sort((a, b) -> Integer.compare(asInt(b.get("score"), 0), asInt(a.get("score"), 0)));
        List<Map<String, Object>> top20 = stockScores.size() > 20 ? stockScores.subList(0, 20) : stockScores;

        List<String> missing = new ArrayList<>();
        missing.addAll((List<String>) indexScore.get("missingInputs"));
        if (stockInputs.isEmpty()) {
            missing.add("stockInputs");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelVersion", MODEL_VERSION);
        result.put("runDate", str(req.get("runDate"), DateUtil.today()));
        result.put("runTime", str(req.get("runTime"), "15:15"));
        result.put("universe", str(req.get("universe"), "NSE_500"));
        result.put("index", indexScore);
        result.put("top20Stocks", top20);
        result.put("strongBuy", stockScores.stream().filter(s -> "Strong Buy".equals(s.get("classification"))).toList());
        result.put("strongSell", stockScores.stream().filter(s -> "Strong Sell".equals(s.get("classification"))).toList());
        result.put("sectorHeatMap", buildSectorHeatMap(stockScores));
        result.put("accumulationDistribution", buildAccumulationDistribution(stockScores));
        result.put("backtestingPeriods", List.of(250, 500, 1250));
        result.put("dataQuality", Map.of(
            "status", missing.isEmpty() ? "ready" : "missing_inputs",
            "missingInputs", missing,
            "message", missing.isEmpty()
                ? "All required inputs were supplied."
                : "Live calculation is blank/neutral until these inputs are connected for this user."
        ));
        result.put("downloadOptions", List.of("Excel", "PDF"));
        result.put("generatedAt", DateUtil.now());

        if (persist && user != null) {
            persistRun(user, req, result, indexScore, top20);
        }
        return result;
    }

    @Transactional
    public Map<String, Object> backtest(User user, Map<String, Object> req) {
        List<Integer> periods = List.of(250, 500, 1250);
        List<Map<String, Object>> rows = new ArrayList<>();
        String message = "Backtest shell is ready. Import historical 3:15 PM inputs and next-day open results to calculate real accuracy.";

        for (Integer period : periods) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("periodDays", period);
            row.put("fromDate", req.get("fromDate"));
            row.put("toDate", req.get("toDate"));
            row.put("totalPredictions", 0);
            row.put("correctPredictions", 0);
            row.put("accuracyPct", 0);
            row.put("gapAccuracyPct", 0);
            row.put("monthlyAccuracy", Map.of());
            row.put("yearlyAccuracy", Map.of());
            row.put("sectorAccuracy", Map.of());
            row.put("stockAccuracy", Map.of());
            row.put("status", "waiting_for_historical_inputs");
            row.put("message", message);
            rows.add(row);

            if (user != null) {
                try {
                    em.createNativeQuery(
                        "INSERT INTO next_day_prediction_backtests(user_id,institution_id,period_days,from_date,to_date,total_predictions,correct_predictions,accuracy_pct,gap_accuracy_pct,monthly_accuracy_json,yearly_accuracy_json,sector_accuracy_json,stock_accuracy_json,status,message,created_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
                        .setParameter(1, user.getId())
                        .setParameter(2, user.getInstitutionId())
                        .setParameter(3, period)
                        .setParameter(4, req.get("fromDate"))
                        .setParameter(5, req.get("toDate"))
                        .setParameter(6, 0)
                        .setParameter(7, 0)
                        .setParameter(8, 0)
                        .setParameter(9, 0)
                        .setParameter(10, "{}")
                        .setParameter(11, "{}")
                        .setParameter(12, "{}")
                        .setParameter(13, "{}")
                        .setParameter(14, "waiting_for_historical_inputs")
                        .setParameter(15, message)
                        .setParameter(16, DateUtil.now())
                        .executeUpdate();
                } catch (Exception e) {
                    log.debug("Next-day backtest persist skipped: {}", e.getMessage());
                }
            }
        }

        return Map.of(
            "modelVersion", MODEL_VERSION,
            "status", "waiting_for_historical_inputs",
            "message", message,
            "periods", rows
        );
    }

    public Map<String, Object> latest(User user) {
        if (user == null) return Map.of();
        try {
            Object[] row = (Object[]) em.createNativeQuery(
                "SELECT id,run_date,run_time,universe,index_score,index_classification,index_confidence,gap_up_probability,gap_down_probability,result_json,created_at " +
                "FROM next_day_prediction_runs WHERE user_id=:uid ORDER BY id DESC LIMIT 1")
                .setParameter("uid", user.getId())
                .getSingleResult();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", row[0]);
            result.put("runDate", row[1]);
            result.put("runTime", row[2]);
            result.put("universe", row[3]);
            result.put("indexScore", row[4]);
            result.put("indexClassification", row[5]);
            result.put("indexConfidence", row[6]);
            result.put("gapUpProbability", row[7]);
            result.put("gapDownProbability", row[8]);
            result.put("result", readJson(row[9]));
            result.put("createdAt", row[10]);
            return result;
        } catch (Exception e) {
            return Map.of("message", "No next-day prediction run is available yet.");
        }
    }

    private Map<String, Object> scoreIndex(Map<String, Object> in) {
        int score = 0;
        int available = 0;
        List<String> missing = new ArrayList<>();
        List<Map<String, Object>> details = new ArrayList<>();

        Double price = dbl(in.get("price"));
        Double vwap = dbl(in.get("vwap"));
        Double dayHigh = dbl(in.get("dayHigh"));
        Double dayLow = dbl(in.get("dayLow"));
        if (price != null && vwap != null && dayHigh != null && dayLow != null && dayHigh > dayLow) {
            available++;
            double rangePos = (price - dayLow) / (dayHigh - dayLow);
            int pts = price > vwap && rangePos >= 0.85 ? 2 : price < vwap && rangePos <= 0.15 ? -2 : 0;
            score += pts;
            details.add(detail("Intraday Momentum", pts, "Range position " + round(rangePos * 100) + "%"));
        } else missing.add("index price/vwap/dayHigh/dayLow");

        Double pcr230 = dbl(in.get("pcr230"));
        Double pcr315 = dbl(in.get("pcr315"));
        if (pcr230 != null && pcr315 != null && pcr230 != 0) {
            available++;
            double change = (pcr315 - pcr230) / pcr230 * 100;
            int pts = change > 10 ? 2 : change < -10 ? -2 : 0;
            score += pts;
            details.add(detail("PCR Change", pts, "PCR changed " + round(change) + "% from 2:30 PM to 3:15 PM"));
        } else missing.add("index PCR at 2:30 PM and 3:15 PM");

        Double last15Volume = dbl(in.get("last15Volume"));
        Double avg15Volume = dbl(in.get("avg15Volume"));
        Double candleOpen = dbl(in.get("last15Open"));
        Double candleClose = dbl(in.get("last15Close"));
        if (last15Volume != null && avg15Volume != null && avg15Volume > 0 && candleOpen != null && candleClose != null) {
            available++;
            boolean spike = last15Volume > avg15Volume * 2;
            int pts = spike && candleClose > candleOpen ? 2 : spike && candleClose < candleOpen ? -2 : 0;
            score += pts;
            details.add(detail("Institutional Volume Spike", pts, "Last 15m volume is " + round(last15Volume / avg15Volume) + "x average"));
        } else missing.add("index last15Volume/avg15Volume/last15Open/last15Close");

        Double adRatio = dbl(in.get("advanceDeclineRatio"));
        if (adRatio != null) {
            available++;
            int pts = adRatio > 2.5 ? 1 : adRatio < 0.4 ? -1 : 0;
            score += pts;
            details.add(detail("Market Breadth", pts, "Advance/Decline ratio " + round(adRatio)));
        } else missing.add("market breadth advanceDeclineRatio");

        Double ftse = dbl(in.get("ftseChangePct"));
        Double dax = dbl(in.get("daxChangePct"));
        Double sp = dbl(in.get("spFuturesChangePct"));
        if (ftse != null && dax != null && sp != null) {
            available++;
            double avg = (ftse + dax + sp) / 3.0;
            int positives = (ftse > 0.15 ? 1 : 0) + (dax > 0.15 ? 1 : 0) + (sp > 0.15 ? 1 : 0);
            int negatives = (ftse < -0.15 ? 1 : 0) + (dax < -0.15 ? 1 : 0) + (sp < -0.15 ? 1 : 0);
            int pts = positives >= 2 ? 1 : negatives >= 2 ? -1 : 0;
            score += pts;
            details.add(detail("Global Sentiment", pts, "Average global change " + round(avg) + "%"));
        } else missing.add("FTSE/DAX/S&P futures change");

        score = clamp(score, -8, 8);
        int confidence = confidence(score, 8, available, 5);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("score", score);
        result.put("maxScore", 8);
        result.put("minScore", -8);
        result.put("classification", classifyIndex(score));
        result.put("confidence", confidence);
        result.put("confidenceBand", confidenceBand(confidence));
        result.put("gapUpProbability", score > 0 ? confidence : Math.max(0, 100 - confidence));
        result.put("gapDownProbability", score < 0 ? confidence : Math.max(0, 100 - confidence));
        result.put("details", details);
        result.put("missingInputs", missing);
        return result;
    }

    private Map<String, Object> scoreStock(Map<String, Object> in) {
        int score = 0;
        int available = 0;
        List<String> missing = new ArrayList<>();
        List<Map<String, Object>> details = new ArrayList<>();

        available += addStockFactor(in, details, missing, "Intraday Strength", "intradayStrength", 2);
        available += addStockFactor(in, details, missing, "Last 15m Volume", "last15VolumeStrength", 2);
        available += addStockFactor(in, details, missing, "Delivery % vs 20D Avg", "deliveryStrength", 2);
        available += addStockFactor(in, details, missing, "Sector Strength", "sectorStrength", 1);
        available += addStockFactor(in, details, missing, "F&O OI", "foOiStrength", 1);
        available += addStockFactor(in, details, missing, "Relative Strength vs Nifty", "relativeStrength", 2);

        for (Map<String, Object> d : details) {
            score += asInt(d.get("points"), 0);
        }
        score = clamp(score, -10, 10);
        int confidence = confidence(score, 10, available, 6);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", str(in.get("symbol"), ""));
        result.put("exchange", str(in.get("exchange"), "NSE"));
        result.put("sector", str(in.get("sector"), ""));
        result.put("score", score);
        result.put("maxScore", 10);
        result.put("minScore", -10);
        result.put("classification", classifyStock(score));
        result.put("confidence", confidence);
        result.put("confidenceBand", confidenceBand(confidence));
        result.put("details", details);
        result.put("missingInputs", missing);
        return result;
    }

    private int addStockFactor(Map<String, Object> in, List<Map<String, Object>> details, List<String> missing,
                               String factor, String key, int weight) {
        Double value = dbl(in.get(key));
        if (value == null) {
            missing.add("stock " + key);
            return 0;
        }
        int points = value > 0 ? weight : value < 0 ? -weight : 0;
        details.add(detail(factor, points, "Input " + key + " = " + round(value)));
        return 1;
    }

    private Map<String, Object> buildSectorHeatMap(List<Map<String, Object>> stocks) {
        Map<String, int[]> sums = new LinkedHashMap<>();
        for (Map<String, Object> stock : stocks) {
            String sector = str(stock.get("sector"), "Unmapped");
            int[] agg = sums.computeIfAbsent(sector, k -> new int[2]);
            agg[0] += asInt(stock.get("score"), 0);
            agg[1]++;
        }
        Map<String, Object> heat = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : sums.entrySet()) {
            heat.put(e.getKey(), Map.of(
                "avgScore", e.getValue()[1] == 0 ? 0 : round(e.getValue()[0] / (double) e.getValue()[1]),
                "stocks", e.getValue()[1]
            ));
        }
        return heat;
    }

    private Map<String, Object> buildAccumulationDistribution(List<Map<String, Object>> stocks) {
        long accumulation = stocks.stream().filter(s -> asInt(s.get("score"), 0) >= 4).count();
        long distribution = stocks.stream().filter(s -> asInt(s.get("score"), 0) <= -4).count();
        return Map.of(
            "accumulationCount", accumulation,
            "distributionCount", distribution,
            "neutralCount", Math.max(0, stocks.size() - accumulation - distribution)
        );
    }

    private void persistRun(User user, Map<String, Object> req, Map<String, Object> result,
                            Map<String, Object> indexScore, List<Map<String, Object>> top20) {
        try {
            Number runId = (Number) em.createNativeQuery(
                "INSERT INTO next_day_prediction_runs(user_id,institution_id,run_date,run_time,universe,index_score,index_classification,index_confidence,gap_up_probability,gap_down_probability,model_version,input_json,result_json,data_quality_json,status,created_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id")
                .setParameter(1, user.getId())
                .setParameter(2, user.getInstitutionId())
                .setParameter(3, result.get("runDate"))
                .setParameter(4, result.get("runTime"))
                .setParameter(5, result.get("universe"))
                .setParameter(6, indexScore.get("score"))
                .setParameter(7, indexScore.get("classification"))
                .setParameter(8, indexScore.get("confidence"))
                .setParameter(9, indexScore.get("gapUpProbability"))
                .setParameter(10, indexScore.get("gapDownProbability"))
                .setParameter(11, MODEL_VERSION)
                .setParameter(12, writeJson(req))
                .setParameter(13, writeJson(result))
                .setParameter(14, writeJson(result.get("dataQuality")))
                .setParameter(15, "completed")
                .setParameter(16, DateUtil.now())
                .getSingleResult();

            em.createNativeQuery(
                "INSERT INTO next_day_index_scores(run_id,index_symbol,score,classification,confidence,gap_up_probability,gap_down_probability,detail_json,created_at) VALUES(?,?,?,?,?,?,?,?,?)")
                .setParameter(1, runId.longValue())
                .setParameter(2, str(req.get("indexSymbol"), "NIFTY"))
                .setParameter(3, indexScore.get("score"))
                .setParameter(4, indexScore.get("classification"))
                .setParameter(5, indexScore.get("confidence"))
                .setParameter(6, indexScore.get("gapUpProbability"))
                .setParameter(7, indexScore.get("gapDownProbability"))
                .setParameter(8, writeJson(indexScore.get("details")))
                .setParameter(9, DateUtil.now())
                .executeUpdate();

            for (Map<String, Object> stock : top20) {
                em.createNativeQuery(
                    "INSERT INTO next_day_stock_scores(run_id,exchange,symbol,sector,score,classification,confidence,detail_json,missing_inputs_json,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)")
                    .setParameter(1, runId.longValue())
                    .setParameter(2, stock.get("exchange"))
                    .setParameter(3, stock.get("symbol"))
                    .setParameter(4, stock.get("sector"))
                    .setParameter(5, stock.get("score"))
                    .setParameter(6, stock.get("classification"))
                    .setParameter(7, stock.get("confidence"))
                    .setParameter(8, writeJson(stock.get("details")))
                    .setParameter(9, writeJson(stock.get("missingInputs")))
                    .setParameter(10, DateUtil.now())
                    .executeUpdate();
            }
        } catch (Exception e) {
            log.debug("Next-day prediction persist skipped: {}", e.getMessage());
        }
    }

    private Map<String, Object> detail(String factor, int points, String note) {
        return Map.of("factor", factor, "points", points, "note", note);
    }

    private String classifyIndex(int score) {
        if (score >= 7) return "Strong Bullish";
        if (score >= 5) return "Bullish";
        if (score >= 2) return "Mild Bullish";
        if (score >= -1) return "Neutral";
        if (score >= -4) return "Mild Bearish";
        if (score >= -6) return "Bearish";
        return "Strong Bearish";
    }

    private String classifyStock(int score) {
        if (score >= 9) return "Strong Buy";
        if (score >= 7) return "Buy";
        if (score >= 4) return "Watchlist Buy";
        if (score >= -3) return "No Trade";
        if (score >= -6) return "Watchlist Sell";
        if (score >= -8) return "Sell";
        return "Strong Sell";
    }

    private int confidence(int score, int maxAbs, int available, int expected) {
        if (available == 0) return 0;
        double strength = Math.abs(score) / (double) maxAbs;
        double coverage = Math.min(1.0, available / (double) expected);
        return clamp((int) Math.round(50 + (50 * strength * coverage)), 0, 100);
    }

    private String confidenceBand(int confidence) {
        if (confidence >= 90) return "Very High";
        if (confidence >= 80) return "High";
        if (confidence >= 70) return "Medium";
        return "Low";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object obj) {
        if (obj instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object obj) {
        if (!(obj instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?>) out.add((Map<String, Object>) item);
        }
        return out;
    }

    private Object readJson(Object obj) {
        if (obj == null) return Map.of();
        try { return objectMapper.readValue(obj.toString(), Object.class); }
        catch (Exception e) { return Map.of(); }
    }

    private String writeJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { return "{}"; }
    }

    private Double dbl(Object obj) {
        if (obj instanceof Number n) return n.doubleValue();
        if (obj == null) return null;
        try { return Double.parseDouble(obj.toString()); }
        catch (Exception e) { return null; }
    }

    private int asInt(Object obj, int def) {
        if (obj instanceof Number n) return n.intValue();
        if (obj == null) return def;
        try { return Integer.parseInt(obj.toString()); }
        catch (Exception e) { return def; }
    }

    private String str(Object obj, String def) {
        return obj == null || obj.toString().isBlank() ? def : obj.toString();
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
