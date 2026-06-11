import com.auction.dao.UserDAO;
import com.auction.model.Role;
import com.auction.model.Status;
import com.auction.model.User;
import com.auction.servlet.api.AuthApiServlet;
import com.auction.test.ApiTestSupport;
import com.auction.util.AuthSession;
import com.auction.util.OtpStore;
import com.auction.util.SecurityUtil;
import com.auction.util.TokenStore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("AuthApiServlet")
class TestAuthApiServlet {

    private static class Wrapper extends AuthApiServlet {
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doPost(req, resp);
        }
    }

    private UserDAO mockDAO;
    private OtpStore otpStore;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() {
        mockDAO  = mock(UserDAO.class);
        otpStore = new OtpStore();
        servlet  = new Wrapper();
        servlet.setUserDAO(mockDAO);
        servlet.setOtpStore(otpStore);
        req  = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
    }

    @Test
    @DisplayName("login missing email → 400")
    void loginMissingEmail() throws Exception {
        when(req.getPathInfo()).thenReturn("/login");
        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        JsonNode body = ApiTestSupport.parse(sw);
        verify(resp).setStatus(400);
        assertTrue(body.get("error").asText().contains("Email"));
    }

    @Test
    @DisplayName("login invalid credentials → 401")
    void loginInvalid() throws Exception {
        when(req.getPathInfo()).thenReturn("/login");
        when(req.getParameter("email")).thenReturn("user@email.com");
        when(req.getParameter("password")).thenReturn("Password1!");
        when(mockDAO.getUserByEmail("user@email.com")).thenReturn(null);

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(401);
    }

    @Test
    @DisplayName("login success returns token")
    void loginSuccess() throws Exception {
        User user = new User("alice", "alice@email.com",
                SecurityUtil.hashPassword("Password1!"), Role.BUYER);
        user.setId(7);
        user.setStatusId(Status.ACTIVE.getId());

        when(req.getPathInfo()).thenReturn("/login");
        when(req.getParameter("email")).thenReturn("alice@email.com");
        when(req.getParameter("password")).thenReturn("Password1!");
        when(mockDAO.getUserByEmail("alice@email.com")).thenReturn(user);

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        JsonNode body = ApiTestSupport.parse(sw);
        verify(resp).setStatus(200);
        assertNotNull(body.get("token"));
        assertEquals("alice", body.get("username").asText());
        assertEquals("BUYER", body.get("role").asText());
    }

    @Test
    @DisplayName("pending account → 403")
    void loginPending() throws Exception {
        User user = new User("bob", "bob@email.com",
                SecurityUtil.hashPassword("Password1!"), Role.BUYER);
        user.setStatusId(Status.PENDING.getId());
        when(req.getPathInfo()).thenReturn("/login");
        when(req.getParameter("email")).thenReturn("bob@email.com");
        when(req.getParameter("password")).thenReturn("Password1!");
        when(mockDAO.getUserByEmail("bob@email.com")).thenReturn(user);

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(403);
    }

    @Test
    @DisplayName("logout removes bearer token")
    void logout() throws Exception {
        AuthSession session = ApiTestSupport.newBuyerSession(1);
        when(req.getPathInfo()).thenReturn("/logout");
        ApiTestSupport.withBearer(req, session);

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(200);
        assertNull(TokenStore.getInstance().get(session.getToken()));
    }

    @Test
    @DisplayName("register duplicate email → 409")
    void registerDuplicateEmail() throws Exception {
        when(req.getPathInfo()).thenReturn("/register");
        when(req.getParameter("username")).thenReturn("newuser");
        when(req.getParameter("email")).thenReturn("taken@email.com");
        when(req.getParameter("password")).thenReturn("Password1!");
        when(req.getParameter("confirmPassword")).thenReturn("Password1!");
        when(req.getParameter("role")).thenReturn("BUYER");
        when(req.getParameter("termsAccept")).thenReturn("true");
        when(mockDAO.checkEmail("taken@email.com")).thenReturn(true);

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(409);
    }
}
