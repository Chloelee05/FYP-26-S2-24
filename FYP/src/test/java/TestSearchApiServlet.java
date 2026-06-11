import com.auction.dao.SearchDAO;
import com.auction.model.SearchResultItem;
import com.auction.servlet.api.SearchApiServlet;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("SearchApiServlet")
class TestSearchApiServlet {

    private static class Wrapper extends SearchApiServlet {
        @Override public void doGet(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doGet(req, resp);
        }
    }

    private SearchDAO mockDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() {
        mockDAO = mock(SearchDAO.class);
        servlet = new Wrapper();
        servlet.setSearchDAO(mockDAO);
        req  = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
    }

    @Test
    @DisplayName("returns paginated search results")
    void searchSuccess() throws Exception {
        SearchResultItem item = new SearchResultItem(
                1L, "Laptop", "Electronics", BigDecimal.TEN,
                Instant.parse("2026-12-31T00:00:00Z"), "seller1", null);
        when(mockDAO.search(eq("laptop"), isNull(), any(), any(), eq(1), eq(12)))
                .thenReturn(List.of(item));
        when(mockDAO.count(eq("laptop"), isNull(), any())).thenReturn(1);
        when(req.getParameter("q")).thenReturn("laptop");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        JsonNode body = ApiTestSupport.parse(sw);
        verify(resp).setStatus(200);
        assertEquals(1, body.get("total").asInt());
        assertTrue(body.get("results").isArray());
    }

    @Test
    @DisplayName("empty query still searches")
    void emptyQuery() throws Exception {
        when(mockDAO.search(eq(""), isNull(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(mockDAO.count(eq(""), isNull(), any())).thenReturn(0);

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);
        verify(resp).setStatus(200);
    }
}
