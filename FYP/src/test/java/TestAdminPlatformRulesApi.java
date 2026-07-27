import com.auction.dao.PlatformRulesDAO;
import com.auction.model.admin.PlatformRules;
import com.auction.servlet.api.AdminApiServlet;
import com.auction.test.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the admin platform-rules API (SCRUM-67 / SCRUM-69):
 * {@code GET /api/admin/rules} and {@code POST /api/admin/rules}.
 */
@DisplayName("AdminApiServlet — platform auction rules")
class TestAdminPlatformRulesApi {

    private static class Wrapper extends AdminApiServlet {
        @Override public void doGet(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doGet(req, resp);
        }
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doPost(req, resp);
        }
    }

    private static final PlatformRules STORED =
            new PlatformRules(new BigDecimal("2.50"), 14, Instant.parse("2026-07-01T10:15:00Z"), 7);

    private PlatformRulesDAO mockDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() {
        mockDAO = mock(PlatformRulesDAO.class);
        when(mockDAO.get()).thenReturn(STORED);
        servlet = new Wrapper();
        servlet.setPlatformRulesDAO(mockDAO);
        req  = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
        when(req.getPathInfo()).thenReturn("/rules");
    }

    private void asAdmin() {
        ApiTestSupport.withBearer(req, ApiTestSupport.newAdminSession(7));
    }

    @Nested
    @DisplayName("access control")
    class AccessControl {

        @Test
        @DisplayName("anonymous GET → 401")
        void anonymousGet() throws Exception {
            ApiTestSupport.bindJsonWriter(resp);
            servlet.doGet(req, resp);
            verify(resp).setStatus(401);
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("buyer GET → 403")
        void buyerGet() throws Exception {
            ApiTestSupport.withBearer(req, ApiTestSupport.newBuyerSession(3));
            ApiTestSupport.bindJsonWriter(resp);
            servlet.doGet(req, resp);
            verify(resp).setStatus(403);
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("seller POST → 403 and nothing is written")
        void sellerPost() throws Exception {
            ApiTestSupport.withBearer(req, ApiTestSupport.newSellerSession(4));
            when(req.getParameter("minBidIncrement")).thenReturn("5.00");
            ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);
            verify(resp).setStatus(403);
            verify(mockDAO, never()).update(any(), anyInt(), any());
        }
    }

    @Nested
    @DisplayName("GET /api/admin/rules")
    class GetRules {

        @Test
        @DisplayName("returns the stored rules")
        void returnsRules() throws Exception {
            asAdmin();
            StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
            servlet.doGet(req, resp);

            verify(resp).setStatus(200);
            JsonNode body = ApiTestSupport.parse(sw);
            assertEquals(0, new BigDecimal("2.50").compareTo(body.get("minBidIncrement").decimalValue()));
            assertEquals(14, body.get("maxAuctionDurationDays").asInt());
            assertEquals(7, body.get("updatedBy").asInt());
        }

        @Test
        @DisplayName("reports a load failure as 500")
        void loadFailure() throws Exception {
            asAdmin();
            when(mockDAO.get()).thenThrow(new RuntimeException("no table"));
            ApiTestSupport.bindJsonWriter(resp);
            servlet.doGet(req, resp);
            verify(resp).setStatus(500);
        }
    }

    @Nested
    @DisplayName("POST /api/admin/rules")
    class UpdateRules {

        @Test
        @DisplayName("saves both rules and echoes the stored values")
        void savesBoth() throws Exception {
            asAdmin();
            when(req.getParameter("minBidIncrement")).thenReturn("5.00");
            when(req.getParameter("maxAuctionDurationDays")).thenReturn("21");
            PlatformRules saved = new PlatformRules(new BigDecimal("5.00"), 21, Instant.now(), 7);
            when(mockDAO.update(new BigDecimal("5.00"), 21, 7)).thenReturn(saved);

            StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
            verify(mockDAO).update(new BigDecimal("5.00"), 21, 7);
            JsonNode body = ApiTestSupport.parse(sw);
            assertEquals(0, new BigDecimal("5.00")
                    .compareTo(body.get("rules").get("minBidIncrement").decimalValue()));
            assertEquals(21, body.get("rules").get("maxAuctionDurationDays").asInt());
        }

        @Test
        @DisplayName("omitted fields keep their current value")
        void partialUpdate() throws Exception {
            asAdmin();
            when(req.getParameter("maxAuctionDurationDays")).thenReturn("60");
            when(mockDAO.update(any(), anyInt(), any()))
                    .thenReturn(new PlatformRules(new BigDecimal("2.50"), 60, Instant.now(), 7));

            ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
            // increment untouched: the stored 2.50 is written back alongside the new duration
            verify(mockDAO).update(new BigDecimal("2.50"), 60, 7);
        }

        @Test
        @DisplayName("no parameters → 400")
        void noParameters() throws Exception {
            asAdmin();
            ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);
            verify(resp).setStatus(400);
            verify(mockDAO, never()).update(any(), anyInt(), any());
        }

        @Test
        @DisplayName("non-numeric increment → 400")
        void nonNumericIncrement() throws Exception {
            asAdmin();
            when(req.getParameter("minBidIncrement")).thenReturn("abc");
            ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);
            verify(resp).setStatus(400);
            verify(mockDAO, never()).update(any(), anyInt(), any());
        }

        @Test
        @DisplayName("non-numeric duration → 400")
        void nonNumericDuration() throws Exception {
            asAdmin();
            when(req.getParameter("maxAuctionDurationDays")).thenReturn("two weeks");
            ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);
            verify(resp).setStatus(400);
            verify(mockDAO, never()).update(any(), anyInt(), any());
        }

        @Test
        @DisplayName("increment below one cent → 400 with the rule message")
        void incrementTooSmall() throws Exception {
            asAdmin();
            when(req.getParameter("minBidIncrement")).thenReturn("0");
            StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            assertTrue(ApiTestSupport.parse(sw).get("error").asText().contains("at least"));
            verify(mockDAO, never()).update(any(), anyInt(), any());
        }

        @Test
        @DisplayName("duration beyond a year → 400 with the rule message")
        void durationTooLong() throws Exception {
            asAdmin();
            when(req.getParameter("maxAuctionDurationDays"))
                    .thenReturn(String.valueOf(PlatformRules.MAX_ALLOWED_DURATION_DAYS + 1));
            StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            assertTrue(ApiTestSupport.parse(sw).get("error").asText().contains("cannot exceed"));
            verify(mockDAO, never()).update(any(), anyInt(), any());
        }

        @Test
        @DisplayName("a save failure is reported as 500")
        void saveFailure() throws Exception {
            asAdmin();
            when(req.getParameter("minBidIncrement")).thenReturn("3.00");
            when(mockDAO.update(any(), anyInt(), any())).thenThrow(new RuntimeException("db down"));

            ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);
            verify(resp).setStatus(500);
        }
    }
}
