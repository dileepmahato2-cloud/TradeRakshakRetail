package com.traderakshak.controller;

import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.service.IndicatorCacheService;
import com.traderakshak.service.TechnicalIndicatorEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/indicators")
@RequiredArgsConstructor
public class TechnicalIndicatorController {

    private final TechnicalIndicatorEngine engine;
    private final IndicatorCacheService cacheService;

    @GetMapping("/compute/{symbol}")
    public ApiResponse<Map<String, Object>> computeIndicators(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "NSE") String exchange,
            @RequestParam(defaultValue = "DAILY") String timeframe) {
        Map<String, Object> result = cacheService.getCachedOrCompute(symbol.toUpperCase(), exchange, timeframe);
        if (result.containsKey("error")) {
            return ApiResponse.error("INDICATOR_DATA_ERROR", result.get("error").toString());
        }
        return ApiResponse.ok(result);
    }

    @PostMapping("/batch")
    public ApiResponse<Map<String, Object>> batchCompute(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<String> symbols = (List<String>) request.getOrDefault("symbols", List.of());
        String exchange = (String) request.getOrDefault("exchange", "NSE");
        String timeframe = (String) request.getOrDefault("timeframe", "DAILY");
        if (symbols.isEmpty()) {
            return ApiResponse.error("INVALID_INPUT", "symbols list is required");
        }
        if (symbols.size() > 50) {
            return ApiResponse.error("BATCH_LIMIT", "Maximum 50 symbols per batch");
        }
        Map<String, Object> result = cacheService.batchCompute(symbols, exchange, timeframe);
        return ApiResponse.ok(result);
    }

    @GetMapping("/signals")
    public ApiResponse<List<Map<String, Object>>> getSignals(
            @RequestParam(required = false) String symbol) {
        return ApiResponse.ok(cacheService.getActiveSignals(symbol));
    }

    @GetMapping("/cache/stats")
    public ApiResponse<Map<String, Object>> getCacheStats() {
        return ApiResponse.ok(cacheService.getCacheStats());
    }

    @DeleteMapping("/cache")
    public ApiResponse<Map<String, Object>> clearCache(
            @RequestParam(required = false) String symbol) {
        int cleared = cacheService.clearCache(symbol);
        return ApiResponse.ok(Map.of("cleared", cleared, "symbol", symbol != null ? symbol : "ALL"));
    }

    @DeleteMapping("/cache/expired")
    public ApiResponse<Map<String, Object>> clearExpiredCache() {
        int cleared = cacheService.clearExpiredCache();
        return ApiResponse.ok(Map.of("cleared", cleared));
    }

    @GetMapping("/settings")
    public ApiResponse<List<Map<String, Object>>> getSettings() {
        return ApiResponse.ok(cacheService.getAllSettings());
    }

    @PostMapping("/settings")
    public ApiResponse<String> saveSetting(@RequestBody Map<String, String> body) {
        String key = body.get("key");
        String value = body.get("value");
        String description = body.getOrDefault("description", "");
        if (key == null || key.isBlank()) {
            return ApiResponse.error("INVALID_INPUT", "setting key is required");
        }
        cacheService.saveSetting(key, value, description, null);
        return ApiResponse.ok("Setting saved: " + key);
    }

    @GetMapping("/supported")
    public ApiResponse<Map<String, Object>> getSupportedIndicators() {
        Map<String, Object> categories = new LinkedHashMap<>();

        categories.put("price", List.of(
            ind("SMA", "Simple Moving Average", "period:20"),
            ind("EMA", "Exponential Moving Average", "period:20"),
            ind("WMA", "Weighted Moving Average", "period:20"),
            ind("VWAP", "Volume Weighted Average Price", "none")
        ));

        categories.put("momentum", List.of(
            ind("RSI", "Relative Strength Index", "period:14"),
            ind("MACD", "Moving Average Convergence Divergence", "fast:12, slow:26, signal:9"),
            ind("STOCHASTIC", "Stochastic Oscillator (%K, %D)", "kPeriod:14, dPeriod:3"),
            ind("STOCH_RSI", "Stochastic RSI", "rsiPeriod:14, stochPeriod:14"),
            ind("ROC", "Rate of Change", "period:14"),
            ind("MOMENTUM", "Momentum (absolute)", "period:14"),
            ind("WILLIAMS_R", "Williams %R", "period:14"),
            ind("CCI", "Commodity Channel Index", "period:20"),
            ind("MFI", "Money Flow Index", "period:14")
        ));

        categories.put("trend", List.of(
            ind("ADX", "Average Directional Index", "period:14"),
            ind("DMI", "Directional Movement Index (+DI, -DI, ADX)", "period:14"),
            ind("SUPERTREND", "SuperTrend", "period:7, multiplier:3.0"),
            ind("PARABOLIC_SAR", "Parabolic SAR", "afStart:0.02, afStep:0.02, afMax:0.2"),
            ind("ICHIMOKU", "Ichimoku Cloud", "tenkan:9, kijun:26, senkouB:52")
        ));

        categories.put("volatility", List.of(
            ind("ATR", "Average True Range", "period:14"),
            ind("BOLLINGER_BANDS", "Bollinger Bands", "period:20, stdMult:2.0"),
            ind("BB_WIDTH", "Bollinger Band Width", "period:20"),
            ind("BB_PERCENT_B", "Bollinger %B", "period:20"),
            ind("KELTNER", "Keltner Channel", "emaPeriod:20, atrPeriod:10, multiplier:1.5"),
            ind("DONCHIAN", "Donchian Channel", "period:20")
        ));

        categories.put("volume", List.of(
            ind("OBV", "On-Balance Volume", "none"),
            ind("VOLUME_SMA", "Volume SMA", "period:20"),
            ind("VOLUME_CHANGE", "Volume Change %", "none"),
            ind("VOLUME_SPIKE", "Volume Spike Detection", "period:20, threshold:2.0"),
            ind("VOLUME_SPIKE_RATIO", "Volume Spike Ratio", "period:20")
        ));

        categories.put("support_resistance", List.of(
            ind("PIVOT_POINTS", "Standard Pivot Points (P, R1-R3, S1-S3)", "none"),
            ind("FIBONACCI_PIVOTS", "Fibonacci Pivot Points", "none"),
            ind("CPR", "Central Pivot Range", "none"),
            ind("SUPPORT_RESISTANCE", "Support & Resistance Levels", "lookback:50"),
            ind("WEEK_52_HL", "52-Week High/Low", "none")
        ));

        categories.put("signals", List.of(
            ind("RSI_ZONE", "RSI Zone Detection", "period:14"),
            ind("MACD_CROSSOVER", "MACD Crossover Signal", "none"),
            ind("EMA_CROSSOVER", "EMA Crossover Signal", "shortPeriod:9, longPeriod:21"),
            ind("SIGNAL_SUMMARY", "Composite Signal Score", "none")
        ));

        categories.put("patterns", List.of(
            ind("HAMMER", "Hammer", "candlestick"),
            ind("DOJI", "Doji", "candlestick"),
            ind("MARUBOZU", "Marubozu", "candlestick"),
            ind("SPINNING_TOP", "Spinning Top", "candlestick"),
            ind("INVERTED_HAMMER", "Inverted Hammer", "candlestick"),
            ind("SHOOTING_STAR", "Shooting Star", "candlestick"),
            ind("BULLISH_ENGULFING", "Bullish Engulfing", "2-candle"),
            ind("BEARISH_ENGULFING", "Bearish Engulfing", "2-candle"),
            ind("MORNING_STAR", "Morning Star", "3-candle"),
            ind("EVENING_STAR", "Evening Star", "3-candle"),
            ind("THREE_WHITE_SOLDIERS", "Three White Soldiers", "3-candle"),
            ind("THREE_BLACK_CROWS", "Three Black Crows", "3-candle"),
            ind("HIGHER_HIGH", "Higher High", "price-action"),
            ind("HIGHER_LOW", "Higher Low", "price-action"),
            ind("LOWER_HIGH", "Lower High", "price-action"),
            ind("LOWER_LOW", "Lower Low", "price-action"),
            ind("BREAKOUT", "Breakout (n-bar)", "lookback:20"),
            ind("BREAKDOWN", "Breakdown (n-bar)", "lookback:20")
        ));

        categories.put("price_change", List.of(
            ind("GAP_PCT", "Gap Percentage", "none"),
            ind("RETURN_PCT", "Return Percentage", "entry, exit"),
            ind("PRICE_CHANGE_PCT", "Price Change %", "periods:1")
        ));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("categories", categories);
        result.put("total_indicators", categories.values().stream()
            .mapToInt(c -> ((List<?>) c).size()).sum());
        result.put("timeframes", List.of("1MIN", "5MIN", "15MIN", "30MIN", "1HOUR", "DAILY", "WEEKLY", "MONTHLY"));
        return ApiResponse.ok(result);
    }

    private Map<String, String> ind(String name, String label, String params) {
        return Map.of("name", name, "label", label, "default_params", params);
    }
}
