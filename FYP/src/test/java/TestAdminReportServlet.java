import com.auction.dao.ReportDAO;
import com.auction.model.AccountReport;
import com.auction.model.User;
import com.auction.servlet.admin.AdminReportServlet;
import com.auction.util.RbacUtil;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.util.List;

import static org.mockito.Mockito.*;

@DisplayName("Test Admin Report Servlet")
public class TestAdminReportServlet {

    private static class AdminReportServletWrapper extends AdminReportServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            super.doGet(req, resp);
        }

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            super.doPost(req, resp);
        }
    }

    private AdminReportServletWrapper servlet;
    private ReportDAO mockReportDAO;
    private HttpServletRequest mockRequest;
    private HttpServletResponse mockResponse;
    private HttpSession mockSession;
    private User mockUser = mock(User.class);
    private MockedStatic<RbacUtil> mockedRbac;

    @BeforeEach
    void setUp() throws Exception {
        servlet = new AdminReportServletWrapper();
        mockReportDAO = mock(ReportDAO.class);
        servlet.setReportDAO(mockReportDAO);

        mockRequest = mock(HttpServletRequest.class);
        mockResponse = mock(HttpServletResponse.class);
        mockSession = mock(HttpSession.class);

        when(mockRequest.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute("user")).thenReturn(mockUser);

        mockedRbac = mockStatic(RbacUtil.class);
        mockedRbac.when(() -> RbacUtil.isAdmin(any())).thenReturn(true);

        ServletContext mockContext = mock(ServletContext.class);
        ServletConfig mockConfig = mock(ServletConfig.class);
        when(mockConfig.getServletContext()).thenReturn(mockContext);
        servlet.init(mockConfig);
    }

    @AfterEach
    void tearDown() {
        mockedRbac.close();
    }

    @Nested
    @DisplayName("Test doGet")
    class TestDoGet {

        @Test
        @DisplayName("No session returns 401")
        public void testNoSession() throws Exception {
            when(mockRequest.getSession(false)).thenReturn(null);
            servlet.doGet(mockRequest, mockResponse);
            verify(mockResponse).sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }

        @Test
        @DisplayName("No user returns 401")
        public void testNoUser() throws Exception {
            when(mockSession.getAttribute("user")).thenReturn(null);
            servlet.doGet(mockRequest, mockResponse);
            verify(mockResponse).sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }

        @Test
        @DisplayName("Non-admin returns 401")
        public void testNonAdmin() throws Exception {
            mockedRbac.when(() -> RbacUtil.isAdmin(any())).thenReturn(false);
            servlet.doGet(mockRequest, mockResponse);
            verify(mockResponse).sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }

        @Test
        @DisplayName("Success sets report_list attribute")
        public void testSuccess() throws Exception {
            List<AccountReport> reports = List.of(new AccountReport(), new AccountReport());
            when(mockReportDAO.getAllReports()).thenReturn(reports);
            servlet.doGet(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("report_list"), eq(reports));
        }

        @Test
        @DisplayName("Database error throws RuntimeException")
        public void testDatabaseError() throws Exception {
            when(mockReportDAO.getAllReports()).thenThrow(new Exception("DB error"));
            Assertions.assertThrows(RuntimeException.class, () -> servlet.doGet(mockRequest, mockResponse));
        }
    }

    @Nested
    @DisplayName("Test doPost")
    class TestDoPost {

        @BeforeEach
        void setUp() {
            when(mockRequest.getParameter("moderation_status")).thenReturn("resolved");
            when(mockRequest.getParameter("auction_id")).thenReturn("1");
        }

        @Test
        @DisplayName("No session returns 401")
        public void testNoSession() throws Exception {
            when(mockRequest.getSession(false)).thenReturn(null);
            servlet.doPost(mockRequest, mockResponse);
            verify(mockResponse).sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }

        @Test
        @DisplayName("No user returns 401")
        public void testNoUser() throws Exception {
            when(mockSession.getAttribute("user")).thenReturn(null);
            servlet.doPost(mockRequest, mockResponse);
            verify(mockResponse).sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }

        @Test
        @DisplayName("Non-admin returns 401")
        public void testNonAdmin() throws Exception {
            mockedRbac.when(() -> RbacUtil.isAdmin(any())).thenReturn(false);
            servlet.doPost(mockRequest, mockResponse);
            verify(mockResponse).sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }

        @Test
        @DisplayName("Null moderation status returns error")
        public void testNullModerationStatus() throws Exception {
            when(mockRequest.getParameter("moderation_status")).thenReturn(null);
            servlet.doPost(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("Error"), eq("Invalid moderation status:"));
        }

        @Test
        @DisplayName("Blank moderation status returns error")
        public void testBlankModerationStatus() throws Exception {
            when(mockRequest.getParameter("moderation_status")).thenReturn("  ");
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

        @Test
        @DisplayName("Successful report status update")
        public void testSuccessfulUpdate() throws Exception {
            when(mockReportDAO.setReportStatus(anyLong(), anyString())).thenReturn(true);
            servlet.doPost(mockRequest, mockResponse);
            verify(mockRequest, never()).setAttribute(eq("Error"), anyString());
        }

        @Test
        @DisplayName("Database error throws RuntimeException")
        public void testDatabaseError() throws Exception {
            when(mockReportDAO.setReportStatus(anyLong(), anyString())).thenThrow(new Exception("DB error"));
            Assertions.assertThrows(RuntimeException.class, () -> servlet.doPost(mockRequest, mockResponse));
        }
    }
}