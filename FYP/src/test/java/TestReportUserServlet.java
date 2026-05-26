import com.auction.dao.ReportDAO;
import com.auction.model.AccountReport;
import com.auction.model.User;
import com.auction.servlet.ReportUserServlet;
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

@DisplayName("Test ReportUserServlet")
public class TestReportUserServlet {

    private static class ReportUserServletWrapper extends ReportUserServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            super.doPost(req, resp);
        }
    }

    private ReportUserServletWrapper servlet;
    private HttpServletRequest mockRequest;
    private HttpServletResponse mockResponse;
    private HttpSession mockSession;
    private ReportDAO mockReportDAO;
    private User mockUser;

    @BeforeEach
    public void setUp() throws Exception {
        servlet = new ReportUserServletWrapper();
        mockReportDAO = mock(ReportDAO.class);
        servlet.setDAO(mockReportDAO);

        mockRequest = mock(HttpServletRequest.class);
        mockResponse = mock(HttpServletResponse.class);
        mockSession = mock(HttpSession.class);
        mockUser = mock(User.class);

        when(mockRequest.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute("user")).thenReturn(mockUser);
        when(mockUser.getId()).thenReturn(1);

        when(mockRequest.getParameter("target_id")).thenReturn("2");
        when(mockRequest.getParameter("reason")).thenReturn("Spam");
        when(mockRequest.getParameter("comment")).thenReturn("Test comment");
    }

    @Test
    @DisplayName("No session")
    public void testNoSession() throws Exception {
        when(mockRequest.getSession(false)).thenReturn(null);
        servlet.doPost(mockRequest, mockResponse);
        verify(mockResponse).sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    @DisplayName("No user in session")
    public void testNoUser() throws Exception {
        when(mockSession.getAttribute("user")).thenReturn(null);
        servlet.doPost(mockRequest, mockResponse);
        verify(mockResponse).sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Nested
    @DisplayName("Test Input Validation")
    class TestInputValidation {

        @Test
        @DisplayName("Missing target ID returns error")
        public void testMissingTargetId() throws Exception {
            when(mockRequest.getParameter("target_id")).thenReturn(null);
            servlet.doPost(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("Error"), eq("All fields must be filled up"));
        }

        @Test
        @DisplayName("Blank target ID returns error")
        public void testBlankTargetId() throws Exception {
            when(mockRequest.getParameter("target_id")).thenReturn("  ");
            servlet.doPost(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("Error"), eq("All fields must be filled up"));
        }

        @Test
        @DisplayName("Invalid target ID format returns error")
        public void testInvalidTargetIdFormat() throws Exception {
            when(mockRequest.getParameter("target_id")).thenReturn("abc");
            servlet.doPost(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("Error"), eq("Invalid target user"));
        }

        @Test
        @DisplayName("Reporting yourself returns error")
        public void testReportYourself() throws Exception {
            when(mockRequest.getParameter("target_id")).thenReturn("1"); // same as user_id
            servlet.doPost(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("Error"), eq("You cannot report yourself"));
        }
    }

    @Nested
    @DisplayName("Test Report Submission")
    class TestReportSubmission {

        @Test
        @DisplayName("Successful report")
        public void testSuccessfulReport() throws Exception {
            when(mockReportDAO.reportUser(any(AccountReport.class))).thenReturn(true);
            servlet.doPost(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("success"), eq("Success"));
        }

        @Test
        @DisplayName("Failed report")
        public void testFailedReport() throws Exception {
            when(mockReportDAO.reportUser(any(AccountReport.class))).thenReturn(false);
            servlet.doPost(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("Error"), eq("Failed to submit report"));
        }

        @Test
        @DisplayName("Database error")
        public void testDatabaseError() throws Exception {
            when(mockReportDAO.reportUser(any(AccountReport.class))).thenThrow(new Exception("DB error"));
            servlet.doPost(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("Error"), eq("Could not reach the database"));
        }
    }
}