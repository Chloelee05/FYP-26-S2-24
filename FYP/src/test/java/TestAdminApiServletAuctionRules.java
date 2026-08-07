import com.auction.servlet.api.AdminApiServlet;
import com.auction.test.ApiTestSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * GET/POST /api/admin/auction-rules — NEW for the "platform-wide auction rules" admin story.
 *
 * <p>Like {@code TestAdminApiServletAnnouncements}, this only covers the guard rails that are
 * rejected before the request reaches {@code PlatformSettingsDAO} or the audit log: the ADMIN
 * role check and the key whitelist / value validation on the POST side. The GET success path
 * and the POST success path both touch {@code PlatformSettingsDAO}, so they are intentionally
 * not exercised here for the same reason the announcement test gives.</p>
 */
@DisplayName("AdminApiServlet — GET/POST /auction-rules")
class TestAdminApiServletAuctionRules {

    /** Widens doGet/doPost to public so this test, outside the servlet's package, can call them. */
    private static class Wrapper extends AdminApiServlet {
        @Override public void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws java.io.IOException { super.doGet(req, resp); }
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
        when(req.getPathInfo()).thenReturn("/auction-rules");
    }

    private void asAdmin(int adminId) {
        ApiTestSupport.withBearer(req, ApiTestSupport.newAdminSession(adminId));
    }

    private void params(Map<String, String> values) {
        Map<String, String[]> map = new HashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            map.put(e.getKey(), new String[]{e.getValue()});
            when(req.getParameter(e.getKey())).thenReturn(e.getValue());
        }
        when(req.getParameterMap()).thenReturn(map);
    }

    // ── GET: role gate only ─────────────────────────────────────────────────

    @Test
    @DisplayName("GET rejects a request with no session at all")
    void getRejectsUnauthenticated() throws Exception {
        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        verify(resp).setStatus(401);
        assertEquals("Authentication required", ApiTestSupport.parse(sw).get("error").asText());
    }

    @Test
    @DisplayName("GET rejects a non-admin session")
    void getRejectsNonAdmin() throws Exception {
        ApiTestSupport.withBearer(req, ApiTestSupport.newBuyerSession(5));

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        verify(resp).setStatus(403);
        assertEquals("Access denied", ApiTestSupport.parse(sw).get("error").asText());
    }

    // ── POST: role gate, whitelist, validation ──────────────────────────────

    @Test
    @DisplayName("POST rejects a non-admin session")
    void postRejectsNonAdmin() throws Exception {
        ApiTestSupport.withBearer(req, ApiTestSupport.newBuyerSession(5));
        params(Map.of("min_bid_increment", "0.05"));

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(403);
    }

    @Test
    @DisplayName("POST rejects an unknown parameter key")
    void postRejectsUnknownKey() throws Exception {
        asAdmin(1);
        params(Map.of("some_other_setting", "1"));

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        assertTrue(ApiTestSupport.parse(sw).get("error").asText().contains("Unknown auction rule key"));
    }

    @Test
    @DisplayName("POST rejects a request with neither setting present")
    void postRejectsEmptyRequest() throws Exception {
        asAdmin(1);
        params(Map.of());

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        assertTrue(ApiTestSupport.parse(sw).get("error").asText().contains("is required"));
    }

    @Test
    @DisplayName("POST rejects a non-numeric min_bid_increment")
    void postRejectsNonNumericMinIncrement() throws Exception {
        asAdmin(1);
        params(Map.of("min_bid_increment", "abc"));

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        assertEquals("Invalid min_bid_increment.", ApiTestSupport.parse(sw).get("error").asText());
    }

    @Test
    @DisplayName("POST rejects a zero min_bid_increment")
    void postRejectsZeroMinIncrement() throws Exception {
        asAdmin(1);
        params(Map.of("min_bid_increment", "0"));

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        assertTrue(ApiTestSupport.parse(sw).get("error").asText().contains("greater than 0"));
    }

    @Test
    @DisplayName("POST rejects a min_bid_increment past the sane upper bound")
    void postRejectsOversizedMinIncrement() throws Exception {
        asAdmin(1);
        params(Map.of("min_bid_increment", "5000"));

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        assertTrue(ApiTestSupport.parse(sw).get("error").asText().contains("at most"));
    }

    @Test
    @DisplayName("POST rejects a non-numeric max_auction_duration_days")
    void postRejectsNonNumericMaxDuration() throws Exception {
        asAdmin(1);
        params(Map.of("max_auction_duration_days", "abc"));

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        assertEquals("Invalid max_auction_duration_days.", ApiTestSupport.parse(sw).get("error").asText());
    }

    @Test
    @DisplayName("POST rejects a negative max_auction_duration_days")
    void postRejectsNegativeMaxDuration() throws Exception {
        asAdmin(1);
        params(Map.of("max_auction_duration_days", "-1"));

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        assertTrue(ApiTestSupport.parse(sw).get("error").asText().contains("greater than 0"));
    }

    @Test
    @DisplayName("POST rejects a max_auction_duration_days past the sane upper bound")
    void postRejectsOversizedMaxDuration() throws Exception {
        asAdmin(1);
        params(Map.of("max_auction_duration_days", "999999"));

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        assertTrue(ApiTestSupport.parse(sw).get("error").asText().contains("at most"));
    }
}
