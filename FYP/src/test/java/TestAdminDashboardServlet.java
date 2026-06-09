import com.auction.dao.AuctionDAO;
import com.auction.dao.UserDAO;
import com.auction.model.admin.AdminListingRow;
import com.auction.model.admin.AdminUserSummary;
import com.auction.model.admin.DashboardMetrics;
import com.auction.servlet.admin.AdminDashboardServlet;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.mockito.Mockito.*;

@DisplayName("Test Admin Dashboard Servlet")
public class TestAdminDashboardServlet {

    private static class AdminDashboardServletWrapper extends AdminDashboardServlet {
        AdminDashboardServletWrapper(UserDAO userDAO, AuctionDAO auctionDAO) {
            super(userDAO, auctionDAO);
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            super.doGet(req, resp);
        }
    }

    private AdminDashboardServletWrapper servlet;
    private UserDAO mockUserDAO;
    private AuctionDAO mockAuctionDAO;
    private HttpServletRequest mockRequest;
    private HttpServletResponse mockResponse;
    private RequestDispatcher mockDispatcher;

    @BeforeEach
    void setUp() throws Exception {
        mockUserDAO = mock(UserDAO.class);
        mockAuctionDAO = mock(AuctionDAO.class);
        servlet = new AdminDashboardServletWrapper(mockUserDAO, mockAuctionDAO);

        mockRequest = mock(HttpServletRequest.class);
        mockResponse = mock(HttpServletResponse.class);
        mockDispatcher = mock(RequestDispatcher.class);

        when(mockRequest.getRequestDispatcher(anyString())).thenReturn(mockDispatcher);

        // default DAO stubs
        when(mockUserDAO.countNonDeletedUsers()).thenReturn(10);
        when(mockUserDAO.countActiveUsers()).thenReturn(8);
        when(mockAuctionDAO.countListingsModerationActive()).thenReturn(5);
        when(mockAuctionDAO.countListingsTotal()).thenReturn(20);
        when(mockAuctionDAO.countListingsFlagged()).thenReturn(2);
        when(mockAuctionDAO.sumWinningBidDollars()).thenReturn((long) 1000.0);
        when(mockUserDAO.listUsersForAdminTable()).thenReturn(List.of());
        when(mockAuctionDAO.listListingsForModeration()).thenReturn(List.of());
        when(mockUserDAO.recentRegistrations(6)).thenReturn(List.of());
        when(mockAuctionDAO.recentFlaggedListings(6)).thenReturn(List.of());
        when(mockUserDAO.recentSuspensions(6)).thenReturn(List.of());
    }

    @Nested
    @DisplayName("Test doGet metrics")
    class TestMetrics {

        @Test
        @DisplayName("Metrics are set as request attribute")
        public void testMetricsSet() throws Exception {
            servlet.doGet(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("metrics"), any(DashboardMetrics.class));
        }

        @Test
        @DisplayName("Metrics contain correct user count")
        public void testMetricsUserCount() throws Exception {
            when(mockUserDAO.countNonDeletedUsers()).thenReturn(42);
            servlet.doGet(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("metrics"), argThat(m ->
                    ((DashboardMetrics) m).getTotalUsers() == 42));
        }

        @Test
        @DisplayName("Metrics contain correct flagged count")
        public void testMetricsFlaggedCount() throws Exception {
            when(mockAuctionDAO.countListingsFlagged()).thenReturn(7);
            servlet.doGet(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("metrics"), argThat(m ->
                    ((DashboardMetrics) m).getFlaggedListings() == 7));
        }
    }

    @Nested
    @DisplayName("Test doGet preview lists")
    class TestPreviewLists {

        @Test
        @DisplayName("Preview users capped at 5")
        public void testPreviewUsersCappedAt5() throws Exception {
            List<AdminUserSummary> users = List.of(
                    mock(AdminUserSummary.class),
                    mock(AdminUserSummary.class),
                    mock(AdminUserSummary.class),
                    mock(AdminUserSummary.class),
                    mock(AdminUserSummary.class),
                    mock(AdminUserSummary.class),
                    mock(AdminUserSummary.class)
            );
            when(mockUserDAO.listUsersForAdminTable()).thenReturn(users);
            servlet.doGet(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("previewUsers"), argThat(list ->
                    ((List<?>) list).size() == 5));
        }

        @Test
        @DisplayName("Preview users shows all when less than 5")
        public void testPreviewUsersLessThan5() throws Exception {
            List<AdminUserSummary> users = List.of(
                    mock(AdminUserSummary.class),
                    mock(AdminUserSummary.class)
            );
            when(mockUserDAO.listUsersForAdminTable()).thenReturn(users);
            servlet.doGet(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("previewUsers"), argThat(list ->
                    ((List<?>) list).size() == 2));
        }

        @Test
        @DisplayName("Preview listings capped at 5")
        public void testPreviewListingsCappedAt5() throws Exception {
            List<AdminListingRow> listings = List.of(
                    mock(AdminListingRow.class),
                    mock(AdminListingRow.class),
                    mock(AdminListingRow.class),
                    mock(AdminListingRow.class),
                    mock(AdminListingRow.class),
                    mock(AdminListingRow.class)
            );
            when(mockAuctionDAO.listListingsForModeration()).thenReturn(listings);
            servlet.doGet(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("previewListings"), argThat(list ->
                    ((List<?>) list).size() == 5));
        }

        @Test
        @DisplayName("Preview listings shows all when less than 5")
        public void testPreviewListingsLessThan5() throws Exception {
            List<AdminListingRow> listings = List.of(
                    mock(AdminListingRow.class),
                    mock(AdminListingRow.class)
            );
            when(mockAuctionDAO.listListingsForModeration()).thenReturn(listings);
            servlet.doGet(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("previewListings"), argThat(list ->
                    ((List<?>) list).size() == 2));
        }
    }

    @Nested
    @DisplayName("Test doGet activity")
    class TestActivity {

        @Test
        @DisplayName("Empty activity sets no recent activity message")
        public void testEmptyActivity() throws Exception {
            servlet.doGet(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("activities"), argThat(list ->
                    ((List<?>) list).size() == 1));
        }

        @Test
        @DisplayName("Activity list capped at 12")
        public void testActivityCappedAt12() throws Exception {
            List<UserDAO.NamedInstantEvent> registrations = List.of(
                    mockEvent(), mockEvent(), mockEvent(), mockEvent(), mockEvent(), mockEvent()
            );
            List<AuctionDAO.FlaggedTitleEvent> flagged = List.of(
                    mockFlaggedEvent(), mockFlaggedEvent(), mockFlaggedEvent(),
                    mockFlaggedEvent(), mockFlaggedEvent(), mockFlaggedEvent()
            );
            when(mockUserDAO.recentRegistrations(6)).thenReturn(registrations);
            when(mockAuctionDAO.recentFlaggedListings(6)).thenReturn(flagged);

            servlet.doGet(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("activities"), argThat(list ->
                    ((List<?>) list).size() <= 12));
        }
    }

    @Nested
    @DisplayName("Test doGet navigation and forwarding")
    class TestNavigation {

        @Test
        @DisplayName("Sets adminActiveNav to overview")
        public void testActiveNav() throws Exception {
            servlet.doGet(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("adminActiveNav"), eq("overview"));
        }

        @Test
        @DisplayName("Forwards to dashboard JSP")
        public void testForwardsToDashboard() throws Exception {
            servlet.doGet(mockRequest, mockResponse);
            verify(mockRequest).getRequestDispatcher("/WEB-INF/views/admin/dashboard.jsp");
            verify(mockDispatcher).forward(mockRequest, mockResponse);
        }
    }

    // helpers
    private UserDAO.NamedInstantEvent mockEvent() {
        UserDAO.NamedInstantEvent e = mock(UserDAO.NamedInstantEvent.class);
        when(e.getAt()).thenReturn(java.time.Instant.now());
        when(e.getName()).thenReturn("TestUser");
        return e;
    }

    private AuctionDAO.FlaggedTitleEvent mockFlaggedEvent() {
        AuctionDAO.FlaggedTitleEvent e = mock(AuctionDAO.FlaggedTitleEvent.class);
        when(e.getAt()).thenReturn(java.time.Instant.now());
        when(e.getTitle()).thenReturn("TestListing");
        return e;
    }
}