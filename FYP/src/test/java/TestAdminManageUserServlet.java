import com.auction.dao.UserDAO;
import com.auction.model.Role;
import com.auction.model.Status;
import com.auction.model.User;
import com.auction.servlet.admin.AdminManageUserServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.*;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AdminManageUserServlet}.
 *
 * <p><b>RBAC enforcement (SCRUM-214):</b> The {@code /admin/users/action} endpoint is
 * guarded by {@code AdminFilter} ({@code @WebFilter("/admin/*")}), which blocks any
 * request that is unauthenticated or does not carry the {@code ADMIN} role — the servlet
 * itself is never reached in those cases. RBAC coverage for Buyer, Seller, and
 * unauthenticated callers lives in {@link TestAdminFilter}.
 */
public class TestAdminManageUserServlet extends Mockito {

    private static class AdminManageUserServletWrapper extends AdminManageUserServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            super.doPost(req, resp);
        }
    }

    private UserDAO mockDAO;
    private AdminManageUserServletWrapper mockServlet;
    private HttpServletRequest mockRequest;
    private HttpServletResponse mockResponse;
    private HttpSession mockSession;

    @BeforeEach
    public void setUp() {
        mockDAO = mock(UserDAO.class);
        mockServlet = new AdminManageUserServletWrapper();
        mockServlet.setUserDAO(mockDAO);
        mockRequest = mock(HttpServletRequest.class);
        mockResponse = mock(HttpServletResponse.class);
        mockSession = mock(HttpSession.class);
        when(mockRequest.getSession(false)).thenReturn(mockSession);
        when(mockRequest.getContextPath()).thenReturn("");
    }

    // -------------------------------------------------------------------------
    // Input validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Blank action or null userid returns 400")
    public void TestNull() throws Exception {
        when(mockRequest.getParameter("action")).thenReturn("");
        when(mockRequest.getParameter("userid")).thenReturn("1");
        mockServlet.doPost(mockRequest, mockResponse);
        verify(mockResponse).sendError(HttpServletResponse.SC_BAD_REQUEST);

        reset(mockResponse);

        when(mockRequest.getParameter("action")).thenReturn("Suspend");
        when(mockRequest.getParameter("userid")).thenReturn(null);
        mockServlet.doPost(mockRequest, mockResponse);
        verify(mockResponse).sendError(HttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    @DisplayName("Non-numeric userid returns 400 (SCRUM-279 IDOR guard)")
    public void TestNonNumericUserId() throws Exception {
        when(mockRequest.getParameter("action")).thenReturn("ACTIVE");
        when(mockRequest.getParameter("userid")).thenReturn("abc");
        when(mockSession.getAttribute("userId")).thenReturn(999);
        mockServlet.doPost(mockRequest, mockResponse);
        verify(mockResponse).sendError(HttpServletResponse.SC_BAD_REQUEST);
        verify(mockDAO, never()).getUserById(anyInt());
    }

    // -------------------------------------------------------------------------
    // Ban (suspend)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Ban an active user succeeds (SCRUM-211)")
    public void TestSuspend() throws Exception {
        when(mockDAO.updateStatus(1, Status.SUSPENDED.getId())).thenReturn(true);
        User target = new User("u", "u@e.com", "x", Role.BUYER);
        target.setId(1);
        // statusId defaults to ACTIVE (1) via User field initialiser
        when(mockDAO.getUserById(1)).thenReturn(target);
        when(mockRequest.getParameter("action")).thenReturn("SUSPEND");
        when(mockRequest.getParameter("userid")).thenReturn("1");
        when(mockSession.getAttribute("userId")).thenReturn(999);

        mockServlet.doPost(mockRequest, mockResponse);

        verify(mockDAO).updateStatus(1, Status.SUSPENDED.getId());
        verify(mockSession).setAttribute(eq("adminFlash"), eq("Account successfully banned."));
        verify(mockResponse).sendRedirect("/admin/users");
    }

    @Test
    @DisplayName("Banning an already-banned user returns error (SCRUM-212)")
    public void TestSuspendAlreadySuspended() throws Exception {
        User target = new User("u", "u@e.com", "x", Role.BUYER);
        target.setId(1);
        target.setStatusId(Status.SUSPENDED.getId());
        when(mockDAO.getUserById(1)).thenReturn(target);
        when(mockRequest.getParameter("action")).thenReturn("SUSPEND");
        when(mockRequest.getParameter("userid")).thenReturn("1");
        when(mockSession.getAttribute("userId")).thenReturn(999);

        mockServlet.doPost(mockRequest, mockResponse);

        verify(mockDAO, never()).updateStatus(anyInt(), anyInt());
        verify(mockSession).setAttribute(eq("adminFlashError"), eq("User account is already banned."));
        verify(mockResponse).sendRedirect("/admin/users");
    }

    // -------------------------------------------------------------------------
    // Unban (SCRUM-21 core)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Unban a suspended user via action=ACTIVE succeeds (SCRUM-211)")
    public void TestActive() throws Exception {
        when(mockDAO.updateStatus(1, Status.ACTIVE.getId())).thenReturn(true);
        User target = new User("u", "u@e.com", "x", Role.BUYER);
        target.setId(1);
        target.setStatusId(Status.SUSPENDED.getId());
        when(mockDAO.getUserById(1)).thenReturn(target);
        when(mockRequest.getParameter("action")).thenReturn("ACTIVE");
        when(mockRequest.getParameter("userid")).thenReturn("1");
        when(mockSession.getAttribute("userId")).thenReturn(999);

        mockServlet.doPost(mockRequest, mockResponse);

        verify(mockDAO).updateStatus(1, Status.ACTIVE.getId());
        verify(mockSession).setAttribute(eq("adminFlash"), eq("Account successfully unbanned."));
        verify(mockResponse).sendRedirect("/admin/users");
    }

    @Test
    @DisplayName("Unban via action=UNBAN alias also succeeds (SCRUM-211)")
    public void TestUnbanAlias() throws Exception {
        when(mockDAO.updateStatus(2, Status.ACTIVE.getId())).thenReturn(true);
        User target = new User("seller1", "seller@e.com", "x", Role.SELLER);
        target.setId(2);
        target.setStatusId(Status.SUSPENDED.getId());
        when(mockDAO.getUserById(2)).thenReturn(target);
        when(mockRequest.getParameter("action")).thenReturn("UNBAN");
        when(mockRequest.getParameter("userid")).thenReturn("2");
        when(mockSession.getAttribute("userId")).thenReturn(999);

        mockServlet.doPost(mockRequest, mockResponse);

        verify(mockDAO).updateStatus(2, Status.ACTIVE.getId());
        verify(mockSession).setAttribute(eq("adminFlash"), eq("Account successfully unbanned."));
        verify(mockResponse).sendRedirect("/admin/users");
    }

    @Test
    @DisplayName("Unbanning an already-active user returns error (SCRUM-212)")
    public void TestUnbanAlreadyActive() throws Exception {
        User target = new User("u", "u@e.com", "x", Role.BUYER);
        target.setId(1);
        // statusId defaults to ACTIVE (1) — not currently banned
        when(mockDAO.getUserById(1)).thenReturn(target);
        when(mockRequest.getParameter("action")).thenReturn("ACTIVE");
        when(mockRequest.getParameter("userid")).thenReturn("1");
        when(mockSession.getAttribute("userId")).thenReturn(999);

        mockServlet.doPost(mockRequest, mockResponse);

        verify(mockDAO, never()).updateStatus(anyInt(), anyInt());
        verify(mockSession).setAttribute(eq("adminFlashError"), eq("User account is not currently banned."));
        verify(mockResponse).sendRedirect("/admin/users");
    }

    @Test
    @DisplayName("Unbanning a deleted account returns error (SCRUM-212)")
    public void TestUnbanDeletedAccount() throws Exception {
        User target = new User("u", "u@e.com", "x", Role.BUYER);
        target.setId(1);
        target.setStatusId(Status.DELETED.getId());
        when(mockDAO.getUserById(1)).thenReturn(target);
        when(mockRequest.getParameter("action")).thenReturn("ACTIVE");
        when(mockRequest.getParameter("userid")).thenReturn("1");
        when(mockSession.getAttribute("userId")).thenReturn(999);

        mockServlet.doPost(mockRequest, mockResponse);

        verify(mockDAO, never()).updateStatus(anyInt(), anyInt());
        verify(mockSession).setAttribute(eq("adminFlashError"), eq("User account is not currently banned."));
        verify(mockResponse).sendRedirect("/admin/users");
    }

    // -------------------------------------------------------------------------
    // Guard rails
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Attempting to unban an ADMIN account is rejected (SCRUM-279)")
    public void TestUnbanAdminTarget() throws Exception {
        User target = new User("adminUser", "admin@e.com", "x", Role.ADMIN);
        target.setId(5);
        target.setStatusId(Status.SUSPENDED.getId());
        when(mockDAO.getUserById(5)).thenReturn(target);
        when(mockRequest.getParameter("action")).thenReturn("ACTIVE");
        when(mockRequest.getParameter("userid")).thenReturn("5");
        when(mockSession.getAttribute("userId")).thenReturn(999);

        mockServlet.doPost(mockRequest, mockResponse);

        verify(mockDAO, never()).updateStatus(anyInt(), anyInt());
        verify(mockSession).setAttribute(eq("adminFlashError"),
                eq("Admin accounts cannot be banned or unbanned here."));
        verify(mockResponse).sendRedirect("/admin/users");
    }

    @Test
    @DisplayName("Admin cannot change their own account status (self-action guard)")
    public void TestSelfAction() throws Exception {
        when(mockRequest.getParameter("action")).thenReturn("ACTIVE");
        when(mockRequest.getParameter("userid")).thenReturn("1");
        when(mockSession.getAttribute("userId")).thenReturn(1);

        mockServlet.doPost(mockRequest, mockResponse);

        verify(mockDAO, never()).getUserById(anyInt());
        verify(mockSession).setAttribute(eq("adminFlashError"),
                eq("You cannot change your own account status."));
        verify(mockResponse).sendRedirect("/admin/users");
    }

    @Test
    @DisplayName("Unban with unknown user id returns error")
    public void TestUnbanUnknownUser() throws Exception {
        when(mockDAO.getUserById(999)).thenReturn(null);
        when(mockRequest.getParameter("action")).thenReturn("ACTIVE");
        when(mockRequest.getParameter("userid")).thenReturn("999");
        when(mockSession.getAttribute("userId")).thenReturn(1);

        mockServlet.doPost(mockRequest, mockResponse);

        verify(mockDAO, never()).updateStatus(anyInt(), anyInt());
        verify(mockSession).setAttribute(eq("adminFlashError"), eq("User not found."));
        verify(mockResponse).sendRedirect("/admin/users");
    }
}
