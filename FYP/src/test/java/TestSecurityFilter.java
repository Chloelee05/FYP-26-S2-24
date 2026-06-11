import com.auction.filter.SecurityFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("SecurityFilter")
class TestSecurityFilter {

    @Test
    @DisplayName("sets security headers and continues chain")
    void securityHeaders() throws Exception {
        SecurityFilter filter = new SecurityFilter();
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(resp).setHeader("X-Content-Type-Options", "nosniff");
        verify(resp).setHeader("X-Frame-Options", "DENY");
        verify(resp).setHeader("X-XSS-Protection", "1; mode=block");
        ArgumentCaptor<String> csp = ArgumentCaptor.forClass(String.class);
        verify(resp).setHeader(eq("Content-Security-Policy"), csp.capture());
        assertTrue(csp.getValue().contains("default-src 'self'"));
        verify(chain).doFilter(req, resp);
    }
}
