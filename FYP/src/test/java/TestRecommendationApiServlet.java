import com.auction.dao.RecommendationDAO;
import com.auction.model.RecommendationProvenance;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doPost(req, resp);
        }
    }

    private RecommendationDAO mockDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() {
        mockDAO = mock(RecommendationDAO.class);
        // The servlet reads getSettings().itemsShown to pick a default limit; without
        // this stub the mock returns null and every request NPEs.
        when(mockDAO.getSettings()).thenReturn(new RecommendationDAO.Settings(8, 0.1));
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

    private static SearchResultItem itemWith(long id, RecommendationProvenance.Reason reason, String text) {
        SearchResultItem item = new SearchResultItem(
                id, "Pokemon card lot", "Collectibles", BigDecimal.valueOf(99),
                Instant.parse("2026-12-31T00:00:00Z"), "seller", null);
        item.setWhy(new RecommendationProvenance(reason, text));
        return item;
    }

    @Test
    @DisplayName("logged-in buyer gets personalised results")
    void personalisedForBuyer() throws Exception {
        var session = ApiTestSupport.newBuyerSession(5);
        ApiTestSupport.withBearer(req, session);
        when(mockDAO.recommendForUser(5, 8)).thenReturn(List.of(
                itemWith(2L, RecommendationProvenance.Reason.PEER_BIDS, "Buyers who bid on your items also bid on this"),
                itemWith(3L, RecommendationProvenance.Reason.TRENDING, "Trending — collecting the most bids today")));

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        JsonNode body = ApiTestSupport.parse(sw);
        verify(resp).setStatus(200);
        assertTrue(body.get("personalised").asBoolean());
    }

    @Test
    @DisplayName("a signed-in buyer with no history is not told the list is personalised")
    void notPersonalisedWhenEveryItemIsTrendingFiller() throws Exception {
        // A brand new account falls through every personalised stage to trending filler.
        // Claiming personalisation here would contradict the reason printed on each card.
        var session = ApiTestSupport.newBuyerSession(5);
        ApiTestSupport.withBearer(req, session);
        when(mockDAO.recommendForUser(5, 8)).thenReturn(List.of(
                itemWith(2L, RecommendationProvenance.Reason.TRENDING, "Trending — collecting the most bids today"),
                itemWith(3L, RecommendationProvenance.Reason.TRENDING, "Trending — collecting the most bids today")));

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        JsonNode body = ApiTestSupport.parse(sw);
        verify(resp).setStatus(200);
        assertFalse(body.get("personalised").asBoolean());
        assertEquals(2, body.get("results").size());
    }

    @Test
    @DisplayName("an empty recommendation list is not personalised")
    void notPersonalisedWhenEmpty() throws Exception {
        var session = ApiTestSupport.newBuyerSession(5);
        ApiTestSupport.withBearer(req, session);
        when(mockDAO.recommendForUser(5, 8)).thenReturn(Collections.emptyList());

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        verify(resp).setStatus(200);
        assertFalse(ApiTestSupport.parse(sw).get("personalised").asBoolean());
    }

    @Test
    @DisplayName("a one-character search keyword is rejected rather than recorded")
    void rejectsTooShortSearchKeyword() throws Exception {
        var session = ApiTestSupport.newBuyerSession(5);
        ApiTestSupport.withBearer(req, session);
        when(req.getPathInfo()).thenReturn("/search-keyword");
        when(req.getParameter("q")).thenReturn("a");

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        verify(mockDAO, never()).recordSearchKeyword(any(), anyString());
    }

    @Test
    @DisplayName("/trending is never personalised, even for a signed-in user")
    void trendingIsNeverPersonalised() throws Exception {
        // The home page needs a genuine "what's hot" list next to personalised picks,
        // so this route must ignore the session entirely.
        var session = ApiTestSupport.newBuyerSession(5);
        ApiTestSupport.withBearer(req, session);
        when(req.getPathInfo()).thenReturn("/trending");
        when(mockDAO.trending(eq(8), eq(Collections.emptySet()), isNull()))
                .thenReturn(Collections.emptyList());

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        JsonNode body = ApiTestSupport.parse(sw);
        verify(resp).setStatus(200);
        assertFalse(body.get("personalised").asBoolean());
        verify(mockDAO).trending(eq(8), eq(Collections.emptySet()), isNull());
        verify(mockDAO, never()).recommendForUser(anyInt(), anyInt());
    }

    @Test
    @DisplayName("/trending honours an explicit limit")
    void trendingHonoursLimit() throws Exception {
        when(req.getPathInfo()).thenReturn("/trending");
        when(req.getParameter("limit")).thenReturn("4");
        when(mockDAO.trending(eq(4), eq(Collections.emptySet()), isNull()))
                .thenReturn(Collections.emptyList());

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        verify(resp).setStatus(200);
        verify(mockDAO).trending(eq(4), eq(Collections.emptySet()), isNull());
    }

    @Test
    @DisplayName("each result carries the provenance that explains it")
    void resultsCarryProvenance() throws Exception {
        SearchResultItem item = new SearchResultItem(
                2L, "Pokemon card lot", "Collectibles", BigDecimal.valueOf(99),
                Instant.parse("2026-12-31T00:00:00Z"), "seller", null);
        RecommendationProvenance why =
                new RecommendationProvenance(RecommendationProvenance.Reason.SEARCH_KEYWORD,
                        "Matches your search for “pokemon”");
        why.setClickCount(12);
        why.setDistinctClickers(4);
        why.setClickedByMasked("b***2");
        why.setKeywords(List.of("pokemon"));
        item.setWhy(why);
        when(mockDAO.trending(eq(8), eq(Collections.emptySet()), isNull()))
                .thenReturn(java.util.List.of(item));

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        JsonNode first = ApiTestSupport.parse(sw).get("results").get(0).get("why");
        assertEquals("SEARCH_KEYWORD", first.get("reasonCode").asText());
        assertEquals("Matches your search for “pokemon”", first.get("reason").asText());
        assertEquals(12, first.get("clickCount").asInt());
        assertEquals("b***2", first.get("clickedByMasked").asText());
        assertEquals("pokemon", first.get("keywords").get(0).asText());
        verify(mockDAO).attachProvenance(anyList(), isNull());
    }

    @Test
    @DisplayName("search results stay free of the recommendation-only provenance block")
    void provenanceOmittedWhenAbsent() throws Exception {
        SearchResultItem item = new SearchResultItem(
                2L, "Phone", "Electronics", BigDecimal.valueOf(99),
                Instant.parse("2026-12-31T00:00:00Z"), "seller", null);
        when(mockDAO.trending(eq(8), eq(Collections.emptySet()), isNull()))
                .thenReturn(java.util.List.of(item));

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        assertFalse(ApiTestSupport.parse(sw).get("results").get(0).has("why"));
    }

    @Test
    @DisplayName("a click is attributed to the keyword that surfaced the card")
    void clickCarriesKeyword() throws Exception {
        when(req.getPathInfo()).thenReturn("/events");
        when(req.getParameter("type")).thenReturn("click");
        when(req.getParameter("auctionId")).thenReturn("9");
        when(req.getParameter("keyword")).thenReturn("pokemon");

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(200);
        verify(mockDAO).recordEvent(isNull(), eq(9L), eq("CLICK"), eq("pokemon"), isNull());
    }

    @Test
    @DisplayName("a click carries the arm that produced the card")
    void clickCarriesReasonCode() throws Exception {
        when(req.getPathInfo()).thenReturn("/events");
        when(req.getParameter("type")).thenReturn("click");
        when(req.getParameter("auctionId")).thenReturn("9");
        when(req.getParameter("reasonCode")).thenReturn("PEER_BIDS");

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(200);
        verify(mockDAO).recordEvent(isNull(), eq(9L), eq("CLICK"), isNull(), eq("PEER_BIDS"));
    }

    @Test
    @DisplayName("batched impressions all carry the same arm label")
    void batchedImpressionsCarryReasonCode() throws Exception {
        when(req.getPathInfo()).thenReturn("/events");
        when(req.getParameter("type")).thenReturn("impression");
        when(req.getParameter("auctionIds")).thenReturn("9,10");
        when(req.getParameter("reasonCode")).thenReturn("TRENDING_CONTROL");

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(200);
        verify(mockDAO).recordEvent(isNull(), eq(9L), eq("IMPRESSION"), isNull(), eq("TRENDING_CONTROL"));
        verify(mockDAO).recordEvent(isNull(), eq(10L), eq("IMPRESSION"), isNull(), eq("TRENDING_CONTROL"));
    }

    @Test
    @DisplayName("the trending strip reports the window its ranking actually used")
    void trendingResponseCarriesTheWindow() throws Exception {
        when(req.getPathInfo()).thenReturn("/trending");
        when(mockDAO.getSettings()).thenReturn(new RecommendationDAO.Settings(8, 0.1, 14));
        when(mockDAO.trending(eq(8), eq(Collections.emptySet()), isNull()))
                .thenReturn(Collections.emptyList());

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        // The landing page prints this number in its subtitle, so it must not be a
        // constant in React that can drift from the setting the query used.
        assertEquals(14, ApiTestSupport.parse(sw).get("trendingWindowDays").asInt());
    }

    @Test
    @DisplayName("a searched keyword is recorded against the signed-in user")
    void recordsSearchKeyword() throws Exception {
        var session = ApiTestSupport.newBuyerSession(5);
        ApiTestSupport.withBearer(req, session);
        when(req.getPathInfo()).thenReturn("/search-keyword");
        when(req.getParameter("q")).thenReturn("pokemon");

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(200);
        verify(mockDAO).recordSearchKeyword(eq(5), eq("pokemon"));
    }

    @Test
    @DisplayName("recording a keyword requires the keyword itself")
    void rejectsBlankSearchKeyword() throws Exception {
        when(req.getPathInfo()).thenReturn("/search-keyword");

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        verify(mockDAO, never()).recordSearchKeyword(any(), anyString());
    }

    @Test
    @DisplayName("per-user attribution is refused without a session")
    void attributionRejectsAnonymous() throws Exception {
        when(req.getPathInfo()).thenReturn("/attribution");

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        verify(resp).setStatus(401);
        verify(mockDAO, never()).attributionOverview(anyInt());
    }

    @Test
    @DisplayName("per-user attribution is refused to a signed-in buyer")
    void attributionRejectsBuyer() throws Exception {
        var session = ApiTestSupport.newBuyerSession(5);
        ApiTestSupport.withBearer(req, session);
        when(req.getPathInfo()).thenReturn("/attribution");

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        verify(resp).setStatus(403);
        verify(mockDAO, never()).attributionOverview(anyInt());
        verify(mockDAO, never()).attributionDetail(anyLong(), anyInt());
    }

    @Test
    @DisplayName("an admin sees the click / keyword leaderboard, and per-auction detail")
    void attributionForAdmin() throws Exception {
        var session = ApiTestSupport.newAdminSession(1);
        ApiTestSupport.withBearer(req, session);
        when(req.getPathInfo()).thenReturn("/attribution");
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("topKeywords", List.of(Map.of("keyword", "pokemon", "searches", 3)));
        when(mockDAO.attributionOverview(25)).thenReturn(overview);

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        verify(resp).setStatus(200);
        assertEquals("pokemon",
                ApiTestSupport.parse(sw).get("topKeywords").get(0).get("keyword").asText());

        reset(resp);
        when(req.getParameter("auctionId")).thenReturn("9");
        when(mockDAO.attributionDetail(9L, 25)).thenReturn(Map.of("auctionId", 9L));

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        verify(resp).setStatus(200);
        verify(mockDAO).attributionDetail(9L, 25);
    }
}
