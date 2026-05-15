import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.auction.dao.UserDAO;
import com.auction.model.Role;
import com.auction.model.User;
import com.auction.servlet.ForgotPasswordServlet;
import com.auction.servlet.ResetPasswordServlet;
import com.auction.util.OtpStore;
import com.auction.util.SecurityUtil;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;

public class TestPasswordResetServlet extends Mockito {

    private static void stubForward(HttpServletRequest request) {
        RequestDispatcher dispatcher = mock(RequestDispatcher.class);
        when(request.getRequestDispatcher(anyString())).thenReturn(dispatcher);
    }

    // Servlet wrappers 

    private static class ForgotServletWrapper extends ForgotPasswordServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException { super.doPost(req, resp); }
    }

    private static class ResetServletWrapper extends ResetPasswordServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException { super.doPost(req, resp); }
    }

    // ForgotPasswordServlet — credential edge cases

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    public void testEmptyIdentifierRejectsOtpRequest(String identifier) throws Exception {
        UserDAO mockDAO = mock(UserDAO.class);
        OtpStore mockStore = mock(OtpStore.class);
        ForgotServletWrapper servlet = new ForgotServletWrapper();
        servlet.setUserDAO(mockDAO);
        servlet.setOtpStore(mockStore);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        stubForward(request);
        when(request.getParameter("identifier")).thenReturn(identifier);

        servlet.doPost(request, response);

        verify(request).setAttribute(eq("Error"), eq("Email or phone number is required."));
        verify(mockStore, never()).generateAndStore(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"notanemail@invalid", "@nodomain.com", "user@.com", "nodomain@", "user name@email.com"})
    public void testInvalidEmailFormatRejectsOtpRequest(String email) throws Exception {
        UserDAO mockDAO = mock(UserDAO.class);
        OtpStore mockStore = mock(OtpStore.class);
        ForgotServletWrapper servlet = new ForgotServletWrapper();
        servlet.setUserDAO(mockDAO);
        servlet.setOtpStore(mockStore);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        stubForward(request);
        when(request.getParameter("identifier")).thenReturn(email);

        servlet.doPost(request, response);

        verify(request).setAttribute(eq("Error"), anyString());
        verify(mockStore, never()).generateAndStore(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "abcde123", "+", "1234567890123456", "9912-3456"})
    public void testInvalidPhoneFormatRejectsOtpRequest(String phone) throws Exception {
        UserDAO mockDAO = mock(UserDAO.class);
        OtpStore mockStore = mock(OtpStore.class);
        ForgotServletWrapper servlet = new ForgotServletWrapper();
        servlet.setUserDAO(mockDAO);
        servlet.setOtpStore(mockStore);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        stubForward(request);
        when(request.getParameter("identifier")).thenReturn(phone);

        servlet.doPost(request, response);

        verify(request).setAttribute(eq("Error"), anyString());
        verify(mockStore, never()).generateAndStore(any());
    }

    // ForgotPasswordServlet 

    @Test
    public void testValidEmailGeneratesOtpAndSetsAttributes() throws Exception {
        User existingUser = new User("john", "john@email.com", "hash", Role.BUYER);
        existingUser.setId(1);

        UserDAO mockDAO = mock(UserDAO.class);
        when(mockDAO.getUserByEmail("john@email.com")).thenReturn(existingUser);

        OtpStore mockStore = mock(OtpStore.class);
        when(mockStore.generateAndStore("john@email.com")).thenReturn("482031");

        ForgotServletWrapper servlet = new ForgotServletWrapper();
        servlet.setUserDAO(mockDAO);
        servlet.setOtpStore(mockStore);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        stubForward(request);
        when(request.getParameter("identifier")).thenReturn("john@email.com");

        servlet.doPost(request, response);

        verify(mockStore).generateAndStore("john@email.com");
        verify(request).setAttribute(eq("simulatedOtp"), eq("482031"));
        verify(request).setAttribute(eq("OtpSent"), anyString());
        verify(request).setAttribute(eq("resetIdentifier"), eq("john@email.com"));
    }

    @Test
    public void testNonExistentAccountShowsGenericMessageWithoutOtp() throws Exception {
        UserDAO mockDAO = mock(UserDAO.class);
        when(mockDAO.getUserByEmail(anyString())).thenReturn(null);

        OtpStore mockStore = mock(OtpStore.class);
        ForgotServletWrapper servlet = new ForgotServletWrapper();
        servlet.setUserDAO(mockDAO);
        servlet.setOtpStore(mockStore);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        stubForward(request);
        when(request.getParameter("identifier")).thenReturn("ghost@email.com");

        servlet.doPost(request, response);

        // Generic message to prevent account enumeration — no OTP must be generated
        verify(request).setAttribute(eq("OtpSent"), anyString());
        verify(request, never()).setAttribute(eq("simulatedOtp"), any());
        verify(mockStore, never()).generateAndStore(any());
    }

    // ResetPasswordServlet — OTP and password edge cases

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    public void testEmptyOtpRejectsReset(String otp) throws Exception {
        UserDAO mockDAO = mock(UserDAO.class);
        OtpStore mockStore = mock(OtpStore.class);
        ResetServletWrapper servlet = new ResetServletWrapper();
        servlet.setUserDAO(mockDAO);
        servlet.setOtpStore(mockStore);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        stubForward(request);
        when(request.getParameter("identifier")).thenReturn("john@email.com");
        when(request.getParameter("otp")).thenReturn(otp);
        when(request.getParameter("newPassword")).thenReturn("Password1!");
        when(request.getParameter("confirmNewPassword")).thenReturn("Password1!");

        servlet.doPost(request, response);

        verify(request).setAttribute(eq("Error"), eq("OTP is required."));
        verify(mockStore, never()).verify(any(), any());
        verify(mockDAO, never()).updatePassword(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Password", "Password1", "Password@", "password1!", "PASSWORD1!"})
    public void testWeakNewPasswordRejectsReset(String weakPassword) throws Exception {
        UserDAO mockDAO = mock(UserDAO.class);
        OtpStore mockStore = mock(OtpStore.class);
        ResetServletWrapper servlet = new ResetServletWrapper();
        servlet.setUserDAO(mockDAO);
        servlet.setOtpStore(mockStore);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        stubForward(request);
        when(request.getParameter("identifier")).thenReturn("john@email.com");
        when(request.getParameter("otp")).thenReturn("123456");
        when(request.getParameter("newPassword")).thenReturn(weakPassword);
        when(request.getParameter("confirmNewPassword")).thenReturn(weakPassword);

        servlet.doPost(request, response);

        // Password check fires before OTP verification
        verify(request).setAttribute(eq("Error"), contains("Password"));
        verify(mockStore, never()).verify(any(), any());
        verify(mockDAO, never()).updatePassword(any(), any());
    }

    @Test
    public void testInvalidOtpRejectsReset() throws Exception {
        UserDAO mockDAO = mock(UserDAO.class);
        OtpStore mockStore = mock(OtpStore.class);
        when(mockStore.verify("john@email.com", "000000")).thenReturn(false);

        ResetServletWrapper servlet = new ResetServletWrapper();
        servlet.setUserDAO(mockDAO);
        servlet.setOtpStore(mockStore);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        stubForward(request);
        when(request.getParameter("identifier")).thenReturn("john@email.com");
        when(request.getParameter("otp")).thenReturn("000000");
        when(request.getParameter("newPassword")).thenReturn("Password1!");
        when(request.getParameter("confirmNewPassword")).thenReturn("Password1!");

        servlet.doPost(request, response);

        verify(request).setAttribute(eq("Error"), eq("Invalid or expired OTP."));
        verify(mockDAO, never()).updatePassword(any(), any());
    }

    @Test
    public void testExpiredOtpRejectsReset() throws Exception {
        UserDAO mockDAO = mock(UserDAO.class);
        OtpStore mockStore = mock(OtpStore.class);
        // OtpStore.verify returns false for expired entries — same code path as invalid
        when(mockStore.verify("john@email.com", "123456")).thenReturn(false);

        ResetServletWrapper servlet = new ResetServletWrapper();
        servlet.setUserDAO(mockDAO);
        servlet.setOtpStore(mockStore);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        stubForward(request);
        when(request.getParameter("identifier")).thenReturn("john@email.com");
        when(request.getParameter("otp")).thenReturn("123456");
        when(request.getParameter("newPassword")).thenReturn("Password1!");
        when(request.getParameter("confirmNewPassword")).thenReturn("Password1!");

        servlet.doPost(request, response);

        verify(request).setAttribute(eq("Error"), eq("Invalid or expired OTP."));
        verify(mockDAO, never()).updatePassword(any(), any());
    }

    // SecurityUtil SHA-256 hashing integration

    @Test
    public void testSuccessfulResetHashesWithSecurityUtil() throws Exception {
        UserDAO mockDAO = mock(UserDAO.class);
        when(mockDAO.updatePassword(eq("john@email.com"), anyString())).thenReturn(true);

        OtpStore mockStore = mock(OtpStore.class);
        when(mockStore.verify("john@email.com", "482031")).thenReturn(true);

        ResetServletWrapper servlet = new ResetServletWrapper();
        servlet.setUserDAO(mockDAO);
        servlet.setOtpStore(mockStore);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        stubForward(request);
        when(request.getParameter("identifier")).thenReturn("john@email.com");
        when(request.getParameter("otp")).thenReturn("482031");
        when(request.getParameter("newPassword")).thenReturn("NewPassword1!");
        when(request.getParameter("confirmNewPassword")).thenReturn("NewPassword1!");

        servlet.doPost(request, response);

        // Capture the hash passed to updatePassword
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockDAO).updatePassword(eq("john@email.com"), hashCaptor.capture());
        String storedHash = hashCaptor.getValue();

        // Assert SecurityUtil salted SHA-256 format: "1$<saltBase64>$<hashBase64>"
        assertTrue(storedHash.startsWith("1$"),
                "Reset password must use SecurityUtil SHA-256 format");
        assertEquals(3, storedHash.split("\\$", 3).length,
                "Hash must contain 3 dollar-sign-delimited parts");

        // Round-trip: SecurityUtil must be able to verify the stored hash
        assertTrue(SecurityUtil.verifyPassword("NewPassword1!", storedHash),
                "SecurityUtil.verifyPassword must confirm the stored hash");

        // OTP must be invalidated after successful reset (anti-replay)
        verify(mockStore).invalidate("john@email.com");

        // Success attribute must be set
        verify(request).setAttribute(eq("Reset"), eq("Password reset successfully!"));
    }
}
