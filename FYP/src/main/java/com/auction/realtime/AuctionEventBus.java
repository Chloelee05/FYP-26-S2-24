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
 * <p>A single shared scheduler emits periodic snapshots so idle connections
 * (and any intermediary proxies) are kept alive. This also makes live updates
 * robust in environments where requests may be routed across multiple app
 * instances (the snapshot is recomputed from the DB).</p>
 *
 * <p>This is process-local state and is intentionally simple: it fits a single
 * Tomcat instance, which is the project's deployment model.</p>
 */
public final class AuctionEventBus {

    private static final Logger LOG = Logger.getLogger(AuctionEventBus.class.getName());
    private static final AuctionEventBus INSTANCE = new AuctionEventBus();
    /** Keep SSE streams alive and clients in sync. */
    private static final int SNAPSHOT_TICK_SECONDS = 5;

    private final Map<Long, Set<AsyncContext>> subscribers = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * Private because the bus is a singleton. Starts the one heartbeat thread that drives
     * {@link #tickSnapshots}; it is a daemon so it cannot keep the JVM alive on shutdown.
     */
    private AuctionEventBus() {
        ScheduledExecutorService tick = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        tick.scheduleAtFixedRate(this::tickSnapshots,
                SNAPSHOT_TICK_SECONDS, SNAPSHOT_TICK_SECONDS, TimeUnit.SECONDS);
    }

    /** The single bus shared by the SSE servlet and by every code path that changes an auction. */
    public static AuctionEventBus getInstance() {
        return INSTANCE;
    }

    /**
     * Registers an open SSE connection to receive events for one auction. Called by
     * {@code AuctionEventServlet} after it starts async mode. The per-auction set is a
     * concurrent set because subscribers arrive on request threads while the heartbeat
     * thread iterates over them.
     */
    public void subscribe(long auctionId, AsyncContext ctx) {
        subscribers.computeIfAbsent(auctionId, k -> ConcurrentHashMap.newKeySet()).add(ctx);
    }

    /**
     * Drops a connection, either because the browser disconnected or because a write failed.
     * The auction key itself is removed once its last subscriber leaves, otherwise the map
     * would grow one dead entry per auction ever viewed.
     */
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

        // SSE wire format: an "event:" line naming the event, a "data:" line holding the JSON,
        // then a blank line to mark the end of the frame. The browser EventSource parses this.
        String frame = "event: " + eventName + "\n" + "data: " + json + "\n\n";
        for (AsyncContext ctx : set) {
            if (!write(ctx, frame)) {
                unsubscribe(auctionId, ctx);
                complete(ctx);
            }
        }
    }

    /**
     * Periodically publish a fresh snapshot for every auction with subscribers.
     * This keeps long-lived SSE connections alive through proxies and ensures
     * clients still receive updates even if a bid request is handled by a
     * different app instance (snapshot is recomputed from the shared DB).
     */
    private void tickSnapshots() {
        for (Long auctionId : subscribers.keySet()) {
            // publishSnapshot ultimately calls back into this bus (publish), so it
            // must never throw and it must tolerate subscribers changing mid-loop.
            AuctionEventPublisher.publishSnapshot(auctionId);
        }
    }

    /**
     * Writes one frame to a subscriber. Returns false if the connection is gone, which is how
     * {@link #publish} decides to prune it. {@code checkError} is needed because PrintWriter
     * swallows IO errors instead of throwing.
     */
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

    /** Ends an async request. Failures are ignored because the context is usually already dead. */
    private void complete(AsyncContext ctx) {
        try { ctx.complete(); } catch (Exception ignored) { }
    }
}
