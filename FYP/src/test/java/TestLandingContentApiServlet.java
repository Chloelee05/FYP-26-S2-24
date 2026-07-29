import com.auction.dao.LandingContentDAO;
import com.auction.servlet.api.LandingContentApiServlet;
import com.auction.test.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the public landing copy endpoint.
 *
 * <p>The endpoint is deliberately unauthenticated (guests read the landing page) and
 * fail-soft, so a missing {@code landing_content} table or a database outage must still
 * produce a 200 with an empty map rather than breaking the home page.</p>
 */
@DisplayName("LandingContentApiServlet")
class TestLandingContentApiServlet {

    private static class Wrapper extends LandingContentApiServlet {
        @Override public void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws java.io.IOException { super.doGet(req, resp); }
    }

    private LandingContentDAO mockDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() throws Exception {
        // The TTL cache is shared across instances; each test starts from a cold cache.
        LandingContentApiServlet.invalidateCache();

        mockDAO = mock(LandingContentDAO.class);
        servlet = new Wrapper();
        ServletContext ctx = mock(ServletContext.class);
        ServletConfig config = mock(ServletConfig.class);
        when(config.getServletContext()).thenReturn(ctx);
        servlet.init(config);
        servlet.setLandingContentDAO(mockDAO);
        req  = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
    }

    @Test
    @DisplayName("returns the content map without authentication")
    void returnsContentMap() throws Exception {
        Map<String, String> content = new LinkedHashMap<>();
        content.put("hero.headline", "Bid smart, buy");
        content.put("guest.heading", "Ready to place your first bid?");
        when(mockDAO.findAllValues()).thenReturn(content);

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        JsonNode body = ApiTestSupport.parse(sw);
        verify(resp).setStatus(200);
        assertEquals("Bid smart, buy", body.get("hero.headline").asText());
        assertEquals("Ready to place your first bid?", body.get("guest.heading").asText());
        // No token is read, so guests are served the same payload as signed-in users.
        verify(req, never()).getHeader("Authorization");
    }

    @Test
    @DisplayName("fails soft with an empty map when the query blows up")
    void failsSoftOnDatabaseError() throws Exception {
        when(mockDAO.findAllValues()).thenThrow(new RuntimeException("relation does not exist"));

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        JsonNode body = ApiTestSupport.parse(sw);
        verify(resp).setStatus(200);
        assertTrue(body.isObject());
        assertEquals(0, body.size());
    }

    @Test
    @DisplayName("caches the result so the landing page does not re-query per visitor")
    void cachesBetweenRequests() throws Exception {
        when(mockDAO.findAllValues()).thenReturn(Map.of("hero.headline", "Bid smart, buy"));

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);
        servlet.doGet(req, resp);

        verify(mockDAO, times(1)).findAllValues();
    }

    @Test
    @DisplayName("invalidateCache forces a re-read after an admin edit")
    void invalidateCacheForcesReread() throws Exception {
        when(mockDAO.findAllValues()).thenReturn(Map.of("hero.headline", "Bid smart, buy"));

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);
        LandingContentApiServlet.invalidateCache();
        servlet.doGet(req, resp);

        verify(mockDAO, times(2)).findAllValues();
    }
}
