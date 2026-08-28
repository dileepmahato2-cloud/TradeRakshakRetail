package com.traderakshak.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory snapshot of the current data-synchronization run. Read by the sync
 * status endpoint and pushed to the UI over SSE. Thread-safe: written by the
 * background sync worker, read by request threads.
 */
@Component
public class SyncStatusHolder {

    public enum Phase { IDLE, STARTED, CHECKING, DOWNLOADING, UPDATING, REFRESHING, COMPLETED, ERROR }

    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Phase phase = Phase.IDLE;
    private volatile String marketStatus = "UNKNOWN";
    private volatile String message = "";
    private volatile int tradingDaysToSync = 0;
    private volatile int recordsMissing = 0;
    private volatile int recordsDone = 0;
    private volatile long startedAt = 0L;
    private volatile long updatedAt = 0L;

    public boolean tryStart() { return running.compareAndSet(false, true); }
    public void finish() { running.set(false); }
    public boolean isRunning() { return running.get(); }

    public synchronized void set(Phase phase, String message) {
        this.phase = phase;
        if (message != null) this.message = message;
        if (phase == Phase.STARTED) this.startedAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
    }

    public synchronized void setMarketStatus(String status) { this.marketStatus = status; }
    public synchronized void setPlan(int tradingDaysToSync, int recordsMissing) {
        this.tradingDaysToSync = tradingDaysToSync;
        this.recordsMissing = recordsMissing;
        this.updatedAt = System.currentTimeMillis();
    }
    public synchronized void setProgress(int recordsDone) {
        this.recordsDone = recordsDone;
        this.updatedAt = System.currentTimeMillis();
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase", phase.name());
        m.put("running", running.get());
        m.put("market_status", marketStatus);
        m.put("message", message);
        m.put("trading_days_to_sync", tradingDaysToSync);
        m.put("records_missing", recordsMissing);
        m.put("records_done", recordsDone);
        int pct = recordsMissing > 0 ? (int) Math.min(100, Math.round(recordsDone * 100.0 / recordsMissing)) : (phase == Phase.COMPLETED ? 100 : 0);
        m.put("percent", pct);
        m.put("started_at", startedAt);
        m.put("updated_at", updatedAt);
        return m;
    }
}
