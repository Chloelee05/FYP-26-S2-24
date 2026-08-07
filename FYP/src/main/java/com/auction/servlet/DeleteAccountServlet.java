package com.auction.servlet;

import com.auction.dao.UserDAO;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

/**
 * Self-service account closure (SCRUM-9). Anonymises PII via {@link UserDAO#deleteAccount(int)},
 * then invalidates the session so no further authenticated requests are possible.
 *
 * <p>The deletion is a soft delete: the row stays so that past auctions, bids and orders keep
 * referring to something, but the identifying fields are anonymised and the status moves to
 * DELETED, which {@code LoginServlet} then refuses to sign in. That is the PDPA compromise
 * between erasing personal data and preserving transaction history.</p>
 *
 * <p>Legacy JSP flow behind {@code AuthFilter}; the SPA uses {@code /api/account/*} in
 * {@code AccountApiServlet}.</p>
 */
@WebServlet("/protected/account/delete")
public class DeleteAccountServlet extends HttpServlet {

    /** Must match the hidden field posted from the confirmation modal. */
    public static final String CONFIRM_TOKEN = "DELETE";

    private UserDAO userDAO;

    public DeleteAccountServlet() {
        this.userDAO = new UserDAO();
    }

    /** For unit tests. */
    public void setUserDAO(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    /**
     * Closes the signed-in account. The target is always the session's own user id, so there is
     * no way to aim this at somebody else. A request without the exact {@code confirm=DELETE}
     * value is bounced back to the dashboard rather than treated as an error, which keeps a
     * stray or replayed POST from destroying an account.
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        HttpSession session = req.getSession(false);
        Integer userId = AccountManagementServlet.readUserId(session);
        if (userId == null) {
            resp.sendRedirect(req.getContextPath() + "/login");
            return;
        }

        String confirm = req.getParameter("confirm");
        if (!CONFIRM_TOKEN.equals(confirm)) {
            resp.sendRedirect(req.getContextPath() + "/protected/account");
            return;
        }

        boolean removed = userDAO.deleteAccount(userId);
        if (!removed) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Account not found");
            return;
        }

        if (session != null) {
            session.invalidate();
        }

        resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        resp.setHeader("Pragma", "no-cache");
        resp.setDateHeader("Expires", 0);

        resp.sendRedirect(req.getContextPath() + "/login?accountClosed=1");
    }
}
