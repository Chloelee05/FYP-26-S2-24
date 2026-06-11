import com.auction.filter.CorsFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

@DisplayName("CorsFilter")
class TestCorsFilter {

    @Test
    @DisplayName("OPTIONS preflight returns 204 without calling chain")
    void optionsPreflight() throws Exception {
        CorsFilter filter = new CorsFilter();
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        when(req.getMethod()).thenReturn("OPTIONS");

        filter.doFilter(req, resp, chain);

        verify(resp).setHeader(eq("Access-Control-Allow-Origin"), eq("http://localhost:3000"));
        verify(resp).setStatus(HttpServletResponse.SC_NO_CONTENT);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("GET passes through chain with CORS headers")
    void getPassesThrough() throws Exception {
        CorsFilter filter = new CorsFilter();
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        when(req.getMethod()).thenReturn("GET");

        filter.doFilter(req, resp, chain);

        verify(resp).setHeader(eq("Access-Control-Allow-Origin"), eq("http://localhost:3000"));
        verify(chain).doFilter(req, resp);
    }
}
