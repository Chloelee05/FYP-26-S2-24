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
 * POST /api/admin/announcements — NEW for the "system-wide announcement" admin story.
 *
 * <p>Covers only the guard rails that must hold before the request ever reaches
 * {@code NotificationService.broadcastAnnouncement} or the audit log: the ADMIN role check
 * (shared with every other write in {@code AdminApiServlet}, since {@code AdminFilter} does
 * not cover {@code /api/*}) and the title/body validation. None of these cases touch a
 * database, which is deliberate — {@code AdminApiServlet} builds its DAOs directly rather
 * than accepting them by constructor or setter, so a test that needs the DAOs mocked would
 * need a real database instead; every case here is rejected before that point is reached.</p>
 */
@DisplayName("AdminApiServlet — POST /announcements")
class TestAdminApiServletAnnouncements {

    /** Widens doPost to public so this test, outside the servlet's package, can call it directly. */
    private static class Wrapper extends AdminApiServlet {
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws java.io.IOException { super.doPost(req, resp); }
    }

    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() {
        servlet = new Wrapper();
        req = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
        when(req.getPathInfo()).thenReturn("/announcements");
    }

    private void asAdmin(int adminId) {
        ApiTestSupport.withBearer(req, ApiTestSupport.newAdminSession(adminId));
    }

    @Test
    @DisplayName("rejects a request with no session at all")
    void rejectsUnauthenticated() throws Exception {
        when(req.getParameter("title")).thenReturn("Maintenance");
        when(req.getParameter("body")).thenReturn("Down 2-3am.");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(401);
        assertEquals("Authentication required", ApiTestSupport.parse(sw).get("error").asText());
    }

    @Test
    @DisplayName("rejects a non-admin session")
    void rejectsNonAdmin() throws Exception {
        ApiTestSupport.withBearer(req, ApiTestSupport.newBuyerSession(7));
        when(req.getParameter("title")).thenReturn("Maintenance");
        when(req.getParameter("body")).thenReturn("Down 2-3am.");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(403);
        assertEquals("Access denied", ApiTestSupport.parse(sw).get("error").asText());
    }

    @Test
    @DisplayName("rejects a blank title")
    void rejectsBlankTitle() throws Exception {
        asAdmin(1);
        when(req.getParameter("title")).thenReturn("   ");
        when(req.getParameter("body")).thenReturn("Down 2-3am.");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        assertEquals("title is required.", ApiTestSupport.parse(sw).get("error").asText());
    }

    @Test
    @DisplayName("rejects a missing title")
    void rejectsMissingTitle() throws Exception {
        asAdmin(1);
        when(req.getParameter("body")).thenReturn("Down 2-3am.");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        assertEquals("title is required.", ApiTestSupport.parse(sw).get("error").asText());
    }

    @Test
    @DisplayName("rejects a blank body")
    void rejectsBlankBody() throws Exception {
        asAdmin(1);
        when(req.getParameter("title")).thenReturn("Maintenance");
        when(req.getParameter("body")).thenReturn("   ");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        assertEquals("body is required.", ApiTestSupport.parse(sw).get("error").asText());
    }

    @Test
    @DisplayName("rejects a title past the length cap")
    void rejectsOverlongTitle() throws Exception {
        asAdmin(1);
        when(req.getParameter("title")).thenReturn("x".repeat(201));
        when(req.getParameter("body")).thenReturn("Down 2-3am.");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        assertTrue(ApiTestSupport.parse(sw).get("error").asText().contains("200 characters"));
    }

    @Test
    @DisplayName("rejects a body past the length cap")
    void rejectsOverlongBody() throws Exception {
        asAdmin(1);
        when(req.getParameter("title")).thenReturn("Maintenance");
        when(req.getParameter("body")).thenReturn("x".repeat(2001));

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        assertTrue(ApiTestSupport.parse(sw).get("error").asText().contains("2000 characters"));
    }
}
