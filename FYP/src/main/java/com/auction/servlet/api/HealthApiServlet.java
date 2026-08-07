package com.auction.servlet.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Load-balancer health probe (risk mitigation).
 *
 * <p>Configure on Render: Settings → Health Check Path → {@code /api/health}.
 * When instance count &gt; 1, Render routes traffic only to healthy instances.</p>
 *
 * <p>Unauthenticated by design, because the platform's probe has no login. It touches no DAO
 * and no database, so a slow query cannot make the instance look dead and get it recycled.</p>
 */
@WebServlet("/api/health")
public class HealthApiServlet extends ApiBase {

    /**
     * Serves GET /api/health. Always 200 with {@code status} of "UP" plus an {@code instance}
     * identifier, which is what tells you which replica answered when several are running.
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        String instance = System.getenv("RENDER_INSTANCE_ID");
        if (instance == null || instance.isBlank()) {
            // Not running on Render (local dev or another host), so fall back to the container hostname.
            instance = System.getenv().getOrDefault("HOSTNAME", "local");
        }
        body.put("instance", instance);
        ok(resp, body);
    }
}
