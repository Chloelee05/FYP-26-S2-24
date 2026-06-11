import com.auction.dao.ProfileActivityDAO;
import com.auction.dao.UserDAO;
import com.auction.model.Role;
import com.auction.model.User;
import com.auction.servlet.api.AccountApiServlet;
import com.auction.test.ApiTestSupport;
import com.auction.util.SecurityUtil;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("AccountApiServlet")
class TestAccountApiServlet {

    private static class Wrapper extends AccountApiServlet {
        @Override public void doGet(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doGet(req, resp);
        }
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doPost(req, resp);
        }
    }

    private UserDAO mockUserDAO;
    private ProfileActivityDAO mockActDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() {
        mockUserDAO = mock(UserDAO.class);
        mockActDAO  = mock(ProfileActivityDAO.class);
        servlet = new Wrapper();
        servlet.setUserDAO(mockUserDAO);
        servlet.setProfileActivityDAO(mockActDAO);
        req  = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
    }

    @Test
    @DisplayName("GET profile requires auth")
    void unauthorized() throws Exception {
        when(req.getPathInfo()).thenReturn("/");
        ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);
        verify(resp).setStatus(401);
    }

    @Test
    @DisplayName("GET profile returns decrypted fields")
    void profileSuccess() throws Exception {
        var s = ApiTestSupport.newBuyerSession(8);
        ApiTestSupport.withBearer(req, s);
        when(req.getPathInfo()).thenReturn("/");

        User user = new User("alice", "alice@email.com", "hash", Role.BUYER);
        user.setId(8);
        user.setPhoneEncrypted(SecurityUtil.encrypt("+6512345678"));
        user.setAddressEncrypted(SecurityUtil.encrypt("1 Orchard Rd"));
        when(mockUserDAO.getUserById(8)).thenReturn(user);

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        JsonNode body = ApiTestSupport.parse(sw);
        verify(resp).setStatus(200);
        assertEquals("alice", body.get("username").asText());
        assertEquals("+6512345678", body.get("phone").asText());
    }

    @Test
    @DisplayName("POST delete without confirm → 400")
    void deleteMissingConfirm() throws Exception {
        var s = ApiTestSupport.newBuyerSession(8);
        ApiTestSupport.withBearer(req, s);
        when(req.getPathInfo()).thenReturn("/delete");

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(400);
        verify(mockUserDAO, never()).deleteAccount(anyInt());
    }
}
