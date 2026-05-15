import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.auction.dao.UserDAO;
import com.auction.servlet.DeleteAccountServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;

@DisplayName("DeleteAccountServlet (SCRUM-9)")
public class TestDeleteAccountServlet extends Mockito {

    private static class DeleteAccountServletWrapper extends DeleteAccountServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            super.doPost(req, resp);
        }
    }

    private DeleteAccountServletWrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private HttpSession session;
    private UserDAO mockDao;

    @BeforeEach
    void setUp() {
        servlet = new DeleteAccountServletWrapper();
        mockDao = mock(UserDAO.class);
        servlet.setUserDAO(mockDao);
        req = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
        session = mock(HttpSession.class);
        when(req.getSession(false)).thenReturn(session);
        when(req.getContextPath()).thenReturn("");
    }

    @Test
    @DisplayName("No session redirects to login")
    void noSessionRedirectsLogin() throws Exception {
        when(req.getSession(false)).thenReturn(null);

        servlet.doPost(req, resp);

        verify(resp).sendRedirect("/login");
        verify(mockDao, never()).deleteAccount(anyInt());
    }

    @Test
    @DisplayName("Missing or wrong confirm token redirects to account page")
    void badConfirmRedirectsAccount() throws Exception {
        when(session.getAttribute("userId")).thenReturn(5);
        when(req.getParameter("confirm")).thenReturn("no");

        servlet.doPost(req, resp);

        verify(resp).sendRedirect("/protected/account");
        verify(mockDao, never()).deleteAccount(anyInt());
        verify(session, never()).invalidate();
    }

    @Test
    @DisplayName("SCRUM-191: after successful delete, session is invalidated")
    void successInvalidatesSession() throws Exception {
        when(session.getAttribute("userId")).thenReturn(5);
        when(req.getParameter("confirm")).thenReturn(DeleteAccountServlet.CONFIRM_TOKEN);
        when(mockDao.deleteAccount(5)).thenReturn(true);

        servlet.doPost(req, resp);

        verify(mockDao).deleteAccount(5);
        verify(session).invalidate();
        verify(resp).sendRedirect("/login?accountClosed=1");
    }

    @Test
    @DisplayName("deleteAccount false sends 404")
    void notFoundWhenDaoReturnsFalse() throws Exception {
        when(session.getAttribute("userId")).thenReturn(5);
        when(req.getParameter("confirm")).thenReturn(DeleteAccountServlet.CONFIRM_TOKEN);
        when(mockDao.deleteAccount(5)).thenReturn(false);

        servlet.doPost(req, resp);

        verify(resp).sendError(eq(404), anyString());
        verify(session, never()).invalidate();
    }
}
