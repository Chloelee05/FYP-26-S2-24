package com.auction.servlet.admin;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Sends a bare {@code /admin} request on to the dashboard, so an admin can type the short URL.
 * {@code AdminFilter} maps both {@code /admin} and {@code /admin/*}, which is why this servlet
 * needs no role check of its own.
 * Part of the legacy JSP admin console; the SPA admin area talks to {@code /api/admin/*}.
 */
@WebServlet("/admin")
public class AdminRootServlet extends HttpServlet {

    /** Redirects to {@code /admin/dashboard}. */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.sendRedirect(req.getContextPath() + "/admin/dashboard");
    }
}
