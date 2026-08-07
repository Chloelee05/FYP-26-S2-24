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
 *
 * <p>Server-side verification is the point: the credential the browser sends is never trusted
 * on its own. Google is asked to confirm it, and the audience check rejects a valid Google
 * token that was issued for some other application.</p>
 *
 * <p>Linking is not registration. /login only works for a Google account already linked to an
 * AuctionHub account, so signing in with Google cannot create an account and bypass the admin
 * approval step. The same suspended, deleted, pending and rejected status gates as password
 * login apply. Links live in {@code linked_accounts} through {@link LinkedAccountDAO}.</p>
 */
@WebServlet("/api/oauth/*")
public class OAuthApiServlet extends ApiBase {

    private static final Logger LOG = Logger.getLogger(OAuthApiServlet.class.getName());
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final LinkedAccountDAO linkedAccountDAO = new LinkedAccountDAO();
    private final UserDAO userDAO = new UserDAO();

    /**
     * The configured Google client id, or null when Google sign-in is switched off. Read from the
     * environment first so deployment does not need a code change; the system property is the
     * local development fallback.
     */
    static String googleClientId() {
        String env = System.getenv("GOOGLE_CLIENT_ID");
        if (env != null && !env.isBlank()) return env.trim();
        String prop = System.getProperty("google.client.id");
        return (prop == null || prop.isBlank()) ? null : prop.trim();
    }

    /** Routes the reads: /config is public, /linked needs a session. */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo() == null ? "/" : req.getPathInfo();
        switch (path) {
            case "/config": handleConfig(resp); break;
            case "/linked": handleLinked(req, resp); break;
            default: error(resp, 404, "Unknown oauth endpoint"); break;
        }
    }

    /** Routes the writes. /link and /unlink require a session; /login is by definition unauthenticated. */
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

    /**
     * GET /api/oauth/config. Public, because the SPA needs the client id before anyone is signed
     * in to decide whether to render the Google button. A client id is a public value by design,
     * unlike the client secret, which this flow does not use at all.
     */
    private void handleConfig(HttpServletResponse resp) throws IOException {
        String clientId = googleClientId();
        Map<String, Object> google = new LinkedHashMap<>();
        google.put("configured", clientId != null);
        google.put("clientId", clientId);
        ok(resp, Map.of("google", google));
    }

    /** GET /api/oauth/linked. Lists the providers linked to the caller's own account, for Account Settings. */
    private void handleLinked(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        try {
            ok(resp, linkedAccountDAO.listForUser(sessionUserId(req)));
        } catch (RuntimeException e) {
            serverError(resp, "Could not load linked accounts. Run DB migrations and try again.");
        }
    }

    /**
     * POST /api/oauth/link with {@code provider} and {@code credential}. Attaches a verified
     * Google identity to the signed-in account. The Google subject id is unique per account, so
     * a 409 comes back if that Google account already belongs to a different member, which stops
     * two AuctionHub accounts sharing one Google login.
     */
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

    /**
     * POST /api/oauth/unlink with {@code provider}. Removes the link for the caller's own account.
     * The password login still exists afterwards, so unlinking cannot lock anyone out.
     */
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

    /**
     * Signs the user in with a previously linked Google account.
     *
     * <p>POST /api/oauth/login with {@code provider} and {@code credential}. The account is found
     * by the Google subject id rather than by email, because an email address can be changed on
     * either side while the subject id is stable. An unlinked Google account gets 404 with
     * instructions instead of being registered. Issues the same token and body as a password
     * login, and does not go through 2FA since Google already applied its own.</p>
     */
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

    /** Checks the provider name against the allow-list, so only Google is accepted at present. */
    private boolean isSupported(String provider) {
        return provider != null
                && LinkedAccountDAO.SUPPORTED_PROVIDERS.contains(provider.toLowerCase());
    }

    /** The two fields taken from a verified Google token: the stable subject id and the email. */
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
            // The audience check is what ties the token to this application. Without it, a token
            // Google issued for any other site would be accepted here as a valid sign-in.
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
