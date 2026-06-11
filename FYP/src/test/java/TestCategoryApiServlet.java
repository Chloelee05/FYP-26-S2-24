import com.auction.dao.CategoryDAO;
import com.auction.model.admin.Category;
import com.auction.servlet.api.CategoryApiServlet;
import com.auction.test.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("CategoryApiServlet")
class TestCategoryApiServlet {

    private static class Wrapper extends CategoryApiServlet {
        @Override public void doGet(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doGet(req, resp);
        }
    }

    private CategoryDAO mockDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() {
        mockDAO = mock(CategoryDAO.class);
        servlet = new Wrapper();
        servlet.setCategoryDAO(mockDAO);
        req  = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
    }

    @Test
    @DisplayName("returns active categories (public)")
    void listCategories() throws Exception {
        Category cat = new Category(1, "Electronics", "Electronics items", 0,
                "electronics", false, null, 3);
        when(mockDAO.listActive()).thenReturn(List.of(cat));

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        JsonNode body = ApiTestSupport.parse(sw);
        verify(resp).setStatus(200);
        assertTrue(body.isArray());
        assertEquals("Electronics", body.get(0).get("name").asText());
    }
}
