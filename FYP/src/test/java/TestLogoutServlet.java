import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.auction.filter.AuthFilter;
import com.auction.servlet.LogoutServlet;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;

@DisplayName("Logout tests (SCRUM-7)")
public class TestLogoutServlet extends Mockito {

    private static class LogoutServletWrapper extends LogoutServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            super.doPost(req, resp);
        }
    }

    private LogoutServletWrapper servlet;
    private HttpServletRequest mockReq;
    private HttpServletResponse mockResp;
    private HttpSession mockSession;

    @BeforeEach
    public void setUp() {
        servlet     = new LogoutServletWrapper();
        mockReq     = mock(HttpServletRequest.class);
        mockResp    = mock(HttpServletResponse.class);
        mockSession = mock(HttpSession.class);
    }

    // ── SCRUM-184: HttpSession invalidation logic ──────────────────────────────

    @Test
    @DisplayName("SCRUM-184: Logout calls session.invalidate()")
    public void testLogoutInvalidatesHttpSession() throws Exception {
        when(mockReq.getSession(false)).thenReturn(mockSession);
        when(mockReq.getContextPath()).thenReturn("");

        servlet.doPost(mockReq, mockResp);

        verify(mockSession, times(1)).invalidate();
    }

    @Test
    @DisplayName("SCRUM-184: No active session does not throw on logout")
    public void testLogoutWithNoSessionDoesNotThrow() {
        when(mockReq.getSession(false)).thenReturn(null);
        when(mockReq.getContextPath()).thenReturn("");

        assertDoesNotThrow(() -> servlet.doPost(mockReq, mockResp));
    }

    // ── SCRUM-185: Redirect to login/home page after session destruction ───────

    @Test
    @DisplayName("SCRUM-185: Logout redirects to /login after session invalidation")
    public void testLogoutRedirectsToLoginPage() throws Exception {
        when(mockReq.getSession(false)).thenReturn(mockSession);
        when(mockReq.getContextPath()).thenReturn("");

        servlet.doPost(mockReq, mockResp);

        verify(mockResp).sendRedirect("/login");
    }

    @Test
    @DisplayName("SCRUM-185: Logout redirects to /login even when no session exists")
    public void testLogoutWithNoSessionStillRedirectsToLogin() throws Exception {
        when(mockReq.getSession(false)).thenReturn(null);
        when(mockReq.getContextPath()).thenReturn("");

        servlet.doPost(mockReq, mockResp);

        verify(mockResp).sendRedirect("/login");
    }

    @Test
    @DisplayName("SCRUM-185: Logout respects context path in redirect URL")
    public void testLogoutRedirectIncludesContextPath() throws Exception {
        when(mockReq.getSession(false)).thenReturn(mockSession);
        when(mockReq.getContextPath()).thenReturn("/auction");

        servlet.doPost(mockReq, mockResp);

        verify(mockResp).sendRedirect("/auction/login");
    }

    @Test
    @DisplayName("SCRUM-185: Logout sets no-cache headers to prevent back-button access")
    public void testLogoutSetsNoCacheHeaders() throws Exception {
        when(mockReq.getSession(false)).thenReturn(mockSession);
        when(mockReq.getContextPath()).thenReturn("");

        servlet.doPost(mockReq, mockResp);

        verify(mockResp).setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        verify(mockResp).setHeader("Pragma", "no-cache");
    }

    // ── SCRUM-186: Unauthorized access to protected pages fails after logout ───

    @Test
    @DisplayName("SCRUM-186: AuthFilter blocks request when session is null (after logout)")
    public void testProtectedPageBlocksRequestWithNullSession() throws Exception {
        AuthFilter filter = new AuthFilter();
        FilterChain mockChain = mock(FilterChain.class);

        when(mockReq.getSession(false)).thenReturn(null);
        when(mockReq.getContextPath()).thenReturn("");

        filter.doFilter(mockReq, mockResp, mockChain);

        verify(mockResp).sendRedirect("/login");
        verify(mockChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("SCRUM-186: AuthFilter blocks request when session has no userId (invalidated session)")
    public void testProtectedPageBlocksRequestAfterSessionInvalidation() throws Exception {
        AuthFilter filter = new AuthFilter();
        FilterChain mockChain = mock(FilterChain.class);

        when(mockReq.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute("userId")).thenReturn(null);
        when(mockReq.getContextPath()).thenReturn("");

        filter.doFilter(mockReq, mockResp, mockChain);

        verify(mockResp).sendRedirect("/login");
        verify(mockChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("SCRUM-186: AuthFilter allows request when valid userId is in session")
    public void testProtectedPageAllowsAuthenticatedRequest() throws Exception {
        AuthFilter filter = new AuthFilter();
        FilterChain mockChain = mock(FilterChain.class);

        when(mockReq.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute("userId")).thenReturn(42);

        filter.doFilter(mockReq, mockResp, mockChain);

        verify(mockChain, times(1)).doFilter(mockReq, mockResp);
        verify(mockResp, never()).sendRedirect(anyString());
    }
}
