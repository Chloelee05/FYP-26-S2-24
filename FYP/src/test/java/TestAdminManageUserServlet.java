import com.auction.dao.UserDAO;
import com.auction.model.Role;
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

    @Test
    @DisplayName("Action null and userid null")
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
    @DisplayName("Test Suspend")
    public void TestSuspend() throws Exception {
        when(mockDAO.updateStatus(1, 2)).thenReturn(true);
        User target = new User("u", "u@e.com", "x", Role.BUYER);
        target.setId(1);
        when(mockDAO.getUserById(1)).thenReturn(target);
        when(mockRequest.getParameter("action")).thenReturn("SUSPEND");
        when(mockRequest.getParameter("userid")).thenReturn("1");
        when(mockSession.getAttribute("userId")).thenReturn(999);

        mockServlet.doPost(mockRequest, mockResponse);

        verify(mockDAO).updateStatus(1, 2);
        verify(mockSession).setAttribute(eq("adminFlash"), eq("Account successfully suspended!"));
        verify(mockResponse).sendRedirect("/admin/users");
    }

    @Test
    @DisplayName("Test Active")
    public void TestActive() throws Exception {
        when(mockDAO.updateStatus(1, 1)).thenReturn(true);
        User target = new User("u", "u@e.com", "x", Role.BUYER);
        target.setId(1);
        target.setStatusId(2);
        when(mockDAO.getUserById(1)).thenReturn(target);
        when(mockRequest.getParameter("action")).thenReturn("ACTIVE");
        when(mockRequest.getParameter("userid")).thenReturn("1");
        when(mockSession.getAttribute("userId")).thenReturn(999);

        mockServlet.doPost(mockRequest, mockResponse);

        verify(mockDAO).updateStatus(1, 1);
        verify(mockSession).setAttribute(eq("adminFlash"), eq("Account successfully unsuspended!"));
        verify(mockResponse).sendRedirect("/admin/users");
    }
}
