import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.auction.dao.UserDAO;
import com.auction.model.Role;
import com.auction.model.User;
import com.auction.servlet.ChangePasswordServlet;
import com.auction.util.SecurityUtil;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;

@DisplayName("ChangePasswordServlet (SCRUM-12)")
public class TestChangePasswordServlet extends Mockito {

    private static class ChangePasswordServletWrapper extends ChangePasswordServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            super.doPost(req, resp);
        }
    }

    private ChangePasswordServletWrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private HttpSession session;
    private UserDAO mockDao;
    private RequestDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        servlet = new ChangePasswordServletWrapper();
        mockDao = mock(UserDAO.class);
        servlet.setUserDAO(mockDao);
        req = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
        session = mock(HttpSession.class);
        dispatcher = mock(RequestDispatcher.class);
        when(req.getSession(false)).thenReturn(session);
        when(req.getContextPath()).thenReturn("");
        when(req.getRequestDispatcher(ChangePasswordServlet.VIEW_FORM)).thenReturn(dispatcher);
    }

    private User userWithPassword(int id, String email, String plainPassword) {
        User u = new User("u", email, SecurityUtil.hashPassword(plainPassword), Role.BUYER);
        u.setId(id);
        return u;
    }

    @Test
    @DisplayName("SCRUM-196: wrong current password does not call updatePassword")
    void wrongCurrentPassword_rejected() throws Exception {
        User u = userWithPassword(1, "john@test.com", "Correct1!");
        when(session.getAttribute("userId")).thenReturn(1);
        when(session.getAttribute("sessionEmail")).thenReturn("john@test.com");
        when(mockDao.getUserByEmail("john@test.com")).thenReturn(u);
        when(req.getParameter("currentPassword")).thenReturn("Wrong1!");
        when(req.getParameter("newPassword")).thenReturn("NewPassword1!");
        when(req.getParameter("confirmPassword")).thenReturn("NewPassword1!");

        servlet.doPost(req, resp);

        verify(mockDao, never()).updatePassword(anyString(), anyString());
        verify(dispatcher).forward(req, resp);
        verify(req).setAttribute(eq("error"), eq("Current password is incorrect."));
    }

    @Test
    @DisplayName("SCRUM-195: successful change stores salted SHA-256 from SecurityUtil")
    void success_storesSecurityUtilHash() throws Exception {
        User u = userWithPassword(5, "a@test.com", "OldPassword1!");
        when(session.getAttribute("userId")).thenReturn(5);
        when(session.getAttribute("sessionEmail")).thenReturn("a@test.com");
        when(mockDao.getUserByEmail("a@test.com")).thenReturn(u);
        when(req.getParameter("currentPassword")).thenReturn("OldPassword1!");
        when(req.getParameter("newPassword")).thenReturn("NewPassword1!");
        when(req.getParameter("confirmPassword")).thenReturn("NewPassword1!");
        when(mockDao.updatePassword(eq("a@test.com"), anyString())).thenReturn(true);

        servlet.doPost(req, resp);

        ArgumentCaptor<String> hashCap = ArgumentCaptor.forClass(String.class);
        verify(mockDao).updatePassword(eq("a@test.com"), hashCap.capture());
        String stored = hashCap.getValue();
        assertTrue(stored.startsWith("1$"));
        assertEquals(3, stored.split("\\$", 3).length);
        assertTrue(SecurityUtil.verifyPassword("NewPassword1!", stored));

        verify(session).invalidate();
        verify(resp).sendRedirect("/login?passwordChanged=1");
    }

    @Test
    @DisplayName("SCRUM-197: session invalidated after successful password change")
    void success_invalidatesSession() throws Exception {
        User u = userWithPassword(1, "x@test.com", "Correct1!");
        when(session.getAttribute("userId")).thenReturn(1);
        when(session.getAttribute("sessionEmail")).thenReturn("x@test.com");
        when(mockDao.getUserByEmail("x@test.com")).thenReturn(u);
        when(req.getParameter("currentPassword")).thenReturn("Correct1!");
        when(req.getParameter("newPassword")).thenReturn("BrandNew1!");
        when(req.getParameter("confirmPassword")).thenReturn("BrandNew1!");
        when(mockDao.updatePassword(anyString(), anyString())).thenReturn(true);

        servlet.doPost(req, resp);

        verify(session).invalidate();
    }

    @Test
    @DisplayName("Weak new password rejected")
    void weakNewPassword_forwardError() throws Exception {
        User u = userWithPassword(1, "x@test.com", "Correct1!");
        when(session.getAttribute("userId")).thenReturn(1);
        when(session.getAttribute("sessionEmail")).thenReturn("x@test.com");
        when(mockDao.getUserByEmail("x@test.com")).thenReturn(u);
        when(req.getParameter("currentPassword")).thenReturn("Correct1!");
        when(req.getParameter("newPassword")).thenReturn("short");
        when(req.getParameter("confirmPassword")).thenReturn("short");

        servlet.doPost(req, resp);

        verify(mockDao, never()).updatePassword(anyString(), anyString());
        verify(dispatcher).forward(req, resp);
    }

    @Test
    @DisplayName("Confirm mismatch rejected")
    void confirmMismatch_forwardError() throws Exception {
        User u = userWithPassword(1, "x@test.com", "Correct1!");
        when(session.getAttribute("userId")).thenReturn(1);
        when(session.getAttribute("sessionEmail")).thenReturn("x@test.com");
        when(mockDao.getUserByEmail("x@test.com")).thenReturn(u);
        when(req.getParameter("currentPassword")).thenReturn("Correct1!");
        when(req.getParameter("newPassword")).thenReturn("NewPassword1!");
        when(req.getParameter("confirmPassword")).thenReturn("Other1!");

        servlet.doPost(req, resp);

        verify(mockDao, never()).updatePassword(anyString(), anyString());
        verify(req).setAttribute("error", "New password and confirmation do not match.");
    }

    @Test
    void noSession_redirectsToLogin() throws Exception {
        when(req.getSession(false)).thenReturn(null);

        servlet.doPost(req, resp);

        verify(resp).sendRedirect("/login");
        verify(mockDao, never()).getUserByEmail(anyString());
    }
}
