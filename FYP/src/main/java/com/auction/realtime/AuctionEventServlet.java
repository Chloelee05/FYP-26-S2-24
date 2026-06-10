package com.auction.realtime;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * Server-Sent Events stream for live auction updates.
 *
 * <p>{@code GET /api/auction-events/{auctionId}} opens a {@code text/event-stream}
 * that pushes price/bid-count/status changes published via {@link AuctionEventBus}.
 * Mapped on its own path (not {@code /api/auction/*}) to avoid clashing with
 * {@code AuctionApiServlet} and to enable async support on just this endpoint.</p>
 *
 * <p>The stream carries only public auction data, so it requires no authentication
 * (matching the public auction-detail endpoint).</p>
 */
@WebServlet(value = "/api/auction-events/*", asyncSupported = true)
public class AuctionEventServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long auctionId;
        try {
            String pathInfo = req.getPathInfo(); // "/42"
            if (pathInfo == null || pathInfo.length() < 2) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Auction ID required");
                return;
            }
            auctionId = Long.parseLong(pathInfo.substring(1).split("/")[0]);
        } catch (NumberFormatException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid auction ID");
            return;
        }

        resp.setContentType("text/event-stream");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-cache, no-transform");
        resp.setHeader("Connection", "keep-alive");
        resp.setHeader("X-Accel-Buffering", "no"); // disable proxy buffering

        PrintWriter writer = resp.getWriter();
        writer.write("retry: 3000\n");
        writer.write(": connected\n\n");
        writer.flush();

        AsyncContext ctx = req.startAsync();
        ctx.setTimeout(0); // never time out; heartbeat keeps it alive

        final long id = auctionId;
        AuctionEventBus bus = AuctionEventBus.getInstance();
        ctx.addListener(new AsyncListener() {
            @Override public void onComplete(AsyncEvent e) { bus.unsubscribe(id, ctx); }
            @Override public void onTimeout(AsyncEvent e)  { bus.unsubscribe(id, ctx); ctx.complete(); }
            @Override public void onError(AsyncEvent e)    { bus.unsubscribe(id, ctx); ctx.complete(); }
            @Override public void onStartAsync(AsyncEvent e) { }
        });

        bus.subscribe(auctionId, ctx);

        // Push the current snapshot immediately so late joiners are in sync.
        AuctionEventPublisher.publishSnapshot(auctionId);
    }
}
