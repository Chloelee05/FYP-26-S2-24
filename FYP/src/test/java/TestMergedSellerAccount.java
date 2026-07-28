import com.auction.dao.UserDAO;
import com.auction.model.Role;
import com.auction.model.User;
import com.auction.servlet.api.AccountApiServlet;
import com.auction.servlet.api.SellerApiServlet;
import com.auction.test.ApiTestSupport;
import com.auction.util.AuthSession;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Buying and selling share one account: every registration creates a BUYER, who can
 * later switch on the {@code can_sell} capability. These tests pin the two halves of
 * that contract — the opt-in endpoint, and seller authorisation keying off the
 * capability rather than a SELLER role.
 */
@DisplayName("Merged buyer/seller accounts")
class TestMergedSellerAccount {

    /** Exposes the protected doPost/doGet hooks for direct invocation. */
    private static class AccountWrapper extends AccountApiServlet {
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doPost(req, resp);
        }
    }

    private static class SellerWrapper extends SellerApiServlet {
        @Override public void doGet(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doGet(req, resp);
        }
    }

    // ── User.canSell() ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("User.canSell()")
    class CanSellModel {

        @Test
        @DisplayName("plain buyer cannot sell")
        void buyerCannotSell() {
            User u = new User();
            u.setRole(Role.BUYER);
            assertFalse(u.canSell());
        }

        @Test
        @DisplayName("buyer with the capability can sell")
        void buyerWithCapabilityCanSell() {
            User u = new User();
            u.setRole(Role.BUYER);
            u.setCanSell(true);
            assertTrue(u.canSell());
        }

        @Test
        @DisplayName("legacy SELLER role still sells without the flag (un-migrated DB)")
        void legacySellerRoleStillSells() {
            User u = new User();
            u.setRole(Role.SELLER);
            assertTrue(u.canSell());
        }
    }

    // ── POST /api/account/enable-selling ──────────────────────────────────────
    @Nested
    @DisplayName("POST /api/account/enable-selling")
    class EnableSelling {

        private UserDAO mockDAO;
        private AccountWrapper servlet;
        private HttpServletRequest req;
        private HttpServletResponse resp;

        @BeforeEach
        void setUp() {
            mockDAO = mock(UserDAO.class);
            servlet = new AccountWrapper();
            servlet.setUserDAO(mockDAO);
            req  = mock(HttpServletRequest.class);
            resp = mock(HttpServletResponse.class);
            when(req.getPathInfo()).thenReturn("/enable-selling");
        }

        private User buyer(boolean canSell) {
            User u = new User();
            u.setId(7);
            u.setUsername("buyer7");
            u.setRole(Role.BUYER);
            u.setCanSell(canSell);
            return u;
        }

        @Test
        @DisplayName("unauthenticated → 401")
        void unauthenticated() throws Exception {
            ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);
            verify(resp).setStatus(401);
            verify(mockDAO, never()).enableSelling(anyInt());
        }

        @Test
        @DisplayName("buyer opting in → capability granted and session updated")
        void buyerOptsIn() throws Exception {
            AuthSession s = ApiTestSupport.newBuyerSession(7);
            ApiTestSupport.withBearer(req, s);
            when(mockDAO.getUserById(7)).thenReturn(buyer(false));
            when(mockDAO.enableSelling(7)).thenReturn(true);

            StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
            verify(mockDAO).enableSelling(7);

            JsonNode body = ApiTestSupport.parse(sw);
            assertTrue(body.get("canSell").asBoolean());
            // The live session must reflect it so the very next seller call succeeds.
            assertEquals(Boolean.TRUE, s.getAttribute("canSell"));
        }

        @Test
        @DisplayName("already selling → idempotent, no second write")
        void idempotent() throws Exception {
            AuthSession s = ApiTestSupport.newSellingBuyerSession(7);
            ApiTestSupport.withBearer(req, s);
            when(mockDAO.getUserById(7)).thenReturn(buyer(true));

            StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
            verify(mockDAO, never()).enableSelling(anyInt());
            assertTrue(ApiTestSupport.parse(sw).get("canSell").asBoolean());
        }

        @Test
        @DisplayName("admin → 403")
        void adminRejected() throws Exception {
            AuthSession s = ApiTestSupport.newAdminSession(1);
            ApiTestSupport.withBearer(req, s);

            ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);

            verify(resp).setStatus(403);
            verify(mockDAO, never()).enableSelling(anyInt());
        }
    }

    // ── Seller endpoints accept the capability ────────────────────────────────
    @Nested
    @DisplayName("Seller authorisation")
    class SellerAuthorisation {

        private SellerWrapper servlet;
        private HttpServletRequest req;
        private HttpServletResponse resp;

        @BeforeEach
        void setUp() {
            servlet = new SellerWrapper();
            req  = mock(HttpServletRequest.class);
            resp = mock(HttpServletResponse.class);
            when(req.getPathInfo()).thenReturn("/auctions");
        }

        @Test
        @DisplayName("buyer without the capability → 403")
        void plainBuyerForbidden() throws Exception {
            ApiTestSupport.withBearer(req, ApiTestSupport.newBuyerSession(3));
            ApiTestSupport.bindJsonWriter(resp);
            servlet.doGet(req, resp);
            verify(resp).setStatus(403);
        }

        @Test
        @DisplayName("buyer with the capability is NOT rejected as forbidden")
        void sellingBuyerAllowed() throws Exception {
            ApiTestSupport.withBearer(req, ApiTestSupport.newSellingBuyerSession(3));
            ApiTestSupport.bindJsonWriter(resp);
            servlet.doGet(req, resp);
            // The DAO is unstubbed so the handler may still fail downstream; what
            // matters is that authorisation let it through.
            verify(resp, never()).setStatus(403);
        }

        @Test
        @DisplayName("legacy SELLER role is still accepted")
        void legacySellerAllowed() throws Exception {
            ApiTestSupport.withBearer(req, ApiTestSupport.newSellerSession(3));
            ApiTestSupport.bindJsonWriter(resp);
            servlet.doGet(req, resp);
            verify(resp, never()).setStatus(403);
        }
    }
}
