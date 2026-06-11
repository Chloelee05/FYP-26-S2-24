import com.auction.dao.UserDAO;
import com.auction.model.Role;
import com.auction.model.User;
import com.auction.servlet.EditProfileServlet;
import com.auction.util.SecurityUtil;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.mockito.Mockito.*;

@DisplayName("EditProfileServlet")
class TestEditProfileServlet {

    private static class Wrapper extends EditProfileServlet {
        @Override
        public void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            super.doGet(req, resp);
        }
    }

    private UserDAO mockDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private HttpSession session;

    @BeforeEach
    void setUp() {
        mockDAO = mock(UserDAO.class);
        servlet = new Wrapper();
        servlet.setUserDAO(mockDAO);
        req     = mock(HttpServletRequest.class);
        resp    = mock(HttpServletResponse.class);
        session = mock(HttpSession.class);
        when(req.getContextPath()).thenReturn("/app");
    }

    @Test
    @DisplayName("doGet without session redirects to login")
    void noSessionRedirects() throws Exception {
        when(req.getSession(false)).thenReturn(null);
        servlet.doGet(req, resp);
        verify(resp).sendRedirect("/app/login");
    }

    @Test
    @DisplayName("doGet populates form from decrypted profile")
    void populatesForm() throws Exception {
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("userId")).thenReturn(5);

        User user = new User("alice", "alice@email.com", "hash", Role.BUYER);
        user.setId(5);
        user.setPhoneEncrypted(SecurityUtil.encrypt("+6599999999"));
        user.setAddressEncrypted(SecurityUtil.encrypt("88 Test St"));
        when(mockDAO.getUserById(5)).thenReturn(user);

        RequestDispatcher dispatcher = mock(RequestDispatcher.class);
        when(req.getRequestDispatcher(EditProfileServlet.VIEW_EDIT)).thenReturn(dispatcher);

        servlet.doGet(req, resp);

        verify(req).setAttribute("formUsername", "alice");
        verify(req).setAttribute("formPhone", "+6599999999");
        verify(req).setAttribute("formAddress", "88 Test St");
        verify(dispatcher).forward(req, resp);
    }
}
