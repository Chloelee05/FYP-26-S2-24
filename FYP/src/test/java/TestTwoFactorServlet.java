import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.auction.dao.UserDAO;
import com.auction.model.Role;
import com.auction.model.User;
import com.auction.servlet.TwoFactorServlet;
import com.auction.util.RbacUtil;
import com.auction.util.SecurityUtil;
import com.auction.util.TotpUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;

public class TestTwoFactorServlet extends Mockito {

    private static class TwoFactorServletWrapper extends TwoFactorServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException { super.doPost(req, resp); }
    }

    // RBAC — RbacUtil.hasRole() with mocked sessions (parameterized)

    @ParameterizedTest
    @CsvSource({
        "ADMIN,  ADMIN,  true",
        "BUYER,  ADMIN,  false",
        "SELLER, ADMIN,  false",
        "ADMIN,  BUYER,  false",
        "BUYER,  BUYER,  true",
        "SELLER, BUYER,  false",
        "ADMIN,  SELLER, false",
        "BUYER,  SELLER, false",
        "SELLER, SELLER, true"
    })
    public void testHasRoleMatchesExpected(String sessionRole, String requiredRole, boolean expected) {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute("userId")).thenReturn(1);
        when(session.getAttribute("userRole")).thenReturn(sessionRole.trim());

        assertEquals(expected, RbacUtil.hasRole(session, Role.valueOf(requiredRole.trim())));
    }

    @Test
    public void testIsAdminTrueOnlyForAdminRole() {
        HttpSession admin  = mock(HttpSession.class);
        HttpSession buyer  = mock(HttpSession.class);
        HttpSession seller = mock(HttpSession.class);

        when(admin.getAttribute("userId")).thenReturn(1);
        when(admin.getAttribute("userRole")).thenReturn("ADMIN");
        when(buyer.getAttribute("userId")).thenReturn(2);
        when(buyer.getAttribute("userRole")).thenReturn("BUYER");
        when(seller.getAttribute("userId")).thenReturn(3);
        when(seller.getAttribute("userRole")).thenReturn("SELLER");

        assertTrue(RbacUtil.isAdmin(admin));
        assertFalse(RbacUtil.isAdmin(buyer));
        assertFalse(RbacUtil.isAdmin(seller));
    }

    @Test
    public void testIsAuthenticatedFalseWhenUserIdAbsent() {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute("userId")).thenReturn(null);

        assertFalse(RbacUtil.isAuthenticated(session));
        assertFalse(RbacUtil.hasRole(session, Role.BUYER));
    }

    @Test
    public void testNullSessionDeniesAllAccess() {
        assertFalse(RbacUtil.isAuthenticated(null));
        assertFalse(RbacUtil.isAdmin(null));
        assertFalse(RbacUtil.isBuyer(null));
        assertFalse(RbacUtil.isSeller(null));
        assertFalse(RbacUtil.hasRole(null, Role.ADMIN));
    }

    // User model — 2FA state change assertions on mocked User objects

    @Test
    public void testUserTwoFactorFieldsDefaultToDisabled() {
        User user = new User("alice", "alice@email.com", "hash", Role.BUYER);
        assertFalse(user.isTwoFactorEnabled());
        assertNull(user.getTwoFactorSecret());
    }

    @Test
    public void testUserTwoFactorStateChangesToEnabled() {
        User user = new User("alice", "alice@email.com", "hash", Role.BUYER);

        user.setTwoFactorEnabled(true);
        user.setTwoFactorSecret("encryptedSecret");

        assertTrue(user.isTwoFactorEnabled());
        assertEquals("encryptedSecret", user.getTwoFactorSecret());
    }

    @Test
    public void testUserTwoFactorStateChangesToDisabled() {
        User user = new User("alice", "alice@email.com", "hash", Role.BUYER);
        user.setTwoFactorEnabled(true);
        user.setTwoFactorSecret("encryptedSecret");

        user.setTwoFactorEnabled(false);
        user.setTwoFactorSecret(null);

        assertFalse(user.isTwoFactorEnabled());
        assertNull(user.getTwoFactorSecret());
    }

    // TotpUtil — secret generation and OTP verification logic

    @Test
    public void testGeneratedSecretIsValidBase32() {
        String secret = TotpUtil.generateSecret();
        assertNotNull(secret);
        assertFalse(secret.isBlank());
        // Base32 uses only A-Z and 2-7
        assertTrue(secret.matches("[A-Z2-7]+"), "Secret must be a valid Base32 string");
    }

    @Test
    public void testTwoSecretsAreNeverEqual() {
        String s1 = TotpUtil.generateSecret();
        String s2 = TotpUtil.generateSecret();
        assertNotEquals(s1, s2, "Each generated secret must be unique");
    }

    @Test
    public void testVerifyCodeReturnsTrueForCurrentCode() {
        String secret = TotpUtil.generateSecret();
        String code   = TotpUtil.generateCode(secret);
        assertTrue(TotpUtil.verifyCode(secret, code),
                "verifyCode must accept the code generated for the current time step");
    }

    @Test
    public void testVerifyCodeReturnsFalseForWrongCode() {
        String secret   = TotpUtil.generateSecret();
        String validCode = TotpUtil.generateCode(secret);
        // Shift by one digit to guarantee invalidity
        int shifted = (Integer.parseInt(validCode) + 3) % 1_000_000;
        String invalidCode = String.format("%06d", shifted);

        // Verify the shifted code is not accidentally the same (astronomically unlikely)
        assumeValidCodeDiffers(validCode, invalidCode);
        assertFalse(TotpUtil.verifyCode(secret, invalidCode));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"12345", "1234567", "ABCDEF", "  ", "00 000"})
    public void testVerifyCodeReturnsFalseForMalformedCode(String code) {
        String secret = TotpUtil.generateSecret();
        assertFalse(TotpUtil.verifyCode(secret, code),
                "verifyCode must reject malformed or wrong-length codes");
    }

    @Test
    public void testVerifyCodeReturnsFalseForNullSecret() {
        assertFalse(TotpUtil.verifyCode(null, "123456"));
    }

    @Test
    public void testGenerateTotpUriContainsRequiredFields() {
        String secret = TotpUtil.generateSecret();
        String uri    = TotpUtil.generateTotpUri(secret, "user@email.com", "OnlineAuction");

        assertTrue(uri.startsWith("otpauth://totp/"), "URI must use otpauth://totp scheme");
        assertTrue(uri.contains("secret=" + secret),  "URI must embed the secret");
        assertTrue(uri.contains("issuer=OnlineAuction"), "URI must embed the issuer");
        assertTrue(uri.contains("digits=6"),           "URI must specify 6 digits");
        assertTrue(uri.contains("period=30"),          "URI must specify 30-second period");
    }

    // TwoFactorServlet — action=setup

    @Test
    public void testSetupRequiresAuthentication() throws Exception {
        TwoFactorServletWrapper servlet = new TwoFactorServletWrapper();
        servlet.setUserDAO(mock(UserDAO.class));

        HttpServletRequest  request  = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        // getSession(false) returns null — unauthenticated
        when(request.getSession(false)).thenReturn(null);
        when(request.getParameter("action")).thenReturn("setup");

        servlet.doPost(request, response);

        verify(request).setAttribute(eq("Error"), eq("Authentication required."));
    }

    @Test
    public void testSetupGeneratesSecretAndUri() throws Exception {
        TwoFactorServletWrapper servlet = new TwoFactorServletWrapper();
        servlet.setUserDAO(mock(UserDAO.class));

        HttpServletRequest  request  = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        HttpSession         session  = mock(HttpSession.class);

        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("userId")).thenReturn(1);
        when(session.getAttribute("userRole")).thenReturn("BUYER");
        when(session.getAttribute("sessionEmail")).thenReturn("alice@email.com");
        when(request.getParameter("action")).thenReturn("setup");

        servlet.doPost(request, response);

        // Secret must be stored in session for the confirm step
        ArgumentCaptor<String> secretCaptor = ArgumentCaptor.forClass(String.class);
        verify(session).setAttribute(eq("pending2faSecret"), secretCaptor.capture());
        String pendingSecret = secretCaptor.getValue();
        assertTrue(pendingSecret.matches("[A-Z2-7]+"), "Pending secret must be Base32");

        // TOTP URI and plaintext secret must be set on the request for the view
        verify(request).setAttribute(eq("totpUri"),
                argThat(v -> v.toString().startsWith("otpauth://totp/")));
        verify(request).setAttribute(eq("totpSecret"), eq(pendingSecret));
        verify(request).setAttribute(eq("Setup"), anyString());
    }

    // TwoFactorServlet — action=confirm

    @Test
    public void testConfirmValidOtpEnablesTwoFactor() throws Exception {
        String secret   = TotpUtil.generateSecret();
        String validCode = TotpUtil.generateCode(secret);

        UserDAO mockDAO = mock(UserDAO.class);
        when(mockDAO.enableTwoFactor(eq("alice@email.com"), anyString())).thenReturn(true);

        TwoFactorServletWrapper servlet = new TwoFactorServletWrapper();
        servlet.setUserDAO(mockDAO);

        HttpServletRequest  request  = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        HttpSession         session  = mock(HttpSession.class);

        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("userId")).thenReturn(1);
        when(session.getAttribute("userRole")).thenReturn("BUYER");
        when(session.getAttribute("sessionEmail")).thenReturn("alice@email.com");
        when(session.getAttribute("pending2faSecret")).thenReturn(secret);
        when(request.getParameter("action")).thenReturn("confirm");
        when(request.getParameter("otpCode")).thenReturn(validCode);

        servlet.doPost(request, response);

        // Secret must be encrypted before persistence
        ArgumentCaptor<String> encCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockDAO).enableTwoFactor(eq("alice@email.com"), encCaptor.capture());
        assertEquals(secret, SecurityUtil.decrypt(encCaptor.getValue()),
                "Secret stored in DB must decrypt back to the original plaintext");

        // Session must reflect the new 2FA state
        verify(session).setAttribute(eq("twoFactorEnabled"), eq(true));
        verify(session).removeAttribute("pending2faSecret");
        verify(request).setAttribute(eq("TwoFactorEnabled"), anyString());
    }

    @Test
    public void testConfirmInvalidOtpRejectsEnable() throws Exception {
        String secret = TotpUtil.generateSecret();
        String valid  = TotpUtil.generateCode(secret);
        int shifted   = (Integer.parseInt(valid) + 3) % 1_000_000;
        String invalid = String.format("%06d", shifted);
        assumeValidCodeDiffers(valid, invalid);

        UserDAO mockDAO = mock(UserDAO.class);
        TwoFactorServletWrapper servlet = new TwoFactorServletWrapper();
        servlet.setUserDAO(mockDAO);

        HttpServletRequest  request  = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        HttpSession         session  = mock(HttpSession.class);

        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("userId")).thenReturn(1);
        when(session.getAttribute("userRole")).thenReturn("BUYER");
        when(session.getAttribute("sessionEmail")).thenReturn("alice@email.com");
        when(session.getAttribute("pending2faSecret")).thenReturn(secret);
        when(request.getParameter("action")).thenReturn("confirm");
        when(request.getParameter("otpCode")).thenReturn(invalid);

        servlet.doPost(request, response);

        verify(request).setAttribute(eq("Error"), eq("Invalid authenticator code. Please try again."));
        verify(mockDAO, never()).enableTwoFactor(any(), any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    public void testConfirmEmptyOtpRejectsEnable(String otp) throws Exception {
        UserDAO mockDAO = mock(UserDAO.class);
        TwoFactorServletWrapper servlet = new TwoFactorServletWrapper();
        servlet.setUserDAO(mockDAO);

        HttpServletRequest  request  = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        HttpSession         session  = mock(HttpSession.class);

        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("userId")).thenReturn(1);
        when(session.getAttribute("userRole")).thenReturn("BUYER");
        when(session.getAttribute("sessionEmail")).thenReturn("alice@email.com");
        when(session.getAttribute("pending2faSecret")).thenReturn(TotpUtil.generateSecret());
        when(request.getParameter("action")).thenReturn("confirm");
        when(request.getParameter("otpCode")).thenReturn(otp);

        servlet.doPost(request, response);

        verify(request).setAttribute(eq("Error"), eq("Authenticator code is required."));
        verify(mockDAO, never()).enableTwoFactor(any(), any());
    }

    // TwoFactorServlet — action=disable

    @Test
    public void testDisableValidOtpDisablesTwoFactor() throws Exception {
        String plainSecret      = TotpUtil.generateSecret();
        String encryptedSecret  = SecurityUtil.encrypt(plainSecret);
        String validCode        = TotpUtil.generateCode(plainSecret);

        User user2fa = new User("alice", "alice@email.com", "hash", Role.BUYER);
        user2fa.setTwoFactorEnabled(true);
        user2fa.setTwoFactorSecret(encryptedSecret);

        UserDAO mockDAO = mock(UserDAO.class);
        when(mockDAO.getUserByEmail("alice@email.com")).thenReturn(user2fa);
        when(mockDAO.disableTwoFactor("alice@email.com")).thenReturn(true);

        TwoFactorServletWrapper servlet = new TwoFactorServletWrapper();
        servlet.setUserDAO(mockDAO);

        HttpServletRequest  request  = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        HttpSession         session  = mock(HttpSession.class);

        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("userId")).thenReturn(1);
        when(session.getAttribute("userRole")).thenReturn("BUYER");
        when(session.getAttribute("sessionEmail")).thenReturn("alice@email.com");
        when(request.getParameter("action")).thenReturn("disable");
        when(request.getParameter("otpCode")).thenReturn(validCode);

        servlet.doPost(request, response);

        verify(mockDAO).disableTwoFactor("alice@email.com");
        verify(session).setAttribute(eq("twoFactorEnabled"), eq(false));
        verify(request).setAttribute(eq("TwoFactorDisabled"), anyString());
    }

    @Test
    public void testDisableInvalidOtpRejectsDisable() throws Exception {
        String plainSecret     = TotpUtil.generateSecret();
        String encryptedSecret = SecurityUtil.encrypt(plainSecret);
        String valid           = TotpUtil.generateCode(plainSecret);
        int shifted            = (Integer.parseInt(valid) + 3) % 1_000_000;
        String invalid         = String.format("%06d", shifted);
        assumeValidCodeDiffers(valid, invalid);

        User user2fa = new User("alice", "alice@email.com", "hash", Role.BUYER);
        user2fa.setTwoFactorEnabled(true);
        user2fa.setTwoFactorSecret(encryptedSecret);

        UserDAO mockDAO = mock(UserDAO.class);
        when(mockDAO.getUserByEmail("alice@email.com")).thenReturn(user2fa);

        TwoFactorServletWrapper servlet = new TwoFactorServletWrapper();
        servlet.setUserDAO(mockDAO);

        HttpServletRequest  request  = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        HttpSession         session  = mock(HttpSession.class);

        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("userId")).thenReturn(1);
        when(session.getAttribute("userRole")).thenReturn("BUYER");
        when(session.getAttribute("sessionEmail")).thenReturn("alice@email.com");
        when(request.getParameter("action")).thenReturn("disable");
        when(request.getParameter("otpCode")).thenReturn(invalid);

        servlet.doPost(request, response);

        verify(request).setAttribute(eq("Error"), eq("Invalid authenticator code."));
        verify(mockDAO, never()).disableTwoFactor(any());
    }

    @Test
    public void testDisableWhenTwoFactorNotEnabledReturnsError() throws Exception {
        User userNo2fa = new User("alice", "alice@email.com", "hash", Role.BUYER);
        userNo2fa.setTwoFactorEnabled(false);

        UserDAO mockDAO = mock(UserDAO.class);
        when(mockDAO.getUserByEmail("alice@email.com")).thenReturn(userNo2fa);

        TwoFactorServletWrapper servlet = new TwoFactorServletWrapper();
        servlet.setUserDAO(mockDAO);

        HttpServletRequest  request  = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        HttpSession         session  = mock(HttpSession.class);

        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("userId")).thenReturn(1);
        when(session.getAttribute("userRole")).thenReturn("BUYER");
        when(session.getAttribute("sessionEmail")).thenReturn("alice@email.com");
        when(request.getParameter("action")).thenReturn("disable");
        when(request.getParameter("otpCode")).thenReturn("123456");

        servlet.doPost(request, response);

        verify(request).setAttribute(eq("Error"), eq("2FA is not enabled on this account."));
        verify(mockDAO, never()).disableTwoFactor(any());
    }

    /** Skip the test if the shifted code accidentally matches (1-in-1M chance). */
    private static void assumeValidCodeDiffers(String valid, String shifted) {
        org.junit.jupiter.api.Assumptions.assumeTrue(!valid.equals(shifted),
                "Shifted code collided with valid code — skipping (1-in-1M event)");
    }
}
