import com.auction.dao.UserDAO;
import com.auction.model.Role;
import com.auction.model.User;
import com.auction.servlet.api.TwoFactorApiServlet;
import com.auction.test.ApiTestSupport;
import com.auction.util.AuthSession;
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

@DisplayName("TwoFactorApiServlet")
class TestTwoFactorApiServlet {

    private static class Wrapper extends TwoFactorApiServlet {
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doPost(req, resp);
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
    @DisplayName("verify-login without pending session → 401")
    void verifyLoginNoSession() throws Exception {
        when(req.getPathInfo()).thenReturn("/verify-login");
        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(401);
    }

    @Test
    @DisplayName("verify-login with wrong OTP → 401")
    void verifyLoginWrongOtp() throws Exception {
        AuthSession pending = TokenStore.getInstance().create();
        pending.setAttribute("awaitingTwoFactor", true);
        pending.setAttribute("pendingUserId", 5);
        pending.setAttribute("pending2faOtp", "123456");
        ApiTestSupport.withBearer(req, pending);

        when(req.getPathInfo()).thenReturn("/verify-login");
        when(req.getParameter("otpCode")).thenReturn("000000");

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(401);
    }

    @Test
    @DisplayName("verify-login success creates authenticated session")
    void verifyLoginSuccess() throws Exception {
        AuthSession pending = TokenStore.getInstance().create();
        pending.setAttribute("awaitingTwoFactor", true);
        pending.setAttribute("pendingUserId", 5);
        pending.setAttribute("pending2faOtp", "654321");
        ApiTestSupport.withBearer(req, pending);

        User user = new User("alice", "alice@email.com", "hash", Role.BUYER);
        user.setId(5);
        when(mockDAO.getUserById(5)).thenReturn(user);

        when(req.getPathInfo()).thenReturn("/verify-login");
        when(req.getParameter("otpCode")).thenReturn("654321");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        JsonNode body = ApiTestSupport.parse(sw);
        verify(resp).setStatus(200);
        assertNotNull(body.get("token"));
        assertEquals("alice", body.get("username").asText());
    }
}
