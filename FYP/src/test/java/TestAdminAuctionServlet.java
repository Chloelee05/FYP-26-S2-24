import com.auction.dao.AuctionDAO;
import com.auction.model.User;
import com.auction.servlet.admin.AdminAuctionServlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.mockito.Mockito.*;

@DisplayName("Test Admin Auction Servlet")
public class TestAdminAuctionServlet {

    private static class AdminAuctionServletWrapper extends AdminAuctionServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            super.doPost(req, resp);
        }
    }

    private AdminAuctionServletWrapper servlet;
    private HttpServletRequest mockRequest;
    private HttpServletResponse mockResponse;
    private HttpSession mockSession;
    private AuctionDAO mockAuctionDAO;
    private User mockUser;

    @BeforeEach
    public void setUp() throws Exception {
        servlet = new AdminAuctionServletWrapper();
        mockAuctionDAO = mock(AuctionDAO.class);
        servlet.setAuctionDAO(mockAuctionDAO);

        mockRequest = mock(HttpServletRequest.class);
        mockResponse = mock(HttpServletResponse.class);
        mockSession = mock(HttpSession.class);
        mockUser = mock(User.class);

        when(mockRequest.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute("user")).thenReturn(mockUser);

        when(mockRequest.getParameter("auction_status")).thenReturn("flagged");
        when(mockRequest.getParameter("auction_id")).thenReturn("1");

        ServletContext mockContext = mock(ServletContext.class);
        ServletConfig mockConfig = mock(ServletConfig.class);
        when(mockConfig.getServletContext()).thenReturn(mockContext);
        servlet.init(mockConfig);
    }

    @Nested
    @DisplayName("Test Authentication")
    class TestAuthentication {

        @Test
        @DisplayName("No session returns 401")
        public void testNoSession() throws Exception {
            when(mockRequest.getSession(false)).thenReturn(null);
            servlet.doPost(mockRequest, mockResponse);
            verify(mockResponse).sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }

        @Test
        @DisplayName("No user in session returns 401")
        public void testNoUser() throws Exception {
            when(mockSession.getAttribute("user")).thenReturn(null);
            servlet.doPost(mockRequest, mockResponse);
            verify(mockResponse).sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("Test Input Validation")
    class TestInputValidation {

        @Test
        @DisplayName("Null moderation status returns error")
        public void testNullModerationStatus() throws Exception {
            when(mockRequest.getParameter("auction_status")).thenReturn(null);
            servlet.doPost(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("Error"), eq("Invalid moderation status:"));
        }

        @Test
        @DisplayName("Blank moderation status returns error")
        public void testBlankModerationStatus() throws Exception {
            when(mockRequest.getParameter("auction_status")).thenReturn("  ");
            servlet.doPost(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("Error"), eq("Invalid moderation status:"));
        }

        @Test
        @DisplayName("Null auction ID returns error")
        public void testNullAuctionId() throws Exception {
            when(mockRequest.getParameter("auction_id")).thenReturn(null);
            servlet.doPost(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("Error"), eq("Invalid auction_id:"));
        }

        @Test
        @DisplayName("Blank auction ID returns error")
        public void testBlankAuctionId() throws Exception {
            when(mockRequest.getParameter("auction_id")).thenReturn("  ");
            servlet.doPost(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("Error"), eq("Invalid auction_id:"));
        }

        @Test
        @DisplayName("Non-numeric auction ID returns error")
        public void testNonNumericAuctionId() throws Exception {
            when(mockRequest.getParameter("auction_id")).thenReturn("abc");
            servlet.doPost(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("Error"), eq("Invalid auction id"));
        }
    }

    @Nested
    @DisplayName("Test Moderation Update")
    class TestModerationUpdate {

        @Test
        @DisplayName("Successful update")
        public void testSuccessfulUpdate() throws Exception {
            when(mockAuctionDAO.updateAuctionState(anyLong(), anyString())).thenReturn(true);
            servlet.doPost(mockRequest, mockResponse);
            verify(mockRequest, never()).setAttribute(eq("Error"), anyString());
        }

        @Test
        @DisplayName("Failed update sets error")
        public void testFailedUpdate() throws Exception {
            when(mockAuctionDAO.updateAuctionState(anyLong(), anyString())).thenReturn(false);
            servlet.doPost(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("Error"), eq("Error with database. Please try again"));
        }

        @Test
        @DisplayName("Database error sets error")
        public void testDatabaseError() throws Exception {
            when(mockAuctionDAO.updateAuctionState(anyLong(), anyString())).thenThrow(new Exception("DB error"));
            servlet.doPost(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("Error"), eq("Could not reach the database"));
        }

        @Test
        @DisplayName("Update to active state")
        public void testUpdateToActive() throws Exception {
            when(mockRequest.getParameter("auction_status")).thenReturn("active");
            when(mockAuctionDAO.updateAuctionState(anyLong(), eq("active"))).thenReturn(true);
            servlet.doPost(mockRequest, mockResponse);
            verify(mockAuctionDAO).updateAuctionState(1L, "active");
        }

        @Test
        @DisplayName("Update to removed state")
        public void testUpdateToRemoved() throws Exception {
            when(mockRequest.getParameter("auction_status")).thenReturn("removed");
            when(mockAuctionDAO.updateAuctionState(anyLong(), eq("removed"))).thenReturn(true);
            servlet.doPost(mockRequest, mockResponse);
            verify(mockAuctionDAO).updateAuctionState(1L, "removed");
        }

        @Test
        @DisplayName("Update to flagged state")
        public void testUpdateToFlagged() throws Exception {
            when(mockRequest.getParameter("auction_status")).thenReturn("flagged");
            when(mockAuctionDAO.updateAuctionState(anyLong(), eq("flagged"))).thenReturn(true);
            servlet.doPost(mockRequest, mockResponse);
            verify(mockAuctionDAO).updateAuctionState(1L, "flagged");
        }
    }
}
