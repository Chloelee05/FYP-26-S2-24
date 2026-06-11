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
 */
@WebServlet("/api/session")
public class SessionApiServlet extends ApiBase {

    private UserDAO userDAO;

    public SessionApiServlet() {
        this.userDAO = new UserDAO();
    }

    /** Test hook */
    public void setUserDAO(UserDAO userDAO) { this.userDAO = userDAO; }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Integer userId = sessionUserId(req);
        if (userId == null) {
            unauthorized(resp);
            return;
        }

        User user = userDAO.getUserById(userId);
        if (user == null) {
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
        body.put("profileImageUrl", user.getProfileImageUrl());
        body.put("twoFactorEnabled", user.isTwoFactorEnabled());
        ok(resp, body);
    }
}
