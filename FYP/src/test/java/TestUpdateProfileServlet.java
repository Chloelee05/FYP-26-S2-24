import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.auction.dao.UserDAO;
import com.auction.servlet.EditProfileServlet;
import com.auction.servlet.UpdateProfileServlet;
import com.auction.util.SecurityUtil;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;

@DisplayName("UpdateProfileServlet (SCRUM-10)")
public class TestUpdateProfileServlet extends Mockito {

    private static class UpdateProfileServletWrapper extends UpdateProfileServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            super.doPost(req, resp);
        }
    }

    private UpdateProfileServletWrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private HttpSession session;
    private UserDAO mockDao;
    private RequestDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        servlet = new UpdateProfileServletWrapper();
        mockDao = mock(UserDAO.class);
        servlet.setUserDAO(mockDao);
        req = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
        session = mock(HttpSession.class);
        dispatcher = mock(RequestDispatcher.class);
        when(req.getSession(false)).thenReturn(session);
        when(req.getContextPath()).thenReturn("");
        when(req.getRequestDispatcher(EditProfileServlet.VIEW_EDIT)).thenReturn(dispatcher);
    }

    private void stubForm(String user, String email, String phone, String address, String img) {
        when(req.getParameter("username")).thenReturn(user);
        when(req.getParameter("email")).thenReturn(email);
        when(req.getParameter("phone")).thenReturn(phone);
        when(req.getParameter("address")).thenReturn(address);
        when(req.getParameter("profileImageUrl")).thenReturn(img);
    }

    @Test
    @DisplayName("SCRUM-193: phone and address passed to DAO are SecurityUtil ciphertext")
    void update_reencryptsPiiBeforePersist() throws Exception {
        when(session.getAttribute("userId")).thenReturn(1);
        stubForm("Alice Lee", "alice@test.com", "+6512345678", "1 Orchard Rd", "");
        when(mockDao.emailTakenByOtherUser("alice@test.com", 1)).thenReturn(false);
        when(mockDao.usernameTakenByOtherUser("Alice Lee", 1)).thenReturn(false);
        when(mockDao.updateProfile(eq(1), eq("Alice Lee"), eq("alice@test.com"), anyString(), anyString(), isNull()))
                .thenReturn(true);

        servlet.doPost(req, resp);

        ArgumentCaptor<String> phoneCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> addrCap = ArgumentCaptor.forClass(String.class);
        verify(mockDao).updateProfile(eq(1), eq("Alice Lee"), eq("alice@test.com"),
                phoneCap.capture(), addrCap.capture(), isNull());

        assertEquals("+6512345678", SecurityUtil.decrypt(phoneCap.getValue()));
        assertEquals("1 Orchard Rd", SecurityUtil.decrypt(addrCap.getValue()));
        verify(resp).sendRedirect("/protected/account?updated=1");
    }

    @Test
    @DisplayName("SCRUM-194: invalid email returns to edit form")
    void invalidEmail_forwardsWithError() throws Exception {
        when(session.getAttribute("userId")).thenReturn(1);
        stubForm("Valid Name", "not-an-email", "+6512345678", "", "");

        servlet.doPost(req, resp);

        verify(dispatcher).forward(req, resp);
        verify(mockDao, never()).updateProfile(anyInt(), anyString(), anyString(), any(), any(), any());
        verify(req).setAttribute(eq("error"), contains("Email"));
    }

    @Test
    @DisplayName("SCRUM-194: duplicate email returns to edit form")
    void duplicateEmail_forwardsWithError() throws Exception {
        when(session.getAttribute("userId")).thenReturn(1);
        stubForm("Alice", "taken@test.com", "", "", "");
        when(mockDao.emailTakenByOtherUser("taken@test.com", 1)).thenReturn(true);

        servlet.doPost(req, resp);

        verify(dispatcher).forward(req, resp);
        verify(req).setAttribute("error", "That email is already in use.");
        verify(mockDao, never()).updateProfile(anyInt(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("SCRUM-194: duplicate display name returns to edit form")
    void duplicateUsername_forwardsWithError() throws Exception {
        when(session.getAttribute("userId")).thenReturn(2);
        stubForm("TakenName", "free@test.com", "", "", "");
        when(mockDao.emailTakenByOtherUser("free@test.com", 2)).thenReturn(false);
        when(mockDao.usernameTakenByOtherUser("TakenName", 2)).thenReturn(true);

        servlet.doPost(req, resp);

        verify(dispatcher).forward(req, resp);
        verify(req).setAttribute("error", "That display name is already taken.");
    }

    @Test
    void email_normalizedToLowerCase() throws Exception {
        when(session.getAttribute("userId")).thenReturn(1);
        stubForm("Bob", "Bob@Test.COM", "", "", "");
        when(mockDao.emailTakenByOtherUser("bob@test.com", 1)).thenReturn(false);
        when(mockDao.usernameTakenByOtherUser("Bob", 1)).thenReturn(false);
        when(mockDao.updateProfile(eq(1), eq("Bob"), eq("bob@test.com"), isNull(), isNull(), isNull()))
                .thenReturn(true);

        servlet.doPost(req, resp);

        verify(mockDao).updateProfile(1, "Bob", "bob@test.com", null, null, null);
        verify(session).setAttribute("sessionEmail", "bob@test.com");
    }

    @Test
    void noSession_redirectsLogin() throws Exception {
        when(req.getSession(false)).thenReturn(null);

        servlet.doPost(req, resp);

        verify(resp).sendRedirect("/login");
        verify(mockDao, never()).updateProfile(anyInt(), anyString(), anyString(), any(), any(), any());
    }
}
