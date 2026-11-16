package com.traderakshak.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
@Slf4j
public class LivePriceBroadcaster {

    private final ObjectMapper mapper;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final AtomicLong uiUpdates = new AtomicLong();

    /**
     * Delivery runs here, never on the caller's thread.
     *
     * <p>SseEmitter.send writes straight down the client's socket and blocks until it drains. It
     * was being called from the broker feed's own receive thread, so one browser that had gone
     * away - a closed tab, a sleeping laptop - stalled market-data ingestion for the full 20-second
     * socket timeout. Dhan then dropped the connection, the service reconnected, the same dead
     * subscriber stalled it again: 167 disconnects between 10:23 and 11:36 on 7 Sep 2026, each
     * one starting within seconds of the previous subscribe. The per-emitter catch below did
     * remove the bad client, but only after the stall had already cost the feed its connection.
     *
     * <p>One thread with a bounded queue: a slow client can now only delay other clients' updates,
     * never the feed. When the queue is full the update is dropped, because live prices are worth
     * nothing late and an unbounded queue would grow without limit behind a dead socket.
     */
    private final java.util.concurrent.ThreadPoolExecutor delivery =
        new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(500),
            r -> { Thread th = new Thread(r, "sse-delivery"); th.setDaemon(true); return th; },
            new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());

    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("status", "connected")));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    public void sendQuote(Map<String, Object> quote) {
        broadcast("quote", quote);
    }

    public void sendStatus(Map<String, Object> status) {
        broadcast("status", status);
    }

    /** Broadcast a Current Top 10 diff/no-change payload so only changed rows animate. */
    public void sendTop10Update(Map<String, Object> payload) {
        broadcast("top10", payload);
    }

    public long uiUpdates() {
        return uiUpdates.get();
    }

    public int clientCount() {
        return emitters.size();
    }

    /** Push a data-synchronization progress snapshot to the UI (event "sync"). */
    public void sendSync(Map<String, Object> payload) {
        broadcast("sync", payload);
    }

    /** Tell the UI a specific module has fresh data (event "refresh", payload {module}). */
    public void sendModuleRefresh(String module) {
        broadcast("refresh", Map.of("module", module, "at", System.currentTimeMillis()));
    }

    private void broadcast(String eventName, Object payload) {
        if (emitters.isEmpty()) return;
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.debug("Live price SSE payload serialization failed: {}", e.getMessage());
            return;
        }
        final String body = json;
        // Serialisation above is cheap and stays on the caller's thread; the socket writes do not.
        delivery.execute(() -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().name(eventName).data(body));
                    uiUpdates.incrementAndGet();
                } catch (Exception e) {
                    // Gone or wedged: drop it now so it cannot hold up the next broadcast.
                    emitters.remove(emitter);
                    try { emitter.completeWithError(e); } catch (Exception ignored) { }
                }
            }
        });
    }
}
