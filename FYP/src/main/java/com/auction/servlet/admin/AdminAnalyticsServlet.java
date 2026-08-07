package com.auction.servlet.admin;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Serves the admin analytics page. It only forwards to the JSP: the charts on that page are
 * static placeholders, so there is no DAO involved and nothing is loaded here.
 * Part of the legacy JSP admin console behind {@code AdminFilter}. The live platform figures
 * come from {@code /api/stats} and {@code /api/admin/*} for the SPA.
 */
@WebServlet("/admin/analytics")
public class AdminAnalyticsServlet extends HttpServlet {

    /** Marks the sidebar entry as active and renders the page. */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.setAttribute("adminActiveNav", "analytics");
        req.getRequestDispatcher("/WEB-INF/views/admin/analytics.jsp").forward(req, resp);
    }
}
