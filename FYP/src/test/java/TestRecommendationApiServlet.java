import com.auction.dao.RecommendationDAO;
import com.auction.model.SearchResultItem;
import com.auction.servlet.api.RecommendationApiServlet;
import com.auction.test.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("RecommendationApiServlet")
class TestRecommendationApiServlet {

    private static class Wrapper extends RecommendationApiServlet {
        @Override public void doGet(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doGet(req, resp);
        }
    }

    private RecommendationDAO mockDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() {
        mockDAO = mock(RecommendationDAO.class);
        servlet = new Wrapper();
        servlet.setRecommendationDAO(mockDAO);
        req  = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
    }

    @Test
    @DisplayName("anonymous user gets trending results")
    void trendingForAnonymous() throws Exception {
        SearchResultItem item = new SearchResultItem(
                2L, "Phone", "Electronics", BigDecimal.valueOf(99),
                Instant.parse("2026-12-31T00:00:00Z"), "seller", null);
        when(mockDAO.trending(eq(8), eq(Collections.emptySet()), isNull()))
                .thenReturn(java.util.List.of(item));

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        JsonNode body = ApiTestSupport.parse(sw);
        verify(resp).setStatus(200);
        assertFalse(body.get("personalised").asBoolean());
        assertTrue(body.get("results").isArray());
    }

    @Test
    @DisplayName("logged-in buyer gets personalised results")
    void personalisedForBuyer() throws Exception {
        var session = ApiTestSupport.newBuyerSession(5);
        ApiTestSupport.withBearer(req, session);
        when(mockDAO.recommendForUser(5, 8)).thenReturn(Collections.emptyList());

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        JsonNode body = ApiTestSupport.parse(sw);
        verify(resp).setStatus(200);
        assertTrue(body.get("personalised").asBoolean());
    }
}
