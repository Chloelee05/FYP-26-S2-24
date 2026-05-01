import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.auction.dao.UserDAO;
import com.auction.model.Role;
import com.auction.model.User;
import com.auction.servlet.AccountManagementServlet;
import com.auction.util.SecurityUtil;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;

@DisplayName("AccountManagementServlet (SCRUM-8)")
public class TestAccountManagementServlet extends Mockito {

    private static class AccountServletWrapper extends AccountManagementServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            super.doGet(req, resp);
        }
    }

    private AccountServletWrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private HttpSession session;
    private UserDAO mockDao;
    private RequestDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        servlet   = new AccountServletWrapper();
        mockDao   = mock(UserDAO.class);
        servlet.setUserDAO(mockDao);
        req       = mock(HttpServletRequest.class);
        resp      = mock(HttpServletResponse.class);
        session   = mock(HttpSession.class);
        dispatcher = mock(RequestDispatcher.class);
        when(req.getSession(false)).thenReturn(session);
        when(req.getRequestDispatcher(AccountManagementServlet.VIEW_DASHBOARD)).thenReturn(dispatcher);
        when(req.getContextPath()).thenReturn("");
    }

    @Test
    @DisplayName("SCRUM-185-style: no session redirects to login")
    void noSessionRedirectsToLogin() throws Exception {
        when(req.getSession(false)).thenReturn(null);

        servlet.doGet(req, resp);

        verify(resp).sendRedirect("/login");
        verify(mockDao, never()).getUserById(anyInt());
    }

    @Test
    @DisplayName("SCRUM-189: profile load uses session userId only; ignores userId request param")
    void loadsOnlySessionUserIdIgnoresRequestParam() throws Exception {
        when(session.getAttribute("userId")).thenReturn(5);
        when(req.getParameter("userId")).thenReturn("999");

        User user = profileUser(5, "bob", "bob@test.com", Role.BUYER);
        when(mockDao.getUserById(5)).thenReturn(user);

        servlet.doGet(req, resp);

        verify(mockDao).getUserById(5);
        verify(mockDao, never()).getUserById(999);
        verify(dispatcher).forward(req, resp);
    }

    @Test
    @DisplayName("SCRUM-187: decrypts phone and address ciphertext from DB-style User row")
    void decryptsPhoneAndAddressForDisplay() throws Exception {
        when(session.getAttribute("userId")).thenReturn(1);

        String phonePlain = "+65 9123 4567";
        String addrPlain = "1 Orchard Road, Singapore";
        User user = profileUser(1, "a", "a@test.com", Role.BUYER);
        user.setPhoneEncrypted(SecurityUtil.encrypt(phonePlain));
        user.setAddressEncrypted(SecurityUtil.encrypt(addrPlain));
        when(mockDao.getUserById(1)).thenReturn(user);

        servlet.doGet(req, resp);

        verify(req).setAttribute("profilePhone", phonePlain);
        verify(req).setAttribute("profileAddress", addrPlain);
    }

    @Test
    @DisplayName("SCRUM-132: forwards dashboard with username, email, role")
    void forwardsWithCoreAttributes() throws Exception {
        when(session.getAttribute("userId")).thenReturn(2);
        User user = profileUser(2, "u2", "u2@test.com", Role.SELLER);
        when(mockDao.getUserById(2)).thenReturn(user);

        servlet.doGet(req, resp);

        verify(req).setAttribute(eq("profileUsername"), eq(SecurityUtil.sanitize("u2")));
        verify(req).setAttribute("profileEmail", "u2@test.com");
        verify(req).setAttribute("profileRole", "SELLER");
        verify(dispatcher).forward(req, resp);
    }

    @Test
    @DisplayName("Missing user row returns 404")
    void missingUserSends404() throws Exception {
        when(session.getAttribute("userId")).thenReturn(42);
        when(mockDao.getUserById(42)).thenReturn(null);

        servlet.doGet(req, resp);

        verify(resp).sendError(eq(404), anyString());
        verify(dispatcher, never()).forward(any(), any());
    }

    private static User profileUser(int id, String username, String email, Role role) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setEmail(email);
        u.setRole(role);
        u.setTwoFactorEnabled(false);
        return u;
    }
}
