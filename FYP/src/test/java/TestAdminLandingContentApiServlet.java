import com.auction.dao.LandingContentDAO;
import com.auction.model.admin.LandingContentItem;
import com.auction.servlet.api.AdminLandingContentApiServlet;
import com.auction.test.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the admin landing-copy write endpoint.
 *
 * <p>{@code AdminFilter} only guards the JSP {@code /admin/*} paths, so this servlet
 * enforces the ADMIN role itself like the other {@code /api/admin} endpoints; those
 * checks are covered here alongside input validation.</p>
 */
@DisplayName("AdminLandingContentApiServlet")
class TestAdminLandingContentApiServlet {

    private static final Set<String> KEYS =
            Set.of("hero.headline", "hero.subheading", "guest.heading");

    private static class Wrapper extends AdminLandingContentApiServlet {
        @Override public void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws java.io.IOException { super.doGet(req, resp); }
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws java.io.IOException { super.doPost(req, resp); }
    }

    private LandingContentDAO mockDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() {
        mockDAO = mock(LandingContentDAO.class);
        servlet = new Wrapper();
        servlet.setLandingContentDAO(mockDAO);
        req  = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
    }

    private void params(String... keyValuePairs) {
        Map<String, String[]> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put(keyValuePairs[i], new String[]{keyValuePairs[i + 1]});
            when(req.getParameter(keyValuePairs[i])).thenReturn(keyValuePairs[i + 1]);
        }
        when(req.getParameterMap()).thenReturn(map);
    }

    // ── Admin-only enforcement ───────────────────────────────────────────────

    @Test
    @DisplayName("POST without a token is rejected with 401")
    void writeRequiresAuthentication() throws Exception {
        params("hero.headline", "Hijacked");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(401);
        assertEquals("Authentication required", ApiTestSupport.parse(sw).get("error").asText());
        verify(mockDAO, never()).updateAll(any(), any());
    }

    @Test
    @DisplayName("POST as a non-admin is rejected with 403")
    void writeRequiresAdminRole() throws Exception {
        ApiTestSupport.withBearer(req, ApiTestSupport.newBuyerSession(7));
        params("hero.headline", "Hijacked");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(403);
        assertEquals("Access denied", ApiTestSupport.parse(sw).get("error").asText());
        verify(mockDAO, never()).updateAll(any(), any());
    }

    @Test
    @DisplayName("GET as a non-admin is rejected with 403")
    void readRequiresAdminRole() throws Exception {
        ApiTestSupport.withBearer(req, ApiTestSupport.newSellingBuyerSession(7));

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        verify(resp).setStatus(403);
        verify(mockDAO, never()).listAll();
    }

    // ── GET ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET returns every field with its admin-form metadata")
    void listsFieldsForAdmin() throws Exception {
        ApiTestSupport.withBearer(req, ApiTestSupport.newAdminSession(1));
        when(mockDAO.listAll()).thenReturn(List.of(new LandingContentItem(
                "hero.headline", "Hero", "Headline", "Bid smart, buy", "Bid smart, buy",
                false, 20, null, null)));

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        JsonNode body = ApiTestSupport.parse(sw);
        verify(resp).setStatus(200);
        assertTrue(body.isArray());
        assertEquals("hero.headline", body.get(0).get("key").asText());
        assertEquals("Hero", body.get(0).get("group").asText());
        assertTrue(body.get(0).get("default").asBoolean());
    }

    // ── Update ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("saves the submitted fields and stamps the editing admin")
    void savesSubmittedFields() throws Exception {
        ApiTestSupport.withBearer(req, ApiTestSupport.newAdminSession(42));
        when(mockDAO.allKeys()).thenReturn(KEYS);
        when(mockDAO.updateAll(any(), any())).thenReturn(2);
        params("hero.headline", "  Bid better  ", "guest.heading", "Join in?");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(200);
        assertEquals(2, ApiTestSupport.parse(sw).get("updated").asInt());
        verify(mockDAO).updateAll(
                Map.of("hero.headline", "Bid better", "guest.heading", "Join in?"), 42);
    }

    @Test
    @DisplayName("rejects an unknown content key without writing anything")
    void rejectsUnknownKey() throws Exception {
        ApiTestSupport.withBearer(req, ApiTestSupport.newAdminSession(1));
        when(mockDAO.allKeys()).thenReturn(KEYS);
        params("hero.headline", "Fine", "hero.injected", "Not a real field");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        assertEquals("Unknown content key: hero.injected",
                ApiTestSupport.parse(sw).get("error").asText());
        verify(mockDAO, never()).updateAll(any(), any());
    }

    @Test
    @DisplayName("rejects a blank value so the landing page never renders empty copy")
    void rejectsBlankValue() throws Exception {
        ApiTestSupport.withBearer(req, ApiTestSupport.newAdminSession(1));
        when(mockDAO.allKeys()).thenReturn(KEYS);
        params("hero.headline", "   ");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        assertTrue(ApiTestSupport.parse(sw).get("error").asText().contains("cannot be empty"));
        verify(mockDAO, never()).updateAll(any(), any());
    }

    @Test
    @DisplayName("rejects a value past the length cap")
    void rejectsOverlongValue() throws Exception {
        ApiTestSupport.withBearer(req, ApiTestSupport.newAdminSession(1));
        when(mockDAO.allKeys()).thenReturn(KEYS);
        params("hero.subheading", "x".repeat(2001));

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        assertTrue(ApiTestSupport.parse(sw).get("error").asText().contains("2000 characters"));
        verify(mockDAO, never()).updateAll(any(), any());
    }

    @Test
    @DisplayName("rejects markup in a value")
    void rejectsMarkup() throws Exception {
        ApiTestSupport.withBearer(req, ApiTestSupport.newAdminSession(1));
        when(mockDAO.allKeys()).thenReturn(KEYS);
        params("hero.headline", "<script>alert(1)</script>");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        verify(mockDAO, never()).updateAll(any(), any());
    }

    @Test
    @DisplayName("rejects a POST that carries no content fields")
    void rejectsEmptySubmission() throws Exception {
        ApiTestSupport.withBearer(req, ApiTestSupport.newAdminSession(1));
        when(mockDAO.allKeys()).thenReturn(KEYS);
        params("action", "UPDATE");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        assertEquals("No content fields supplied.", ApiTestSupport.parse(sw).get("error").asText());
        verify(mockDAO, never()).updateAll(any(), any());
    }

    @Test
    @DisplayName("rejects an unknown action")
    void rejectsUnknownAction() throws Exception {
        ApiTestSupport.withBearer(req, ApiTestSupport.newAdminSession(1));
        params("action", "DROP");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        assertEquals("Unknown action: DROP", ApiTestSupport.parse(sw).get("error").asText());
        verify(mockDAO, never()).updateAll(any(), any());
    }

    // ── Reset to default ─────────────────────────────────────────────────────

    @Test
    @DisplayName("resets one field to its seeded default")
    void resetsSingleField() throws Exception {
        ApiTestSupport.withBearer(req, ApiTestSupport.newAdminSession(5));
        when(mockDAO.resetToDefault("hero.headline", 5)).thenReturn(true);
        params("action", "RESET", "key", "hero.headline");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(200);
        assertEquals("Field restored to its default.",
                ApiTestSupport.parse(sw).get("message").asText());
        verify(mockDAO).resetToDefault("hero.headline", 5);
    }

    @Test
    @DisplayName("resetting an unknown key returns 404")
    void resetUnknownKey() throws Exception {
        ApiTestSupport.withBearer(req, ApiTestSupport.newAdminSession(5));
        when(mockDAO.resetToDefault(anyString(), anyInt())).thenReturn(false);
        params("action", "RESET", "key", "hero.nope");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(404);
        assertEquals("Unknown content key: hero.nope",
                ApiTestSupport.parse(sw).get("error").asText());
    }

    @Test
    @DisplayName("resets a whole group to its seeded defaults")
    void resetsGroup() throws Exception {
        ApiTestSupport.withBearer(req, ApiTestSupport.newAdminSession(5));
        when(mockDAO.resetGroup("Hero", 5)).thenReturn(10);
        params("action", "RESET", "group", "Hero");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(200);
        assertTrue(ApiTestSupport.parse(sw).get("message").asText().startsWith("10 field(s)"));
        verify(mockDAO).resetGroup("Hero", 5);
    }

    @Test
    @DisplayName("reset without key or group is rejected")
    void resetWithoutTarget() throws Exception {
        ApiTestSupport.withBearer(req, ApiTestSupport.newAdminSession(5));
        params("action", "RESET");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        assertEquals("key or group is required to reset.",
                ApiTestSupport.parse(sw).get("error").asText());
        verify(mockDAO, never()).resetToDefault(any(), any());
    }
}
