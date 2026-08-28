package com.traderakshak.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phase 3 — Indicator freshness.
 *
 * In this codebase technical indicators (RSI/EMA/MACD/ATR/ADX/BB/…) are computed
 * on demand from stored candles and cached (see {@link IndicatorCacheService}),
 * not persisted per trading day. So "missing indicators" really means "not yet
 * computed/cached for the current data." This service pre-warms the indicator
 * cache for the whole active universe in the background — reusing the existing
 * compute engine, reading only from candles already in the DB (no Dhan calls) —
 * so indicator-driven screens are instant instead of computing on first view.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IndicatorWarmupService {

    private final IndicatorCacheService indicatorCache;
    private final AppConfigService appConfig;
    private final LivePriceBroadcaster broadcaster;

    @PersistenceContext
    private EntityManager em;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "indicator-warmup");
        t.setDaemon(true);
        return t;
    });

    /** Warm the indicator cache for the whole active universe on a background thread. */
    public void warmAsync(Long institutionId) {
        if (!appConfig.getBoolean("indicators.warmup.enabled", true)) return;
        if (!running.compareAndSet(false, true)) return;
        exec.submit(() -> {
            try {
                warm(institutionId);
            } catch (Throwable t) {
                log.warn("[IndicatorWarmup] failed: {}", t.getMessage());
            } finally {
                running.set(false);
            }
        });
    }

    /** Post-market indicator warm-up at 15:40 IST (after the 15:35 EOD price refresh). */
    @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void eodWarmup() {
        warmAsync((long) appConfig.getInt("sync.systemInstitutionId", 1));
    }

    private void warm(Long institutionId) {
        List<String> symbols = activeSymbols(institutionId);
        if (symbols.isEmpty()) return;
        String exchange = appConfig.getString("indicators.warmup.exchange", "NSE");
        String timeframe = appConfig.getString("indicators.warmup.timeframe", "daily");
        int batchSize = 50; // batchCompute is capped at 50 symbols per call
        int done = 0;
        long start = System.currentTimeMillis();
        for (int i = 0; i < symbols.size(); i += batchSize) {
            List<String> batch = symbols.subList(i, Math.min(i + batchSize, symbols.size()));
            try {
                indicatorCache.batchCompute(batch, exchange, timeframe);
            } catch (Exception e) {
                log.debug("[IndicatorWarmup] batch failed ({} symbols): {}", batch.size(), e.getMessage());
            }
            done += batch.size();
        }
        log.info("[IndicatorWarmup] warmed indicators for {} symbols in {} ms.", done, System.currentTimeMillis() - start);
        try { broadcaster.sendModuleRefresh("indicators"); } catch (Exception ignored) {}
    }

    private List<String> activeSymbols(Long institutionId) {
        try {
            List<?> rows = em.createNativeQuery(
                "SELECT symbol FROM tracked_stocks WHERE institution_id=? AND is_active=1 ORDER BY symbol")
                .setParameter(1, institutionId).getResultList();
            List<String> out = new ArrayList<>();
            for (Object r : rows) if (r != null) out.add(String.valueOf(r));
            return out;
        } catch (Exception e) {
            log.debug("[IndicatorWarmup] symbol load failed: {}", e.getMessage());
            return List.of();
        }
    }
}
