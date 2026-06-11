import com.auction.dao.AuctionDAO;
import com.auction.dao.UserDAO;
import com.auction.model.Role;
import com.auction.model.User;
import com.auction.model.admin.AdminListingRow;
import com.auction.model.admin.AdminUserSummary;
import com.auction.servlet.admin.AdminGenReportServlet;
import com.auction.util.RbacUtil;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;

@DisplayName("Test Admin Gen Report Servlet")
public class TestAdminGenReportServlet {

    private static class AdminGenReportServletWrapper extends AdminGenReportServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            super.doGet(req, resp);
        }
    }

    private AdminGenReportServletWrapper servlet;
    private UserDAO mockUserDAO;
    private AuctionDAO mockAuctionDAO;
    private HttpServletRequest mockRequest;
    private HttpServletResponse mockResponse;
    private HttpSession mockSession;
    private User mockUser = mock(User.class);
    private MockedStatic<RbacUtil> mockedRbac;

    @BeforeEach
    void setUp() throws Exception {
        mockUserDAO   = mock(UserDAO.class);
        mockAuctionDAO = mock(AuctionDAO.class);
        servlet = new AdminGenReportServletWrapper();
        servlet.setDAOs(mockUserDAO, mockAuctionDAO);

        mockRequest  = mock(HttpServletRequest.class);
        mockResponse = mock(HttpServletResponse.class);
        mockSession  = mock(HttpSession.class);

        when(mockRequest.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute("user")).thenReturn(mockUser);

        mockedRbac = mockStatic(RbacUtil.class);
        mockedRbac.when(() -> RbacUtil.isAdmin(any())).thenReturn(true);

        // default all params to null (no filters)
        when(mockRequest.getParameter("sellerUsername")).thenReturn(null);
        when(mockRequest.getParameter("category")).thenReturn(null);
        when(mockRequest.getParameter("from")).thenReturn(null);
        when(mockRequest.getParameter("to")).thenReturn(null);

        // mock response output stream for PDF writing
        when(mockResponse.getOutputStream()).thenReturn(new jakarta.servlet.ServletOutputStream() {
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            @Override public void write(int b) { buf.write(b); }
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(jakarta.servlet.WriteListener l) {}
        });

        // default DAO stubs for full report
        when(mockUserDAO.countNonDeletedUsers()).thenReturn(10);
        when(mockUserDAO.countActiveUsers()).thenReturn(8);
        when(mockAuctionDAO.countListingsTotal()).thenReturn(20);
        when(mockAuctionDAO.countListingsFlagged()).thenReturn(2);
        when(mockAuctionDAO.sumWinningBidDollars()).thenReturn(1000L);
        when(mockUserDAO.listUsersForAdminTable()).thenReturn(List.of());
        when(mockAuctionDAO.listListingsForModeration()).thenReturn(List.of());
        when(mockAuctionDAO.listForGenReport(any(), any(), any(), any())).thenReturn(List.of());

        ServletContext mockContext = mock(ServletContext.class);
        ServletConfig mockConfig  = mock(ServletConfig.class);
        when(mockConfig.getServletContext()).thenReturn(mockContext);
        servlet.init(mockConfig);
    }

    @AfterEach
    void tearDown() {
        mockedRbac.close();
    }

    @Nested
    @DisplayName("Test Authentication")
    class TestAuthentication {

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
    }

    @Nested
    @DisplayName("Test Date Validation")
    class TestDateValidation {

        @Test
        @DisplayName("Invalid date format returns 400")
        public void testInvalidDateFormat() throws Exception {
            when(mockRequest.getParameter("from")).thenReturn("not-a-date");
            servlet.doGet(mockRequest, mockResponse);
            verify(mockResponse).sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid date format");
        }

        @Test
        @DisplayName("End date before start date returns 400")
        public void testEndBeforeStart() throws Exception {
            when(mockRequest.getParameter("from")).thenReturn("2026-05-15");
            when(mockRequest.getParameter("to")).thenReturn("2026-05-10");
            servlet.doGet(mockRequest, mockResponse);
            verify(mockResponse).sendError(HttpServletResponse.SC_BAD_REQUEST, "End date must be after start date");
        }

        @Test
        @DisplayName("Valid date range passes validation")
        public void testValidDateRange() throws Exception {
            when(mockRequest.getParameter("from")).thenReturn("2026-05-10");
            when(mockRequest.getParameter("to")).thenReturn("2026-05-15");
            servlet.doGet(mockRequest, mockResponse);
            verify(mockResponse, never()).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
        }
    }

    @Nested
    @DisplayName("Test Full Report (no filters)")
    class TestFullReport {

        @Test
        @DisplayName("Sets PDF content type")
        public void testContentType() throws Exception {
            servlet.doGet(mockRequest, mockResponse);
            verify(mockResponse).setContentType("application/pdf");
        }

        @Test
        @DisplayName("Sets content disposition header")
        public void testContentDisposition() throws Exception {
            servlet.doGet(mockRequest, mockResponse);
            verify(mockResponse).setHeader("Content-Disposition", "attachment; filename=admin-report.pdf");
        }

        @Test
        @DisplayName("Calls all DAO methods for full report")
        public void testFullReportDaoCalls() throws Exception {
            servlet.doGet(mockRequest, mockResponse);
            verify(mockUserDAO).countNonDeletedUsers();
            verify(mockUserDAO).countActiveUsers();
            verify(mockAuctionDAO).countListingsTotal();
            verify(mockAuctionDAO).countListingsFlagged();
            verify(mockAuctionDAO).sumWinningBidDollars();
            verify(mockUserDAO).listUsersForAdminTable();
            verify(mockAuctionDAO).listListingsForModeration();
        }

        @Test
        @DisplayName("Does not call filtered DAO method for full report")
        public void testFullReportNoFilteredDaoCall() throws Exception {
            servlet.doGet(mockRequest, mockResponse);
            verify(mockAuctionDAO, never()).listForGenReport(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Full report with user and listing data")
        public void testFullReportWithData() throws Exception {
            AdminUserSummary mockUser = mock(AdminUserSummary.class);
            when(mockUser.getId()).thenReturn(1);
            when(mockUser.getUsername()).thenReturn("testuser");
            when(mockUser.getEmail()).thenReturn("test@test.com");
            when(mockUser.getRole()).thenReturn(Role.valueOf("SELLER"));
            when(mockUserDAO.listUsersForAdminTable()).thenReturn(List.of(mockUser));

            AdminListingRow mockListing = mock(AdminListingRow.class);
            when(mockListing.getAuctionId()).thenReturn(1L);
            when(mockListing.getTitle()).thenReturn("Test Listing");
            when(mockListing.getSellerUsername()).thenReturn("testuser");
            when(mockListing.getModerationState()).thenReturn("active");
            when(mockAuctionDAO.listListingsForModeration()).thenReturn(List.of(mockListing));

            servlet.doGet(mockRequest, mockResponse);
            verify(mockResponse).setContentType("application/pdf");
            verify(mockResponse, never()).sendError(anyInt());
        }
    }

    @Nested
    @DisplayName("Test Filtered Report")
    class TestFilteredReport {

        @Test
        @DisplayName("Calls filtered DAO method when seller filter set")
        public void testSellerFilter() throws Exception {
            when(mockRequest.getParameter("sellerUsername")).thenReturn("john");
            servlet.doGet(mockRequest, mockResponse);
            verify(mockAuctionDAO).listForGenReport(eq("john"), isNull(), isNull(), isNull());
            verify(mockAuctionDAO, never()).listListingsForModeration();
        }

        @Test
        @DisplayName("Calls filtered DAO method when category filter set")
        public void testCategoryFilter() throws Exception {
            when(mockRequest.getParameter("category")).thenReturn("Cars");
            servlet.doGet(mockRequest, mockResponse);
            verify(mockAuctionDAO).listForGenReport(isNull(), eq("Cars"), isNull(), isNull());
        }

        @Test
        @DisplayName("Calls filtered DAO with date range")
        public void testDateRangeFilter() throws Exception {
            when(mockRequest.getParameter("from")).thenReturn("2026-01-01");
            when(mockRequest.getParameter("to")).thenReturn("2026-06-01");
            servlet.doGet(mockRequest, mockResponse);
            verify(mockAuctionDAO).listForGenReport(isNull(), isNull(), any(Instant.class), any(Instant.class));
        }

        @Test
        @DisplayName("Calls filtered DAO with all filters set")
        public void testAllFilters() throws Exception {
            when(mockRequest.getParameter("sellerUsername")).thenReturn("john");
            when(mockRequest.getParameter("category")).thenReturn("Cars");
            when(mockRequest.getParameter("from")).thenReturn("2026-01-01");
            when(mockRequest.getParameter("to")).thenReturn("2026-06-01");
            servlet.doGet(mockRequest, mockResponse);
            verify(mockAuctionDAO).listForGenReport(eq("john"), eq("Cars"), any(Instant.class), any(Instant.class));
        }

        @Test
        @DisplayName("Empty filtered results still generates PDF")
        public void testEmptyFilteredResults() throws Exception {
            when(mockRequest.getParameter("sellerUsername")).thenReturn("nobody");
            when(mockAuctionDAO.listForGenReport(any(), any(), any(), any())).thenReturn(List.of());
            servlet.doGet(mockRequest, mockResponse);
            verify(mockResponse).setContentType("application/pdf");
            verify(mockResponse, never()).sendError(anyInt());
        }

        @Test
        @DisplayName("Does not call metrics DAOs for filtered report")
        public void testFilteredReportNoMetricsCalls() throws Exception {
            when(mockRequest.getParameter("sellerUsername")).thenReturn("john");
            servlet.doGet(mockRequest, mockResponse);
            verify(mockUserDAO, never()).countNonDeletedUsers();
            verify(mockUserDAO, never()).countActiveUsers();
            verify(mockAuctionDAO, never()).countListingsTotal();
        }
    }

    @Nested
    @DisplayName("Test Database Errors")
    class TestDatabaseErrors {

        @Test
        @DisplayName("DAO error returns 500")
        public void testDaoError() throws Exception {
            when(mockUserDAO.countNonDeletedUsers()).thenThrow(new RuntimeException("DB error"));
            servlet.doGet(mockRequest, mockResponse);
            verify(mockResponse).sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Could not generate report");
        }

        @Test
        @DisplayName("Filtered DAO error returns 500")
        public void testFilteredDaoError() throws Exception {
            when(mockRequest.getParameter("sellerUsername")).thenReturn("john");
            when(mockAuctionDAO.listForGenReport(any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("DB error"));
            servlet.doGet(mockRequest, mockResponse);
            verify(mockResponse).sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Could not generate report");
        }
    }
}