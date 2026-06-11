import com.auction.dao.NotificationDAO;
import com.auction.model.Notification;
import com.auction.servlet.api.NotificationApiServlet;
import com.auction.test.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("NotificationApiServlet")
class TestNotificationApiServlet {

    private static class Wrapper extends NotificationApiServlet {
        @Override public void doGet(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doGet(req, resp);
        }
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doPost(req, resp);
        }
    }

    private NotificationDAO mockDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() {
        mockDAO = mock(NotificationDAO.class);
        servlet = new Wrapper();
        servlet.setNotificationDAO(mockDAO);
        req  = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
    }

    @Test
    @DisplayName("GET requires auth")
    void unauthorized() throws Exception {
        ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);
        verify(resp).setStatus(401);
    }

    @Test
    @DisplayName("GET returns notifications and unread count")
    void listNotifications() throws Exception {
        var s = ApiTestSupport.newBuyerSession(4);
        ApiTestSupport.withBearer(req, s);
        Notification n = new Notification(1L, "OUTBID", "You were outbid",
                "/auction/1", false, Instant.now());
        when(mockDAO.listForUser(4, 30)).thenReturn(List.of(n));
        when(mockDAO.countUnread(4)).thenReturn(1);

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        JsonNode body = ApiTestSupport.parse(sw);
        verify(resp).setStatus(200);
        assertEquals(1, body.get("unreadCount").asInt());
    }
}
