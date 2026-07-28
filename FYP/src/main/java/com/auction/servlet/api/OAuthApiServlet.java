package com.auction.servlet.api;

import com.auction.dao.LinkedAccountDAO;
import com.auction.dao.UserDAO;
import com.auction.model.Status;
import com.auction.model.User;
import com.auction.util.AuthSession;
import com.auction.util.SecurityUtil;
import com.auction.util.TokenStore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Third-party (Google) sign-in linking (SCRUM-17).
 *
 * GET  /api/oauth/config  — which providers are configured (Google client ID for the button)
 * GET  /api/oauth/linked  — providers linked to the logged-in account
 * POST /api/oauth/link    — params: provider=google, credential=&lt;Google ID token&gt;
 * POST /api/oauth/unlink  — params: provider=google
 * POST /api/oauth/login   — params: provider=google, credential — sign in with a linked account
 *
 * <p>Google ID tokens (from Google Identity Services) are verified server-side against
 * Google's tokeninfo endpoint; the audience must match {@code GOOGLE_CLIENT_ID}
 * (environment variable or {@code google.client.id} system property).</p>
 */
@WebServlet("/api/oauth/*")
public class OAuthApiServlet extends ApiBase {

    private static final Logger LOG = Logger.getLogger(OAuthApiServlet.class.getName());
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final LinkedAccountDAO linkedAccountDAO = new LinkedAccountDAO();
    private final UserDAO userDAO = new UserDAO();

    static String googleClientId() {
        String env = System.getenv("GOOGLE_CLIENT_ID");
        if (env != null && !env.isBlank()) return env.trim();
        String prop = System.getProperty("google.client.id");
        return (prop == null || prop.isBlank()) ? null : prop.trim();
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo() == null ? "/" : req.getPathInfo();
        switch (path) {
            case "/config": handleConfig(resp); break;
            case "/linked": handleLinked(req, resp); break;
            default: error(resp, 404, "Unknown oauth endpoint"); break;
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo() == null ? "/" : req.getPathInfo();
        switch (path) {
            case "/link":   handleLink(req, resp);   break;
            case "/unlink": handleUnlink(req, resp); break;
            case "/login":  handleLogin(req, resp);  break;
            default: error(resp, 404, "Unknown oauth endpoint"); break;
        }
    }

    private void handleConfig(HttpServletResponse resp) throws IOException {
        String clientId = googleClientId();
        Map<String, Object> google = new LinkedHashMap<>();
        google.put("configured", clientId != null);
        google.put("clientId", clientId);
        ok(resp, Map.of("google", google));
    }

    private void handleLinked(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        try {
            ok(resp, linkedAccountDAO.listForUser(sessionUserId(req)));
        } catch (RuntimeException e) {
            serverError(resp, "Could not load linked accounts. Run DB migrations and try again.");
        }
    }

    private void handleLink(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        String provider = param(req, "provider");
        if (!isSupported(provider)) { badRequest(resp, "Only Google linking is supported right now."); return; }

        GoogleIdentity identity = verifyGoogleCredential(param(req, "credential"), resp);
        if (identity == null) return; // error already written

        try {
            LinkedAccountDAO.LinkResult r =
                    linkedAccountDAO.link(sessionUserId(req), "google", identity.sub, identity.email);
            if (r == LinkedAccountDAO.LinkResult.ALREADY_LINKED_TO_OTHER_USER) {
                error(resp, 409, "This Google account is already linked to a different AuctionHub account.");
                return;
            }
            okMsg(resp, "Google account linked. You can now sign in with Google.");
        } catch (RuntimeException e) {
            serverError(resp, "Could not link the account. Run DB migrations and try again.");
        }
    }

    private void handleUnlink(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        String provider = param(req, "provider");
        if (!isSupported(provider)) { badRequest(resp, "Unknown provider."); return; }
        try {
            boolean removed = linkedAccountDAO.unlink(sessionUserId(req), provider.toLowerCase());
            if (removed) okMsg(resp, "Google account unlinked. Sign in with your email and password.");
            else error(resp, 404, "No linked account to remove.");
        } catch (RuntimeException e) {
            serverError(resp, "Could not unlink the account.");
        }
    }

    /** Signs the user in with a previously linked Google account. */
    private void handleLogin(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String provider = param(req, "provider");
        if (!isSupported(provider)) { badRequest(resp, "Only Google sign-in is supported right now."); return; }

        GoogleIdentity identity = verifyGoogleCredential(param(req, "credential"), resp);
        if (identity == null) return;

        Integer userId = linkedAccountDAO.findUserIdByProvider("google", identity.sub);
        if (userId == null) {
            error(resp, 404, "This Google account is not linked to any AuctionHub account. "
                    + "Sign in with your password first, then link Google in Account Settings.");
            return;
        }

        User user = userDAO.getUserById(userId);
        if (user == null) { error(resp, 404, "Account not found."); return; }
        if (user.getStatusId() == Status.SUSPENDED.getId()) { error(resp, 403, "Your account has been suspended."); return; }
        if (user.getStatusId() == Status.DELETED.getId())   { error(resp, 403, "This account is no longer available."); return; }
        if (user.getStatusId() == Status.PENDING.getId())   { error(resp, 403, "Your account is awaiting administrator approval."); return; }
        if (user.getStatusId() == Status.REJECTED.getId())  { error(resp, 403, "Your registration was not approved. Please contact support."); return; }

        AuthSession session = TokenStore.getInstance().create();
        session.setMaxInactiveInterval(60 * 30);
        session.setAttribute("userId",           user.getId());
        session.setAttribute("userRole",         user.getRole().name());
        session.setAttribute("canSell",         user.canSell());
        session.setAttribute("sessionEmail",     user.getEmail());
        session.setAttribute("twoFactorEnabled", false);
        session.setAttribute("maskedEmail",      SecurityUtil.maskEmail(user.getEmail()));
        session.setAttribute("maskedUsername",   SecurityUtil.maskUsername(user.getUsername()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token",    session.getToken());
        body.put("id",       user.getId());
        body.put("username", user.getUsername());
        body.put("email",    user.getEmail());
        body.put("role",     user.getRole().name());
        body.put("canSell",     user.canSell());
        body.put("profileImageUrl", user.getProfileImageUrl());
        body.put("twoFactorEnabled", false);
        ok(resp, body);
    }

    private boolean isSupported(String provider) {
        return provider != null
                && LinkedAccountDAO.SUPPORTED_PROVIDERS.contains(provider.toLowerCase());
    }

    private static final class GoogleIdentity {
        final String sub;
        final String email;
        GoogleIdentity(String sub, String email) { this.sub = sub; this.email = email; }
    }

    /**
     * Validates a Google ID token via the tokeninfo endpoint and checks the audience.
     * Writes an error response and returns null when the token is missing/invalid.
     */
    private GoogleIdentity verifyGoogleCredential(String credential, HttpServletResponse resp)
            throws IOException {
        if (credential == null) { badRequest(resp, "credential (Google ID token) is required."); return null; }

        String clientId = googleClientId();
        if (clientId == null) {
            error(resp, 503, "Google sign-in is not configured on this server (set GOOGLE_CLIENT_ID).");
            return null;
        }

        try {
            HttpRequest tokenReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://oauth2.googleapis.com/tokeninfo?id_token="
                            + URLEncoder.encode(credential, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> r = HTTP.send(tokenReq, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                error(resp, 401, "Google rejected the sign-in token. Please try again.");
                return null;
            }
            JsonNode node = MAPPER.readTree(r.body());
            String aud = node.path("aud").asText(null);
            String sub = node.path("sub").asText(null);
            String email = node.path("email").asText(null);
            if (sub == null || !clientId.equals(aud)) {
                error(resp, 401, "The Google token was issued for a different application.");
                return null;
            }
            return new GoogleIdentity(sub, email);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warning("Google tokeninfo verification failed: " + e.getMessage());
            error(resp, 502, "Could not verify the Google token. Check the server's internet access.");
            return null;
        }
    }
}
