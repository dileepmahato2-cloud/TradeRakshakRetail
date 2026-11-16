package com.traderakshak.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class IndicatorCacheService {

    private final TechnicalIndicatorEngine engine;
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DEFAULT_CACHE_MINUTES = 15;

    @SuppressWarnings("unchecked")
    public Map<String, Object> getCachedOrCompute(String symbol, String exchange, String timeframe) {
        try {
            List<Object[]> cached = em.createNativeQuery(
                "SELECT value_json FROM technical_indicator_cache " +
                "WHERE symbol=:sym AND indicator_name='ALL' AND timeframe=:tf " +
                "AND expires_at > :now")
                .setParameter("sym", symbol)
                .setParameter("tf", timeframe)
                .setParameter("now", LocalDateTime.now().format(FMT))
                .getResultList();
            if (!cached.isEmpty() && cached.get(0) != null) {
                String json = cached.get(0) instanceof Object[]
                    ? ((Object[]) cached.get(0))[0].toString()
                    : cached.get(0).toString();
                return objectMapper.readValue(json, Map.class);
            }
        } catch (Exception e) {
            log.debug("Cache miss for {}: {}", symbol, e.getMessage());
        }

        Map<String, Object> data = loadOhlcv(symbol, exchange, 300);
        if (data == null) return Map.of("error", "No OHLCV data for " + symbol);

        long start = System.currentTimeMillis();
        @SuppressWarnings("unchecked")
        List<Double> opens = (List<Double>) data.get("opens");
        @SuppressWarnings("unchecked")
        List<Double> highs = (List<Double>) data.get("highs");
        @SuppressWarnings("unchecked")
        List<Double> lows = (List<Double>) data.get("lows");
        @SuppressWarnings("unchecked")
        List<Double> closes = (List<Double>) data.get("closes");
        @SuppressWarnings("unchecked")
        List<Double> volumes = (List<Double>) data.get("volumes");

        Map<String, Object> result = engine.computeAll(opens, highs, lows, closes, volumes);
        long duration = System.currentTimeMillis() - start;

        storeCache(symbol, exchange, timeframe, result, closes != null ? closes.size() : 0);
        logCalculation(symbol, exchange, "ALL", timeframe, "success", (int) duration,
            closes != null ? closes.size() : 0, null);

        return result;
    }

    @Transactional
    public void storeCache(String symbol, String exchange, String timeframe,
                           Map<String, Object> result, int dataPoints) {
        try {
            String now = LocalDateTime.now().format(FMT);
            String expires = LocalDateTime.now().plusMinutes(DEFAULT_CACHE_MINUTES).format(FMT);
            String json = objectMapper.writeValueAsString(result);

            em.createNativeQuery(
                "INSERT INTO technical_indicator_cache (symbol, exchange, indicator_name, " +
                "indicator_params, timeframe, value_json, computed_at, expires_at, data_points) " +
                "VALUES (:sym, :ex, 'ALL', '{}', :tf, :json, :now, :exp, :dp) " +
                "ON CONFLICT (symbol, indicator_name, indicator_params, timeframe) " +
                "DO UPDATE SET value_json=:json, computed_at=:now, expires_at=:exp, data_points=:dp")
                .setParameter("sym", symbol).setParameter("ex", exchange)
                .setParameter("tf", timeframe).setParameter("json", json)
                .setParameter("now", now).setParameter("exp", expires)
                .setParameter("dp", dataPoints)
                .executeUpdate();
        } catch (Exception e) {
            log.debug("Cache store error for {}: {}", symbol, e.getMessage());
        }
    }

    @Transactional
    public void logCalculation(String symbol, String exchange, String indicator,
                               String timeframe, String status, int durationMs,
                               int dataPoints, String error) {
        try {
            em.createNativeQuery(
                "INSERT INTO indicator_calculation_logs (symbol, exchange, indicator_name, " +
                "timeframe, status, duration_ms, data_points, error_message, created_at) " +
                "VALUES (:sym, :ex, :ind, :tf, :st, :dur, :dp, :err, :now)")
                .setParameter("sym", symbol).setParameter("ex", exchange)
                .setParameter("ind", indicator).setParameter("tf", timeframe)
                .setParameter("st", status).setParameter("dur", durationMs)
                .setParameter("dp", dataPoints).setParameter("err", error)
                .setParameter("now", LocalDateTime.now().format(FMT))
                .executeUpdate();
        } catch (Exception e) {
            log.debug("Calculation log error: {}", e.getMessage());
        }
    }

    @Transactional
    public int clearCache(String symbol) {
        try {
            if (symbol != null && !symbol.isBlank()) {
                return em.createNativeQuery("DELETE FROM technical_indicator_cache WHERE symbol=:sym")
                    .setParameter("sym", symbol).executeUpdate();
            }
            return em.createNativeQuery("DELETE FROM technical_indicator_cache").executeUpdate();
        } catch (Exception e) {
            log.warn("Cache clear error: {}", e.getMessage());
            return 0;
        }
    }

    @Transactional
    public int clearExpiredCache() {
        try {
            return em.createNativeQuery(
                "DELETE FROM technical_indicator_cache WHERE expires_at < :now")
                .setParameter("now", LocalDateTime.now().format(FMT))
                .executeUpdate();
        } catch (Exception e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        try {
            Object total = em.createNativeQuery("SELECT COUNT(*) FROM technical_indicator_cache")
                .getSingleResult();
            stats.put("total_cached", total instanceof Number n ? n.longValue() : 0);

            Object expired = em.createNativeQuery(
                "SELECT COUNT(*) FROM technical_indicator_cache WHERE expires_at < :now")
                .setParameter("now", LocalDateTime.now().format(FMT))
                .getSingleResult();
            stats.put("expired", expired instanceof Number n ? n.longValue() : 0);

            Object logs = em.createNativeQuery(
                "SELECT COUNT(*) FROM indicator_calculation_logs").getSingleResult();
            stats.put("total_calculations", logs instanceof Number n ? n.longValue() : 0);

            Object avgDuration = em.createNativeQuery(
                "SELECT COALESCE(AVG(duration_ms),0) FROM indicator_calculation_logs WHERE status='success'")
                .getSingleResult();
            stats.put("avg_duration_ms", avgDuration instanceof Number n ? n.doubleValue() : 0);

            Object signals = em.createNativeQuery("SELECT COUNT(*) FROM stock_signals")
                .getSingleResult();
            stats.put("total_signals", signals instanceof Number n ? n.longValue() : 0);
        } catch (Exception e) {
            stats.put("error", e.getMessage());
        }
        return stats;
    }

    @Transactional
    public void storeSignal(String symbol, String exchange, String signalType,
                            String signalValue, String indicator, double indicatorValue,
                            String timeframe, double confidence) {
        try {
            String now = LocalDateTime.now().format(FMT);
            String expires = LocalDateTime.now().plusHours(24).format(FMT);
            em.createNativeQuery(
                "INSERT INTO stock_signals (symbol, exchange, signal_type, signal_value, " +
                "indicator_name, indicator_value, timeframe, confidence, triggered_at, expires_at) " +
                "VALUES (:sym, :ex, :st, :sv, :ind, :iv, :tf, :conf, :now, :exp) " +
                "ON CONFLICT (symbol, signal_type, timeframe, triggered_at) DO NOTHING")
                .setParameter("sym", symbol).setParameter("ex", exchange)
                .setParameter("st", signalType).setParameter("sv", signalValue)
                .setParameter("ind", indicator).setParameter("iv", indicatorValue)
                .setParameter("tf", timeframe).setParameter("conf", confidence)
                .setParameter("now", now).setParameter("exp", expires)
                .executeUpdate();
        } catch (Exception e) {
            log.debug("Signal store error: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getActiveSignals(String symbol) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            List<Object[]> rows = em.createNativeQuery(
                "SELECT symbol, signal_type, signal_value, indicator_name, indicator_value, " +
                "timeframe, confidence, triggered_at FROM stock_signals " +
                "WHERE (:sym IS NULL OR symbol=:sym) AND expires_at > :now " +
                "ORDER BY triggered_at DESC LIMIT 100")
                .setParameter("sym", symbol)
                .setParameter("now", LocalDateTime.now().format(FMT))
                .getResultList();
            for (Object[] r : rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("symbol", r[0]); m.put("signal_type", r[1]);
                m.put("signal_value", r[2]); m.put("indicator_name", r[3]);
                m.put("indicator_value", r[4]); m.put("timeframe", r[5]);
                m.put("confidence", r[6]); m.put("triggered_at", r[7]);
                result.add(m);
            }
        } catch (Exception e) {
            log.debug("getActiveSignals error: {}", e.getMessage());
        }
        return result;
    }

    public Map<String, Object> getSetting(String key, String defaultValue) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Object val = em.createNativeQuery(
                "SELECT setting_value FROM indicator_settings WHERE setting_key=:key")
                .setParameter("key", key).getSingleResult();
            result.put("key", key);
            result.put("value", val != null ? val.toString() : defaultValue);
        } catch (Exception e) {
            result.put("key", key);
            result.put("value", defaultValue);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getAllSettings() {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            List<Object[]> rows = em.createNativeQuery(
                "SELECT setting_key, setting_value, description, updated_at " +
                "FROM indicator_settings ORDER BY setting_key")
                .getResultList();
            for (Object[] r : rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", r[0]); m.put("value", r[1]);
                m.put("description", r[2]); m.put("updated_at", r[3]);
                result.add(m);
            }
        } catch (Exception e) {
            log.debug("getAllSettings error: {}", e.getMessage());
        }
        return result;
    }

    @Transactional
    public void saveSetting(String key, String value, String description, Long userId) {
        try {
            em.createNativeQuery(
                "INSERT INTO indicator_settings (setting_key, setting_value, description, " +
                "updated_by, updated_at) VALUES (:key, :val, :desc, :uid, :now) " +
                "ON CONFLICT (setting_key) DO UPDATE SET setting_value=:val, " +
                "description=:desc, updated_by=:uid, updated_at=:now")
                .setParameter("key", key).setParameter("val", value)
                .setParameter("desc", description).setParameter("uid", userId)
                .setParameter("now", LocalDateTime.now().format(FMT))
                .executeUpdate();
        } catch (Exception e) {
            log.warn("saveSetting error: {}", e.getMessage());
        }
    }

    @Transactional
    public Map<String, Object> batchCompute(List<String> symbols, String exchange, String timeframe) {
        Map<String, Object> results = new LinkedHashMap<>();
        int success = 0, failed = 0;
        for (String symbol : symbols) {
            try {
                Map<String, Object> r = getCachedOrCompute(symbol, exchange, timeframe);
                if (!r.containsKey("error")) {
                    results.put(symbol, r);
                    success++;
                } else {
                    results.put(symbol, r);
                    failed++;
                }
            } catch (Exception e) {
                results.put(symbol, Map.of("error", e.getMessage()));
                failed++;
            }
        }
        results.put("_summary", Map.of("total", symbols.size(), "success", success, "failed", failed));
        return results;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadOhlcv(String symbol, String exchange, int bars) {
        try {
            List<Object[]> rows = em.createNativeQuery(
                "SELECT open, high, low, close, COALESCE(total_volume,volume) FROM broker_market_candles " +
                "AND UPPER(COALESCE(candle_mode,''))='DAILY' " +
                "WHERE symbol=:sym AND (:ex IS NULL OR exchange=:ex) " +
                "ORDER BY trade_date DESC LIMIT :n")
                .setParameter("sym", symbol).setParameter("ex", exchange)
                .setParameter("n", bars).getResultList();
            if (rows.isEmpty()) return null;
            Collections.reverse(rows);
            List<Double> opens = new ArrayList<>(), highs = new ArrayList<>(),
                         lows = new ArrayList<>(), closes = new ArrayList<>(),
                         volumes = new ArrayList<>();
            for (Object[] r : rows) {
                opens.add(r[0] != null ? ((Number)r[0]).doubleValue() : 0);
                highs.add(r[1] != null ? ((Number)r[1]).doubleValue() : 0);
                lows.add(r[2] != null ? ((Number)r[2]).doubleValue() : 0);
                closes.add(r[3] != null ? ((Number)r[3]).doubleValue() : 0);
                volumes.add(r[4] != null ? ((Number)r[4]).doubleValue() : 0);
            }
            Map<String, Object> data = new HashMap<>();
            data.put("opens", opens); data.put("highs", highs);
            data.put("lows", lows); data.put("closes", closes);
            data.put("volumes", volumes);
            return data;
        } catch (Exception e) {
            log.debug("loadOhlcv error {}: {}", symbol, e.getMessage());
            return null;
        }
    }
}
