import com.auction.dao.AnnouncementDAO;
import com.auction.model.admin.Announcement;
import com.auction.servlet.api.AdminAnnouncementApiServlet;
import com.auction.test.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.StringWriter;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the admin broadcast API:
 * {@code GET /api/admin/announcements} and {@code POST /api/admin/announcements}.
 */
@DisplayName("AdminAnnouncementApiServlet — system-wide announcements")
class TestAdminAnnouncementApi {

    private static class Wrapper extends AdminAnnouncementApiServlet {
        @Override public void doGet(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doGet(req, resp);
        }
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doPost(req, resp);
        }
    }

    private static final int ADMIN_ID = 7;

    private AnnouncementDAO mockDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() {
        mockDAO = mock(AnnouncementDAO.class);
        servlet = new Wrapper();
        servlet.setAnnouncementDAO(mockDAO);
        req  = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
    }

    private void asAdmin() {
        ApiTestSupport.withBearer(req, ApiTestSupport.newAdminSession(ADMIN_ID));
    }

    /** A valid maintenance notice on the request. */
    private void validPost() {
        when(req.getParameter("title")).thenReturn("Scheduled maintenance");
        when(req.getParameter("message")).thenReturn("Bidding pauses 02:00-04:00 SGT on 30 July.");
    }

    /** Echoes whatever draft the servlet broadcast back as a persisted announcement. */
    private void daoEchoes(int recipients) {
        when(mockDAO.broadcast(any())).thenAnswer(inv ->
                ((Announcement) inv.getArgument(0)).stored(99L, Instant.parse("2026-07-27T09:00:00Z"), recipients));
    }

    private Announcement captureBroadcast() {
        ArgumentCaptor<Announcement> captor = ArgumentCaptor.forClass(Announcement.class);
        verify(mockDAO).broadcast(captor.capture());
        return captor.getValue();
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
        @DisplayName("anonymous POST → 401 and nothing is broadcast")
        void anonymousPost() throws Exception {
            validPost();
            ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);
            verify(resp).setStatus(401);
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("buyer POST → 403 and nothing is broadcast")
        void buyerPost() throws Exception {
            ApiTestSupport.withBearer(req, ApiTestSupport.newBuyerSession(3));
            validPost();
            ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);
            verify(resp).setStatus(403);
            verify(mockDAO, never()).broadcast(any());
        }

        @Test
        @DisplayName("seller GET → 403")
        void sellerGet() throws Exception {
            ApiTestSupport.withBearer(req, ApiTestSupport.newSellerSession(4));
            ApiTestSupport.bindJsonWriter(resp);
            servlet.doGet(req, resp);
            verify(resp).setStatus(403);
            verifyNoInteractions(mockDAO);
        }
    }

    @Nested
    @DisplayName("POST /api/admin/announcements")
    class Broadcast {

        @Test
        @DisplayName("sends to all active users and reports the reach")
        void sendsToAll() throws Exception {
            asAdmin();
            validPost();
            daoEchoes(130);

            StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
            JsonNode body = ApiTestSupport.parse(sw);
            assertEquals(130, body.get("recipientCount").asInt());
            assertTrue(body.get("message").asText().contains("130 users"));
            assertEquals(99L, body.get("announcement").get("id").asLong());
            assertEquals("ALL", body.get("announcement").get("audience").asText());
            assertEquals("INFO", body.get("announcement").get("severity").asText());
        }

        @Test
        @DisplayName("defaults to the whole platform at INFO severity, attributed to the admin")
        void defaults() throws Exception {
            asAdmin();
            validPost();
            daoEchoes(5);
            ApiTestSupport.bindJsonWriter(resp);

            servlet.doPost(req, resp);

            Announcement draft = captureBroadcast();
            assertEquals(Announcement.Audience.ALL, draft.getAudience());
            assertEquals(Announcement.Severity.INFO, draft.getSeverity());
            assertEquals("Scheduled maintenance", draft.getTitle());
            assertNull(draft.getLink());
            assertEquals(Integer.valueOf(ADMIN_ID), draft.getCreatedBy());
        }

        @Test
        @DisplayName("honours audience, severity and link")
        void honoursOptions() throws Exception {
            asAdmin();
            validPost();
            when(req.getParameter("audience")).thenReturn("sellers");
            when(req.getParameter("severity")).thenReturn("critical");
            when(req.getParameter("link")).thenReturn("/seller/dashboard");
            daoEchoes(40);

            StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
            Announcement draft = captureBroadcast();
            assertEquals(Announcement.Audience.SELLERS, draft.getAudience());
            assertEquals(Announcement.Severity.CRITICAL, draft.getSeverity());
            assertEquals("/seller/dashboard", draft.getLink());
            assertTrue(ApiTestSupport.parse(sw).get("message").asText().contains("sellers"));
        }

        @Test
        @DisplayName("missing title → 400")
        void missingTitle() throws Exception {
            asAdmin();
            when(req.getParameter("message")).thenReturn("Body only.");
            StringWriter sw = ApiTestSupport.bindJsonWriter(resp);

            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            assertTrue(ApiTestSupport.parse(sw).get("error").asText().contains("title is required"));
            verify(mockDAO, never()).broadcast(any());
        }

        @Test
        @DisplayName("missing message → 400")
        void missingMessage() throws Exception {
            asAdmin();
            when(req.getParameter("title")).thenReturn("Heads up");
            StringWriter sw = ApiTestSupport.bindJsonWriter(resp);

            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            assertTrue(ApiTestSupport.parse(sw).get("error").asText().contains("message is required"));
            verify(mockDAO, never()).broadcast(any());
        }

        @Test
        @DisplayName("an over-long title → 400")
        void titleTooLong() throws Exception {
            asAdmin();
            StringBuilder longTitle = new StringBuilder();
            for (int i = 0; i <= Announcement.TITLE_MAX_LENGTH; i++) longTitle.append('a');
            when(req.getParameter("title")).thenReturn(longTitle.toString());
            when(req.getParameter("message")).thenReturn("Body.");
            ApiTestSupport.bindJsonWriter(resp);

            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            verify(mockDAO, never()).broadcast(any());
        }

        @Test
        @DisplayName("an external link → 400, so a broadcast cannot become an open redirect")
        void externalLinkRejected() throws Exception {
            asAdmin();
            validPost();
            when(req.getParameter("link")).thenReturn("https://evil.example.com");
            StringWriter sw = ApiTestSupport.bindJsonWriter(resp);

            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            assertTrue(ApiTestSupport.parse(sw).get("error").asText().contains("in-app path"));
            verify(mockDAO, never()).broadcast(any());
        }

        @Test
        @DisplayName("an unknown audience → 400 listing the accepted values")
        void unknownAudience() throws Exception {
            asAdmin();
            validPost();
            when(req.getParameter("audience")).thenReturn("everyone");
            StringWriter sw = ApiTestSupport.bindJsonWriter(resp);

            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            assertTrue(ApiTestSupport.parse(sw).get("error").asText().contains("BUYERS"));
            verify(mockDAO, never()).broadcast(any());
        }

        @Test
        @DisplayName("an unknown severity → 400 listing the accepted values")
        void unknownSeverity() throws Exception {
            asAdmin();
            validPost();
            when(req.getParameter("severity")).thenReturn("panic");
            StringWriter sw = ApiTestSupport.bindJsonWriter(resp);

            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            assertTrue(ApiTestSupport.parse(sw).get("error").asText().contains("CRITICAL"));
            verify(mockDAO, never()).broadcast(any());
        }

        @Test
        @DisplayName("a delivery failure is reported as 500")
        void broadcastFailure() throws Exception {
            asAdmin();
            validPost();
            when(mockDAO.broadcast(any())).thenThrow(new RuntimeException("db down"));

            StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);

            verify(resp).setStatus(500);
            assertTrue(ApiTestSupport.parse(sw).get("error").asText().contains("Could not send"));
        }

        @Test
        @DisplayName("no email copy is fetched unless the admin asks for one")
        void noEmailByDefault() throws Exception {
            asAdmin();
            validPost();
            daoEchoes(10);
            ApiTestSupport.bindJsonWriter(resp);

            servlet.doPost(req, resp);

            verify(mockDAO, never()).recipientEmails(any());
        }

        @Test
        @DisplayName("sendEmail=true collects the recipient addresses")
        void emailCopyRequested() throws Exception {
            asAdmin();
            validPost();
            when(req.getParameter("sendEmail")).thenReturn("true");
            when(mockDAO.recipientEmails(Announcement.Audience.ALL))
                    .thenReturn(List.of("a@example.com", "b@example.com"));
            daoEchoes(2);

            ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
            verify(mockDAO).recipientEmails(Announcement.Audience.ALL);
        }

        @Test
        @DisplayName("a failure to email does not fail an announcement already delivered in-app")
        void emailFailureDoesNotFailBroadcast() throws Exception {
            asAdmin();
            validPost();
            when(req.getParameter("sendEmail")).thenReturn("true");
            when(mockDAO.recipientEmails(any())).thenThrow(new RuntimeException("smtp down"));
            daoEchoes(3);

            StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
            JsonNode body = ApiTestSupport.parse(sw);
            assertEquals(3, body.get("recipientCount").asInt());
            assertEquals(0, body.get("emailedCount").asInt());
        }
    }

    @Nested
    @DisplayName("GET /api/admin/announcements")
    class History {

        @Test
        @DisplayName("returns past announcements with their reach")
        void returnsHistory() throws Exception {
            asAdmin();
            Announcement past = new Announcement(12L, "Policy update",
                    "Buyer protection now covers 30 days.", Announcement.Audience.BUYERS,
                    Announcement.Severity.INFO, "/profile", ADMIN_ID, "adminjane",
                    Instant.parse("2026-07-20T02:00:00Z"), 84);
            when(mockDAO.listRecent(anyInt())).thenReturn(List.of(past));

            StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
            servlet.doGet(req, resp);

            verify(resp).setStatus(200);
            JsonNode body = ApiTestSupport.parse(sw);
            assertEquals(1, body.size());
            assertEquals("Policy update", body.get(0).get("title").asText());
            assertEquals("BUYERS", body.get(0).get("audience").asText());
            assertEquals(84, body.get(0).get("recipientCount").asInt());
            assertEquals("adminjane", body.get(0).get("createdByName").asText());
        }

        @Test
        @DisplayName("an empty history is an empty list, not an error")
        void emptyHistory() throws Exception {
            asAdmin();
            when(mockDAO.listRecent(anyInt())).thenReturn(List.of());

            StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
            servlet.doGet(req, resp);

            verify(resp).setStatus(200);
            assertEquals(0, ApiTestSupport.parse(sw).size());
        }

        @Test
        @DisplayName("a load failure is reported as 500")
        void loadFailure() throws Exception {
            asAdmin();
            when(mockDAO.listRecent(anyInt())).thenThrow(new RuntimeException("no table"));

            ApiTestSupport.bindJsonWriter(resp);
            servlet.doGet(req, resp);

            verify(resp).setStatus(500);
        }
    }
}
