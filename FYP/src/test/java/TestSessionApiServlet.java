import com.auction.dao.UserDAO;
import com.auction.model.Role;
import com.auction.model.User;
import com.auction.servlet.api.SessionApiServlet;
import com.auction.test.ApiTestSupport;
import com.auction.util.AuthSession;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("SessionApiServlet")
class TestSessionApiServlet {

    private static class Wrapper extends SessionApiServlet {
        @Override public void doGet(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doGet(req, resp);
        }
    }

    private UserDAO mockDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() {
        mockDAO = mock(UserDAO.class);
        servlet = new Wrapper();
        servlet.setUserDAO(mockDAO);
        req  = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
    }

    @Test
    @DisplayName("no token → 401")
    void unauthorized() throws Exception {
        ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);
        verify(resp).setStatus(401);
    }

    @Test
    @DisplayName("valid session returns user profile")
    void success() throws Exception {
        AuthSession s = ApiTestSupport.newBuyerSession(3);
        ApiTestSupport.withBearer(req, s);

        User user = new User("alice", "alice@email.com", "hash", Role.BUYER);
        user.setId(3);
        when(mockDAO.getUserById(3)).thenReturn(user);

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        JsonNode body = ApiTestSupport.parse(sw);
        verify(resp).setStatus(200);
        assertEquals("alice", body.get("username").asText());
        assertEquals("BUYER", body.get("role").asText());
    }
}
