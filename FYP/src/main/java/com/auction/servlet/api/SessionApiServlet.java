package com.auction.servlet.api;

import com.auction.dao.UserDAO;
import com.auction.model.User;
import com.auction.util.AuthSession;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/session — returns the current logged-in user's identity, or 401.
 * Used by the React AuthContext on startup to hydrate the user state.
 *
 * <p>This is the first call the SPA makes after a page reload: it holds a bearer token in
 * browser storage but no user object, so it asks the server who that token belongs to.
 * The reply is deliberately re-read from {@code users} through {@link UserDAO} rather than
 * echoed from the session, so a role change or a {@code can_sell} grant made by an admin
 * takes effect on the user's next refresh without forcing a re-login.</p>
 */
@WebServlet("/api/session")
public class SessionApiServlet extends ApiBase {

    private UserDAO userDAO;

    public SessionApiServlet() {
        this.userDAO = new UserDAO();
    }

    /** Test hook: lets a unit test supply a stub DAO instead of hitting the database. */
    public void setUserDAO(UserDAO userDAO) { this.userDAO = userDAO; }

    /**
     * Serves GET /api/session. Takes no parameters; identity comes only from the bearer token.
     * Returns 401 when the token is missing or expired, otherwise the user's id, username, email,
     * role, {@code canSell} capability, profile image and two-factor flag.
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Integer userId = sessionUserId(req);
        if (userId == null) {
            unauthorized(resp);
            return;
        }

        User user = userDAO.getUserById(userId);
        if (user == null) {
            // Token points at a user row that is gone, e.g. the account was soft deleted or
            // removed by an admin while the tab stayed open. Kill the session so the stale
            // token cannot keep passing AuthFilter.
            AuthSession s = authSession(req);
            if (s != null) s.invalidate();
            unauthorized(resp);
            return;
        }

        AuthSession s = authSession(req);
        Map<String, Object> body = new LinkedHashMap<>();
        if (s != null) body.put("token", s.getToken());
        body.put("id",       user.getId());
        body.put("username", user.getUsername());
        body.put("email",    user.getEmail());
        body.put("role",     user.getRole() != null ? user.getRole().name() : null);
        body.put("canSell",     user.canSell());
        body.put("profileImageUrl", user.getProfileImageUrl());
        body.put("twoFactorEnabled", user.isTwoFactorEnabled());
        ok(resp, body);
    }
}
