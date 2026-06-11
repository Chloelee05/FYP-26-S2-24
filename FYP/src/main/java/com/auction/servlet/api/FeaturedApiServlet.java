package com.auction.servlet.api;

import com.auction.dao.FeaturedListingDAO;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** GET /api/featured — active promoted listings. */
@WebServlet("/api/featured")
public class FeaturedApiServlet extends ApiBase {

    private final FeaturedListingDAO featuredDAO = new FeaturedListingDAO();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        int limit = Math.min(20, Math.max(1, parseInt(param(req, "limit"), 8)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("results", featuredDAO.listActiveFeatured(limit));
        ok(resp, body);
    }

    private int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }
}
