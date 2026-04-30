import com.auction.dao.UserDAO;
import com.auction.servlet.admin.AdminManageUserServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.*;

public class TestAdminManageUserServlet extends Mockito {

    private static class AdminManageUserServletWrapper extends AdminManageUserServlet{
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            super.doPost(req, resp);
        }
    }

    private UserDAO mockDAO;
    private AdminManageUserServletWrapper mockServlet;
    private HttpServletRequest mockRequest;
    private HttpServletResponse mockResponse;

    @BeforeEach
    public void setUp()
    {
        mockDAO = mock(UserDAO.class);
        mockServlet = new AdminManageUserServletWrapper();
        mockServlet.setUserDAO(mockDAO);
        mockRequest = mock(HttpServletRequest.class);
        mockResponse = mock(HttpServletResponse.class);
    }

    @Test
    @DisplayName("Action null and userid null")
    public void TestNull() throws Exception{
        when(mockRequest.getParameter("action")).thenReturn("");
        when(mockRequest.getParameter("userid")).thenReturn("1");
        mockServlet.doPost(mockRequest,mockResponse);
        verify(mockResponse).sendError(HttpServletResponse.SC_BAD_REQUEST);

        reset(mockResponse);

        when(mockRequest.getParameter("action")).thenReturn("Suspend");
        when(mockRequest.getParameter("userid")).thenReturn(null);
        mockServlet.doPost(mockRequest,mockResponse);
        verify(mockResponse).sendError(HttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    @DisplayName("Test Suspend")
    public void TestSuspend() throws Exception{
        when(mockDAO.updateStatus(1, 2)).thenReturn(true);
        
        when(mockRequest.getParameter("action")).thenReturn("SUSPEND");
        when(mockRequest.getParameter("userid")).thenReturn("1");
        mockServlet.doPost(mockRequest,mockResponse);
        verify(mockRequest).setAttribute(eq("Success"), eq("Account successfully suspended!"));
    }

    @Test
    @DisplayName("Test Active")
    public void TestActive() throws Exception{
        when(mockDAO.updateStatus(1, 1)).thenReturn(true);

        when(mockRequest.getParameter("action")).thenReturn("ACTIVE");
        when(mockRequest.getParameter("userid")).thenReturn("1");
        mockServlet.doPost(mockRequest,mockResponse);
        verify(mockRequest).setAttribute(eq("Success"), eq("Account successfully unsuspended!"));
    }
}
