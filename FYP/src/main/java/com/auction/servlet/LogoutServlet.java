package com.auction.servlet;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

/**
 * Legacy JSP sign-out endpoint. Destroys the session and sends the visitor back to the login
 * page. The SPA calls {@code /api/auth/logout} in {@code AuthApiServlet} for the same purpose.
 * Once the session is invalidated, {@code AuthFilter} turns any later request to
 * {@code /protected/*} into a redirect, which is what actually enforces the sign-out.
 */
@WebServlet("/logout")
public class LogoutServlet extends HttpServlet {

    /**
     * Invalidates the session if one exists and redirects to the login page. The no-cache
     * headers stop the browser serving a signed-in page from its back-forward cache after
     * the user has pressed the back button.
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        HttpSession session = req.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        resp.setHeader("Pragma", "no-cache");
        resp.sendRedirect(req.getContextPath() + "/login");
    }

    /** Accepts a plain link to /logout as well as a form post, so the navbar can use an anchor. */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doPost(req, resp);
    }
}
