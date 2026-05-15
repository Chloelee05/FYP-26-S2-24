import com.auction.filter.AdminFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AdminFilter tests")
public class TestAdminFilter {

    private AdminFilter filter;
    private HttpServletRequest mockReq;
    private HttpServletResponse mockResp;
    private FilterChain mockChain;
    private HttpSession mockSession;

    @BeforeEach
    public void setUp() {
        filter = new AdminFilter();
        mockReq = mock(HttpServletRequest.class);
        mockResp = mock(HttpServletResponse.class);
        mockChain = mock(FilterChain.class);
        mockSession = mock(HttpSession.class);
        when(mockReq.getContextPath()).thenReturn("");
    }

    @Test
    @DisplayName("Test no session")
    public void TestNoSession() throws Exception {
        when(mockReq.getSession(false)).thenReturn(null);

        filter.doFilter(mockReq, mockResp, mockChain);

        verify(mockResp).sendRedirect("/login");
        verify(mockChain, never()).doFilter(mockReq, mockResp);
    }

    @Test
    @DisplayName("Test unauthenticated (no userId)")
    public void TestNoUser() throws Exception {
        when(mockReq.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute("userId")).thenReturn(null);

        filter.doFilter(mockReq, mockResp, mockChain);

        verify(mockResp).sendRedirect("/login");
        verify(mockChain, never()).doFilter(mockReq, mockResp);
    }

    @Test
    @DisplayName("Test buyer role")
    public void TestBuyer() throws Exception {
        when(mockReq.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute("userId")).thenReturn(10);
        when(mockSession.getAttribute("userRole")).thenReturn("BUYER");

        filter.doFilter(mockReq, mockResp, mockChain);

        verify(mockResp).sendError(HttpServletResponse.SC_FORBIDDEN, "Admin access required.");
        verify(mockChain, never()).doFilter(mockReq, mockResp);
    }

    @Test
    @DisplayName("Test seller role")
    public void TestSeller() throws Exception {
        when(mockReq.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute("userId")).thenReturn(11);
        when(mockSession.getAttribute("userRole")).thenReturn("SELLER");

        filter.doFilter(mockReq, mockResp, mockChain);

        verify(mockResp).sendError(HttpServletResponse.SC_FORBIDDEN, "Admin access required.");
        verify(mockChain, never()).doFilter(mockReq, mockResp);
    }

    @Test
    @DisplayName("Test admin role")
    public void TestAdmin() throws Exception {
        when(mockReq.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute("userId")).thenReturn(1);
        when(mockSession.getAttribute("userRole")).thenReturn("ADMIN");

        filter.doFilter(mockReq, mockResp, mockChain);

        verify(mockChain, times(1)).doFilter(mockReq, mockResp);
        verify(mockResp, never()).sendRedirect(ArgumentMatchers.anyString());
        verify(mockResp, never()).sendError(ArgumentMatchers.anyInt(), ArgumentMatchers.anyString());
    }
}
