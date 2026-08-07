import com.auction.servlet.api.AdminApiServlet;
import com.auction.test.ApiTestSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * GET /api/admin/analytics/report — the new dateFrom/dateTo/category validation added for the
 * "report filters by date range, category and seller" admin story.
 *
 * <p>Every case here is rejected by the new validation before the request ever reaches
 * {@code AdminReportDAO}, so — like {@code TestAdminApiServletAnnouncements} — none of these
 * touch a database. A request with none of the three new parameters is untouched by this
 * story and is not re-tested here.</p>
 */
@DisplayName("AdminApiServlet — GET /analytics/report date/category filter validation")
class TestAdminApiServletReportFilters {

    /** Widens doGet to public so this test, outside the servlet's package, can call it directly. */
    private static class Wrapper extends AdminApiServlet {
        @Override public void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws java.io.IOException { super.doGet(req, resp); }
    }

    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() {
        servlet = new Wrapper();
        req = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
        when(req.getPathInfo()).thenReturn("/analytics/report");
        ApiTestSupport.withBearer(req, ApiTestSupport.newAdminSession(1));
    }

    private String errorMessage(StringWriter sw) throws Exception {
        return ApiTestSupport.parse(sw).get("error").asText();
    }

    @Test
    @DisplayName("rejects an unparseable dateFrom")
    void rejectsInvalidDateFrom() throws Exception {
        when(req.getParameter("type")).thenReturn("revenue");
        when(req.getParameter("dateFrom")).thenReturn("not-a-date");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        verify(resp).setStatus(400);
        assertTrue(errorMessage(sw).contains("dateFrom"));
    }

    @Test
    @DisplayName("rejects an unparseable dateTo")
    void rejectsInvalidDateTo() throws Exception {
        when(req.getParameter("type")).thenReturn("revenue");
        when(req.getParameter("dateTo")).thenReturn("31-12-2025");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        verify(resp).setStatus(400);
        assertTrue(errorMessage(sw).contains("dateTo"));
    }

    @Test
    @DisplayName("rejects dateFrom after dateTo")
    void rejectsDateFromAfterDateTo() throws Exception {
        when(req.getParameter("type")).thenReturn("revenue");
        when(req.getParameter("dateFrom")).thenReturn("2025-06-01");
        when(req.getParameter("dateTo")).thenReturn("2025-01-01");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        verify(resp).setStatus(400);
        assertTrue(errorMessage(sw).contains("dateFrom must not be after dateTo"));
    }

    @Test
    @DisplayName("rejects an absurdly large date range")
    void rejectsAbsurdRange() throws Exception {
        when(req.getParameter("type")).thenReturn("revenue");
        when(req.getParameter("dateFrom")).thenReturn("2000-01-01");
        when(req.getParameter("dateTo")).thenReturn("2024-01-01");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        verify(resp).setStatus(400);
        assertTrue(errorMessage(sw).contains("too large"));
    }

    @Test
    @DisplayName("rejects a dateTo in the future")
    void rejectsFutureDateTo() throws Exception {
        when(req.getParameter("type")).thenReturn("revenue");
        when(req.getParameter("dateTo")).thenReturn("2999-01-01");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        verify(resp).setStatus(400);
        assertTrue(errorMessage(sw).contains("future"));
    }

    @Test
    @DisplayName("rejects a dateFrom before the year 2000 sanity floor")
    void rejectsImplausiblyOldDate() throws Exception {
        when(req.getParameter("type")).thenReturn("revenue");
        when(req.getParameter("dateFrom")).thenReturn("1899-01-01");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        verify(resp).setStatus(400);
        assertTrue(errorMessage(sw).contains("2000"));
    }

    @Test
    @DisplayName("rejects a non-admin session the same way every other GET on this servlet does")
    void rejectsNonAdmin() throws Exception {
        ApiTestSupport.withBearer(req, ApiTestSupport.newBuyerSession(9));
        when(req.getParameter("type")).thenReturn("revenue");
        when(req.getParameter("dateFrom")).thenReturn("not-a-date");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        verify(resp).setStatus(403);
    }
}
