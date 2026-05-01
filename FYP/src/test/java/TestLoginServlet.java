import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.auction.dao.UserDAO;
import com.auction.model.Role;
import com.auction.model.User;
import com.auction.servlet.LoginServlet;
import com.auction.servlet.RegisterServlet;
import com.auction.util.SecurityUtil;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;

public class TestLoginServlet extends Mockito {

    private static void stubLoginForward(HttpServletRequest request) {
        RequestDispatcher dispatcher = mock(RequestDispatcher.class);
        when(request.getRequestDispatcher(anyString())).thenReturn(dispatcher);
    }

    private static class LoginServletWrapper extends LoginServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            super.doPost(req, resp);
        }
    }

    private static class RegisterServletWrapper extends RegisterServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            super.doPost(req, resp);
        }
    }

    // Credential edge cases (empty fields + invalid email format) 

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "notanemail", "@nodomain.com", "user@.com", "nodomain@"})
    public void testInvalidEmailFormatRejectsLogin(String email) throws Exception {
        UserDAO mockDAO = mock(UserDAO.class);
        LoginServletWrapper servlet = new LoginServletWrapper();
        servlet.setUserDAO(mockDAO);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        stubLoginForward(request);

        when(request.getParameter("email")).thenReturn(email);
        when(request.getParameter("password")).thenReturn("Password1!");

        servlet.doPost(request, response);

        verify(request).setAttribute(eq("Error"), anyString());
        verify(mockDAO, never()).getUserByEmail(any());
    }

    @Test
    public void testEmptyPasswordRejectsLogin() throws Exception {
        UserDAO mockDAO = mock(UserDAO.class);
        LoginServletWrapper servlet = new LoginServletWrapper();
        servlet.setUserDAO(mockDAO);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        stubLoginForward(request);

        when(request.getParameter("email")).thenReturn("user@email.com");
        when(request.getParameter("password")).thenReturn("");

        servlet.doPost(request, response);

        verify(request).setAttribute(eq("Error"), eq("Password is required."));
        verify(mockDAO, never()).getUserByEmail(any());
    }

    // Authentication logic
    @Test
    public void testNonExistentUserRejectsLogin() throws Exception {
        UserDAO mockDAO = mock(UserDAO.class);
        when(mockDAO.getUserByEmail("unknown@email.com")).thenReturn(null);

        LoginServletWrapper servlet = new LoginServletWrapper();
        servlet.setUserDAO(mockDAO);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        stubLoginForward(request);

        when(request.getParameter("email")).thenReturn("unknown@email.com");
        when(request.getParameter("password")).thenReturn("Password1!");

        servlet.doPost(request, response);

        verify(request).setAttribute(eq("Error"), eq("Invalid email or password."));
    }

    @Test
    public void testWrongPasswordRejectsLogin() throws Exception {
        String storedHash = SecurityUtil.hashPassword("CorrectPassword1!");
        User existingUser = new User("john", "john@email.com", storedHash, Role.BUYER);

        UserDAO mockDAO = mock(UserDAO.class);
        when(mockDAO.getUserByEmail("john@email.com")).thenReturn(existingUser);

        LoginServletWrapper servlet = new LoginServletWrapper();
        servlet.setUserDAO(mockDAO);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        stubLoginForward(request);

        when(request.getParameter("email")).thenReturn("john@email.com");
        when(request.getParameter("password")).thenReturn("WrongPassword1!");

        servlet.doPost(request, response);

        verify(request).setAttribute(eq("Error"), eq("Invalid email or password."));
    }

    @Test
    public void testSuccessfulLoginSetsSession() throws Exception {
        String storedHash = SecurityUtil.hashPassword("Password1!");
        User existingUser = new User("john", "john@email.com", storedHash, Role.BUYER);
        existingUser.setId(42);

        UserDAO mockDAO = mock(UserDAO.class);
        when(mockDAO.getUserByEmail("john@email.com")).thenReturn(existingUser);

        LoginServletWrapper servlet = new LoginServletWrapper();
        servlet.setUserDAO(mockDAO);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        HttpSession session = mock(HttpSession.class);
        stubLoginForward(request);

        when(request.getContextPath()).thenReturn("");
        when(request.getParameter("email")).thenReturn("john@email.com");
        when(request.getParameter("password")).thenReturn("Password1!");
        when(request.getSession(true)).thenReturn(session);

        servlet.doPost(request, response);

        verify(request).setAttribute(eq("Login"), eq("Login successful!"));
        verify(response).sendRedirect("/protected/account");
        verify(session).setAttribute(eq("userId"), eq(42));
        verify(session).setAttribute(eq("userRole"), eq("BUYER"));
        verify(session).setAttribute(eq("maskedEmail"), eq(SecurityUtil.maskEmail("john@email.com")));
        verify(session).setAttribute(eq("maskedUsername"), eq(SecurityUtil.maskUsername("john")));
    }

    // Registration flow: SecurityUtil SHA-256 hashing integration

    @Test
    public void testRegistrationHashesPasswordWithSecurityUtil() throws Exception {
        UserDAO mockDAO = mock(UserDAO.class);
        when(mockDAO.checkUser(anyString())).thenReturn(false);
        when(mockDAO.checkEmail(anyString())).thenReturn(false);
        when(mockDAO.insertUser(any(User.class))).thenReturn(true);

        RegisterServletWrapper registerServlet = new RegisterServletWrapper();
        registerServlet.setUserDAO(mockDAO);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        stubLoginForward(request);

        when(request.getParameter("username")).thenReturn("newuser");
        when(request.getParameter("email")).thenReturn("newuser@email.com");
        when(request.getParameter("password")).thenReturn("Password1!");
        when(request.getParameter("role")).thenReturn("buyer");

        registerServlet.doPost(request, response);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(mockDAO).insertUser(userCaptor.capture());
        String storedPassword = userCaptor.getValue().getPassword();

        // Assert hash follows SecurityUtil's salted SHA-256 format: "1$<saltBase64>$<hashBase64>"
        assertTrue(storedPassword.startsWith("1$"), "Hash must use SecurityUtil SHA-256 format");
        assertEquals(3, storedPassword.split("\\$", 3).length, "Hash must have 3 dollar-sign-delimited parts");

        // Assert SecurityUtil can verify the stored hash (round-trip)
        assertTrue(SecurityUtil.verifyPassword("Password1!", storedPassword),
                "SecurityUtil.verifyPassword must confirm the stored hash");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Password", "Password1", "Password@", "password1!", "PASSWORD1!"})
    public void testRegistrationRejectsWeakPasswords(String weakPassword) throws Exception {
        UserDAO mockDAO = mock(UserDAO.class);
        RegisterServletWrapper registerServlet = new RegisterServletWrapper();
        registerServlet.setUserDAO(mockDAO);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        stubLoginForward(request);

        when(request.getParameter("username")).thenReturn("newuser");
        when(request.getParameter("email")).thenReturn("newuser@email.com");
        when(request.getParameter("password")).thenReturn(weakPassword);
        when(request.getParameter("role")).thenReturn("buyer");

        registerServlet.doPost(request, response);

        verify(request).setAttribute(eq("Error"), contains("Password"));
        verify(mockDAO, never()).insertUser(any());
    }
}
