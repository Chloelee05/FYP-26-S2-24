import com.auction.filter.AdminFilter;
import com.auction.model.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.auction.model.Role;


@DisplayName("AdminFilter tests")
public class TestAdminFilter extends Mockito {

    private AdminFilter filter;
    private HttpServletRequest mockReq;
    private HttpServletResponse mockResp;
    private FilterChain mockChain;
    private HttpSession mockSession;

    @BeforeEach
    public void setUp() {
        filter    = new AdminFilter();
        mockReq   = mock(HttpServletRequest.class);
        mockResp  = mock(HttpServletResponse.class);
        mockChain = mock(FilterChain.class);
        mockSession = mock(HttpSession.class);
    }

    @Test
    @DisplayName("Test no session")
    public void TestNoSession() throws Exception {
        when(mockReq.getSession(false)).thenReturn(null);

        filter.doFilter(mockReq, mockResp, mockChain);

        verify(mockChain, never()).doFilter(mockReq, mockResp);
    }

    @Test
    @DisplayName("Test no user")
    public void TestNoUser() throws Exception {
        when(mockReq.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute("user")).thenReturn(null);

        filter.doFilter(mockReq, mockResp, mockChain);

        verify(mockChain, never()).doFilter(mockReq, mockResp);
    }

    @Test
    @DisplayName("Test buyer role")
    public void TestBuyer() throws Exception {
        User user = new User();
        user.setRole(Role.BUYER);

        when(mockReq.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute("user")).thenReturn(user);

        filter.doFilter(mockReq, mockResp, mockChain);

        verify(mockChain, never()).doFilter(mockReq, mockResp);
    }

    @Test
    @DisplayName("Test seller role")
    public void TestSeller() throws Exception {
        User user = new User();
        user.setRole(Role.SELLER);

        when(mockReq.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute("user")).thenReturn(user);

        filter.doFilter(mockReq, mockResp, mockChain);

        verify(mockChain, never()).doFilter(mockReq, mockResp);
    }

    @Test
    @DisplayName("Test admin role")
    public void TestAdmin() throws Exception {
        User user = new User();
        user.setRole(Role.ADMIN);

        when(mockReq.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute("user")).thenReturn(user);

        filter.doFilter(mockReq, mockResp, mockChain);

        verify(mockChain, times(1)).doFilter(mockReq, mockResp);
    }
}