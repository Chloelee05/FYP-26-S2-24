package com.auction.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.AsyncContext;

import java.io.PrintWriter;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * In-memory pub/sub hub for real-time auction updates delivered over Server-Sent
 * Events (SSE). Subscribers are {@link AsyncContext}s registered per auction id.
 *
 * <p>A single shared scheduler emits heartbeat comments every 25s so idle
 * connections (and any intermediary proxies) are kept alive. Dead connections
 * are detected on write failure and pruned.</p>
 *
 * <p>This is process-local state and is intentionally simple: it fits a single
 * Tomcat instance, which is the project's deployment model.</p>
 */
public final class AuctionEventBus {

    private static final Logger LOG = Logger.getLogger(AuctionEventBus.class.getName());
    private static final AuctionEventBus INSTANCE = new AuctionEventBus();
    /**
     * Some reverse proxies/CDNs terminate "idle" SSE connections aggressively.
     * A short heartbeat keeps the stream alive under those intermediaries.
     */
    private static final int HEARTBEAT_SECONDS = 5;

    private final Map<Long, Set<AsyncContext>> subscribers = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private AuctionEventBus() {
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(this::sendHeartbeats,
                HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    public static AuctionEventBus getInstance() {
        return INSTANCE;
    }

    public void subscribe(long auctionId, AsyncContext ctx) {
        subscribers.computeIfAbsent(auctionId, k -> ConcurrentHashMap.newKeySet()).add(ctx);
    }

    public void unsubscribe(long auctionId, AsyncContext ctx) {
        Set<AsyncContext> set = subscribers.get(auctionId);
        if (set != null) {
            set.remove(ctx);
            if (set.isEmpty()) subscribers.remove(auctionId);
        }
    }

    /**
     * Serializes {@code data} to JSON and broadcasts it as a named SSE event to all
     * subscribers of {@code auctionId}. Never throws; dead subscribers are pruned.
     */
    public void publish(long auctionId, String eventName, Object data) {
        Set<AsyncContext> set = subscribers.get(auctionId);
        if (set == null || set.isEmpty()) return;

        String json;
        try {
            json = mapper.writeValueAsString(data);
        } catch (Exception e) {
            LOG.warning("SSE payload serialization failed: " + e.getMessage());
            return;
        }

        String frame = "event: " + eventName + "\n" + "data: " + json + "\n\n";
        for (AsyncContext ctx : set) {
            if (!write(ctx, frame)) {
                unsubscribe(auctionId, ctx);
                complete(ctx);
            }
        }
    }

    private void sendHeartbeats() {
        for (Map.Entry<Long, Set<AsyncContext>> entry : subscribers.entrySet()) {
            for (AsyncContext ctx : entry.getValue()) {
                if (!write(ctx, ": heartbeat\n\n")) {
                    entry.getValue().remove(ctx);
                    complete(ctx);
                }
            }
        }
    }

    private boolean write(AsyncContext ctx, String payload) {
        try {
            PrintWriter writer = ctx.getResponse().getWriter();
            writer.write(payload);
            writer.flush();
            return !writer.checkError();
        } catch (Exception e) {
            return false;
        }
    }

    private void complete(AsyncContext ctx) {
        try { ctx.complete(); } catch (Exception ignored) { }
    }
}
