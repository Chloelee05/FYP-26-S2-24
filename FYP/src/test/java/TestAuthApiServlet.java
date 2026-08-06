import com.auction.dao.PlatformSettingsDAO;
import com.auction.dao.UserDAO;
import com.auction.model.Role;
import com.auction.model.Status;
import com.auction.model.User;
import com.auction.servlet.api.AuthApiServlet;
import com.auction.test.ApiTestSupport;
import com.auction.util.AuthSession;
import com.auction.util.DevMode;
import com.auction.util.LoginAttemptLimiter;
import com.auction.util.MailConfig;
import com.auction.util.OtpStore;
import com.auction.util.SecurityUtil;
import com.auction.util.TokenStore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("AuthApiServlet")
class TestAuthApiServlet {

    private static class Wrapper extends AuthApiServlet {
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doPost(req, resp);
        }
    }

    private UserDAO mockDAO;
    private OtpStore otpStore;
    private LoginAttemptLimiter loginAttemptLimiter;
    private PlatformSettingsDAO mockSettings;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() {
        mockDAO  = mock(UserDAO.class);
        otpStore = new OtpStore();
        // A fresh instance per test, not LoginAttemptLimiter.getInstance() — otherwise
        // lockout state from one test would leak into the next via the shared singleton.
        loginAttemptLimiter = new LoginAttemptLimiter();
        mockSettings = mock(PlatformSettingsDAO.class);
        when(mockSettings.getInt(eq("login_lockout_threshold"), anyInt())).thenReturn(5);
        when(mockSettings.getInt(eq("login_lockout_cooldown_minutes"), anyInt())).thenReturn(15);
        servlet  = new Wrapper();
        servlet.setUserDAO(mockDAO);
        servlet.setOtpStore(otpStore);
        servlet.setLoginAttemptLimiter(loginAttemptLimiter);
        servlet.setPlatformSettingsDAO(mockSettings);
        req  = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
    }

    @Test
    @DisplayName("login missing email → 400")
    void loginMissingEmail() throws Exception {
        when(req.getPathInfo()).thenReturn("/login");
        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        JsonNode body = ApiTestSupport.parse(sw);
        verify(resp).setStatus(400);
        assertTrue(body.get("error").asText().contains("Email"));
    }

    @Test
    @DisplayName("login invalid credentials → 401")
    void loginInvalid() throws Exception {
        when(req.getPathInfo()).thenReturn("/login");
        when(req.getParameter("email")).thenReturn("user@email.com");
        when(req.getParameter("password")).thenReturn("Password1!");
        when(mockDAO.getUserByEmail("user@email.com")).thenReturn(null);

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(401);
    }

    @Test
    @DisplayName("login success returns token")
    void loginSuccess() throws Exception {
        User user = new User("alice", "alice@email.com",
                SecurityUtil.hashPassword("Password1!"), Role.BUYER);
        user.setId(7);
        user.setStatusId(Status.ACTIVE.getId());

        when(req.getPathInfo()).thenReturn("/login");
        when(req.getParameter("email")).thenReturn("alice@email.com");
        when(req.getParameter("password")).thenReturn("Password1!");
        when(mockDAO.getUserByEmail("alice@email.com")).thenReturn(user);

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        JsonNode body = ApiTestSupport.parse(sw);
        verify(resp).setStatus(200);
        assertNotNull(body.get("token"));
        assertEquals("alice", body.get("username").asText());
        assertEquals("BUYER", body.get("role").asText());
    }

    @Test
    @DisplayName("pending account → 403")
    void loginPending() throws Exception {
        User user = new User("bob", "bob@email.com",
                SecurityUtil.hashPassword("Password1!"), Role.BUYER);
        user.setStatusId(Status.PENDING.getId());
        when(req.getPathInfo()).thenReturn("/login");
        when(req.getParameter("email")).thenReturn("bob@email.com");
        when(req.getParameter("password")).thenReturn("Password1!");
        when(mockDAO.getUserByEmail("bob@email.com")).thenReturn(user);

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(403);
    }

    // ── Login brute-force lockout ────────────────────────────────────────────

    /** Wires one wrong-password login attempt against {@code email}, runs it, and returns its writer. */
    private StringWriter wrongPasswordAttempt(String email) throws Exception {
        when(req.getPathInfo()).thenReturn("/login");
        when(req.getParameter("email")).thenReturn(email);
        when(req.getParameter("password")).thenReturn("WrongPassword1!");
        when(mockDAO.getUserByEmail(email)).thenReturn(null);
        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        return sw;
    }

    @Test
    @DisplayName("5 consecutive wrong passwords lock the account out")
    void loginLocksOutAfterThreshold() throws Exception {
        String email = "bruteforced@email.com";
        for (int i = 0; i < 5; i++) {
            wrongPasswordAttempt(email);
        }
        verify(resp, times(5)).setStatus(401);

        StringWriter sw = wrongPasswordAttempt(email);
        JsonNode body = ApiTestSupport.parse(sw);
        verify(resp).setStatus(429);
        assertTrue(body.get("error").asText().toLowerCase().contains("too many"),
                "a lockout must return a distinct rejection, not a generic 401");
    }

    @Test
    @DisplayName("fewer than the threshold does not lock the account out")
    void loginBelowThresholdStillReturns401() throws Exception {
        String email = "user@email.com";
        for (int i = 0; i < 4; i++) {
            wrongPasswordAttempt(email);
        }
        verify(resp, times(4)).setStatus(401);
        verify(resp, never()).setStatus(429);
    }

    @Test
    @DisplayName("a correct login before the threshold resets the failure count")
    void loginSuccessResetsLockoutCounter() throws Exception {
        String email = "alice@email.com";
        User user = new User("alice", email, SecurityUtil.hashPassword("Password1!"), Role.BUYER);
        user.setId(42);
        user.setStatusId(Status.ACTIVE.getId());
        when(mockDAO.getUserByEmail(email)).thenReturn(user);

        for (int i = 0; i < 4; i++) {
            wrongPasswordAttempt(email);
        }

        // wrongPasswordAttempt's stubbing of getUserByEmail(email) -> null must be
        // re-pointed at the real account before the correct-password attempt.
        when(mockDAO.getUserByEmail(email)).thenReturn(user);
        when(req.getPathInfo()).thenReturn("/login");
        when(req.getParameter("email")).thenReturn(email);
        when(req.getParameter("password")).thenReturn("Password1!");
        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(200);

        // Another 4 wrong passwords after the reset must not lock the account out —
        // if the reset had not happened, this 4th failure would be the 8th overall
        // and would already have tripped a threshold of 5.
        for (int i = 0; i < 4; i++) {
            wrongPasswordAttempt(email);
        }
        assertFalse(loginAttemptLimiter.isLockedOut(email));
    }

    @Test
    @DisplayName("locking out one account does not affect a different account")
    void loginLockoutDoesNotAffectOtherAccounts() throws Exception {
        String attacker = "attacker@email.com";
        String other = "other@email.com";
        for (int i = 0; i < 5; i++) {
            wrongPasswordAttempt(attacker);
        }
        assertTrue(loginAttemptLimiter.isLockedOut(attacker));
        assertFalse(loginAttemptLimiter.isLockedOut(other));

        User user = new User("otheruser", other, SecurityUtil.hashPassword("Password1!"), Role.BUYER);
        user.setId(99);
        user.setStatusId(Status.ACTIVE.getId());
        when(mockDAO.getUserByEmail(other)).thenReturn(user);
        when(req.getPathInfo()).thenReturn("/login");
        when(req.getParameter("email")).thenReturn(other);
        when(req.getParameter("password")).thenReturn("Password1!");
        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(200);
    }

    @Test
    @DisplayName("logout removes bearer token")
    void logout() throws Exception {
        AuthSession session = ApiTestSupport.newBuyerSession(1);
        when(req.getPathInfo()).thenReturn("/logout");
        ApiTestSupport.withBearer(req, session);

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(200);
        assertNull(TokenStore.getInstance().get(session.getToken()));
    }

    // ── OTP disclosure (password reset + 2FA) ────────────────────────────────
    //
    // The forgot-password and 2FA responses used to carry the live OTP whenever SMTP was
    // unconfigured, which on the deployment was always. Disclosure now hangs on
    // AUCTION_DEV_MODE alone, so these assert both halves of that switch.

    /** Wires a forgot-password request for an account that exists, and returns the writer. */
    private StringWriter forgotPasswordFor(String email) throws Exception {
        User user = new User("victim", email,
                SecurityUtil.hashPassword("Password1!"), Role.BUYER);
        user.setStatusId(Status.ACTIVE.getId());
        when(req.getPathInfo()).thenReturn("/forgot-password");
        when(req.getParameter("identifier")).thenReturn(email);
        when(mockDAO.getUserByEmail(email)).thenReturn(user);
        return ApiTestSupport.bindJsonWriter(resp);
    }

    @Test
    @DisplayName("forgot-password hides the OTP when dev mode is off")
    void forgotPasswordHidesOtpWithoutDevMode() throws Exception {
        StringWriter sw = forgotPasswordFor("victim@email.com");

        try (MockedStatic<DevMode> dev = mockStatic(DevMode.class);
             MockedStatic<MailConfig> mail = mockStatic(MailConfig.class)) {
            dev.when(DevMode::isEnabled).thenReturn(false);
            mail.when(MailConfig::isSmtpConfigured).thenReturn(false);
            servlet.doPost(req, resp);
        }

        JsonNode body = ApiTestSupport.parse(sw);
        verify(resp).setStatus(200);
        assertNull(body.get("devOtp"),
                "an unconfigured mailer must not turn the response into an OTP oracle");
        assertEquals("If that account exists, an OTP has been sent.",
                body.get("message").asText());
    }

    @Test
    @DisplayName("forgot-password returns the OTP when dev mode is on")
    void forgotPasswordReturnsOtpInDevMode() throws Exception {
        StringWriter sw = forgotPasswordFor("victim@email.com");

        try (MockedStatic<DevMode> dev = mockStatic(DevMode.class);
             MockedStatic<MailConfig> mail = mockStatic(MailConfig.class)) {
            dev.when(DevMode::isEnabled).thenReturn(true);
            mail.when(MailConfig::isSmtpConfigured).thenReturn(false);
            servlet.doPost(req, resp);
        }

        JsonNode body = ApiTestSupport.parse(sw);
        String devOtp = body.get("devOtp").asText();
        assertEquals(6, devOtp.length());
        assertTrue(otpStore.verify("victim@email.com", devOtp),
                "the disclosed code should be the one actually stored");
    }

    @Test
    @DisplayName("forgot-password reply is identical for unknown accounts")
    void forgotPasswordDoesNotEnumerate() throws Exception {
        when(req.getPathInfo()).thenReturn("/forgot-password");
        when(req.getParameter("identifier")).thenReturn("nobody@email.com");
        when(mockDAO.getUserByEmail("nobody@email.com")).thenReturn(null);
        StringWriter unknown = ApiTestSupport.bindJsonWriter(resp);

        try (MockedStatic<DevMode> dev = mockStatic(DevMode.class)) {
            dev.when(DevMode::isEnabled).thenReturn(false);
            servlet.doPost(req, resp);
        }

        assertEquals("If that account exists, an OTP has been sent.",
                ApiTestSupport.parse(unknown).get("message").asText());
    }

    @Test
    @DisplayName("2FA login hides the OTP when dev mode is off")
    void twoFactorLoginHidesOtpWithoutDevMode() throws Exception {
        StringWriter sw = twoFactorLoginFor("2fa@email.com");

        try (MockedStatic<DevMode> dev = mockStatic(DevMode.class);
             MockedStatic<MailConfig> mail = mockStatic(MailConfig.class)) {
            dev.when(DevMode::isEnabled).thenReturn(false);
            mail.when(MailConfig::isSmtpConfigured).thenReturn(false);
            servlet.doPost(req, resp);
        }

        JsonNode body = ApiTestSupport.parse(sw);
        assertTrue(body.get("requires2fa").asBoolean());
        assertNull(body.get("devOtp"),
                "the second factor must not be handed back with the challenge");
    }

    @Test
    @DisplayName("2FA login returns the OTP when dev mode is on")
    void twoFactorLoginReturnsOtpInDevMode() throws Exception {
        StringWriter sw = twoFactorLoginFor("2fa@email.com");

        try (MockedStatic<DevMode> dev = mockStatic(DevMode.class);
             MockedStatic<MailConfig> mail = mockStatic(MailConfig.class)) {
            dev.when(DevMode::isEnabled).thenReturn(true);
            mail.when(MailConfig::isSmtpConfigured).thenReturn(false);
            servlet.doPost(req, resp);
        }

        JsonNode body = ApiTestSupport.parse(sw);
        assertEquals(6, body.get("devOtp").asText().length());
    }

    /** Wires a valid login for a 2FA-enabled account, and returns the writer. */
    private StringWriter twoFactorLoginFor(String email) throws Exception {
        User user = new User("twofa", email,
                SecurityUtil.hashPassword("Password1!"), Role.BUYER);
        user.setId(11);
        user.setStatusId(Status.ACTIVE.getId());
        user.setTwoFactorEnabled(true);
        when(req.getPathInfo()).thenReturn("/login");
        when(req.getParameter("email")).thenReturn(email);
        when(req.getParameter("password")).thenReturn("Password1!");
        when(mockDAO.getUserByEmail(email)).thenReturn(user);
        return ApiTestSupport.bindJsonWriter(resp);
    }

    // ── Change password ──────────────────────────────────────────────────────
    //
    // getUserById() intentionally omits the password column (see UserDAO), so
    // handleChangePassword must re-look-up the account by email to actually get a
    // hash to verify against — otherwise every attempt fails with a false
    // "Current password is incorrect.", regardless of what was typed.

    @Test
    @DisplayName("change-password with correct current password succeeds")
    void changePasswordSucceeds() throws Exception {
        String email = "changer@email.com";
        User byId = new User("changer", email, null, Role.BUYER);
        byId.setId(3);
        User byEmail = new User("changer", email,
                SecurityUtil.hashPassword("Correct1!"), Role.BUYER);
        byEmail.setId(3);
        when(mockDAO.getUserById(3)).thenReturn(byId);
        when(mockDAO.getUserByEmail(email)).thenReturn(byEmail);
        when(mockDAO.updatePassword(eq(email), anyString())).thenReturn(true);

        AuthSession session = ApiTestSupport.newBuyerSession(3);
        ApiTestSupport.withBearer(req, session);
        when(req.getPathInfo()).thenReturn("/change-password");
        when(req.getParameter("currentPassword")).thenReturn("Correct1!");
        when(req.getParameter("newPassword")).thenReturn("NewPassword1!");
        when(req.getParameter("confirmPassword")).thenReturn("NewPassword1!");

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(mockDAO).updatePassword(eq(email), anyString());
        verify(resp).setStatus(200);
    }

    @Test
    @DisplayName("change-password with wrong current password → 401")
    void changePasswordWrongCurrentPassword() throws Exception {
        String email = "changer2@email.com";
        User byId = new User("changer2", email, null, Role.BUYER);
        byId.setId(4);
        User byEmail = new User("changer2", email,
                SecurityUtil.hashPassword("Correct1!"), Role.BUYER);
        byEmail.setId(4);
        when(mockDAO.getUserById(4)).thenReturn(byId);
        when(mockDAO.getUserByEmail(email)).thenReturn(byEmail);

        AuthSession session = ApiTestSupport.newBuyerSession(4);
        ApiTestSupport.withBearer(req, session);
        when(req.getPathInfo()).thenReturn("/change-password");
        when(req.getParameter("currentPassword")).thenReturn("Wrong1!");
        when(req.getParameter("newPassword")).thenReturn("NewPassword1!");
        when(req.getParameter("confirmPassword")).thenReturn("NewPassword1!");

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(mockDAO, never()).updatePassword(anyString(), anyString());
        verify(resp).setStatus(401);
    }

    @Test
    @DisplayName("register duplicate email → 409")
    void registerDuplicateEmail() throws Exception {
        when(req.getPathInfo()).thenReturn("/register");
        when(req.getParameter("username")).thenReturn("newuser");
        when(req.getParameter("email")).thenReturn("taken@email.com");
        when(req.getParameter("password")).thenReturn("Password1!");
        when(req.getParameter("confirmPassword")).thenReturn("Password1!");
        when(req.getParameter("role")).thenReturn("BUYER");
        when(req.getParameter("termsAccept")).thenReturn("true");
        when(mockDAO.checkEmail("taken@email.com")).thenReturn(true);

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(409);
    }
}
