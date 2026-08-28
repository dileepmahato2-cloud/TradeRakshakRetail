package com.traderakshak.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 4 — lightweight in-memory TTL cache for hot, read-mostly data
 * (holiday calendar, and any short-lived derived values). Thread-safe, with
 * hit/miss stats. Values are stored by reference, so only cache data that
 * callers do not mutate after retrieval.
 */
@Component
public class MarketDataCache {

    private static final class Entry {
        final Object value;
        final long expiresAt;
        Entry(Object value, long expiresAt) { this.value = value; this.expiresAt = expiresAt; }
    }

    /**
     * Hard ceiling on entries. Expiry alone never bounded this cache: entries were only
     * dropped when someone happened to read an expired key, so anything written and never
     * re-read stayed for the life of the process. On a small heap that grows without limit
     * over days of uptime. Override with cache.market_data.max_entries.
     */
    private static final int DEFAULT_MAX_ENTRIES = 5_000;

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();
    private final int maxEntries;

    public MarketDataCache() {
        int configured = DEFAULT_MAX_ENTRIES;
        try {
            String v = System.getProperty("cache.market_data.max_entries");
            if (v != null && !v.isBlank()) configured = Math.max(100, Integer.parseInt(v.trim()));
        } catch (NumberFormatException ignored) { /* keep the default */ }
        this.maxEntries = configured;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        Entry e = store.get(key);
        if (e == null || System.currentTimeMillis() > e.expiresAt) {
            if (e != null) store.remove(key);
            misses.incrementAndGet();
            return null;
        }
        hits.incrementAndGet();
        return (T) e.value;
    }

    public void put(String key, Object value, long ttlMs) {
        if (store.size() >= maxEntries && !store.containsKey(key)) makeRoom();
        store.put(key, new Entry(value, System.currentTimeMillis() + Math.max(0L, ttlMs)));
    }

    /**
     * Drop expired entries first; if that is not enough, drop those closest to expiry.
     * Only runs when the cache is full, so the O(n) pass is rare.
     */
    private void makeRoom() {
        long now = System.currentTimeMillis();
        int before = store.size();
        store.values().removeIf(e -> now > e.expiresAt);
        evictions.addAndGet(before - store.size());

        int over = store.size() - maxEntries + 1;
        if (over <= 0) return;
        store.entrySet().stream()
            .sorted((a, b) -> Long.compare(a.getValue().expiresAt, b.getValue().expiresAt))
            .limit(over)
            .map(Map.Entry::getKey)
            .toList()
            .forEach(k -> { if (store.remove(k) != null) evictions.incrementAndGet(); });
    }

    public void evict(String key) { store.remove(key); }

    public void evictPrefix(String prefix) {
        store.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public void clear() { store.clear(); }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        long h = hits.get();
        long ms = misses.get();
        long total = h + ms;
        m.put("size", store.size());
        m.put("max_entries", maxEntries);
        m.put("evictions", evictions.get());
        m.put("hits", h);
        m.put("misses", ms);
        m.put("hit_ratio_pct", total == 0 ? 0.0 : Math.round(h * 10000.0 / total) / 100.0);
        return m;
    }
}
