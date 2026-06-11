import com.auction.filter.SpaFallbackFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.mockito.Mockito.*;

@DisplayName("SpaFallbackFilter")
class TestSpaFallbackFilter {

    private SpaFallbackFilter filter;
    private ServletContext ctx;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new SpaFallbackFilter();
        ctx = mock(ServletContext.class);
        FilterConfig config = mock(FilterConfig.class);
        when(config.getServletContext()).thenReturn(ctx);
        filter.init(config);
        chain = mock(FilterChain.class);
    }

    @Test
    @DisplayName("POST requests skip SPA fallback")
    void postSkipsSpa() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(req.getMethod()).thenReturn("POST");

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        verify(ctx, never()).getRequestDispatcher(anyString());
    }

    @Test
    @DisplayName("/api/* paths skip SPA fallback")
    void apiSkipsSpa() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(req.getMethod()).thenReturn("GET");
        when(req.getRequestURI()).thenReturn("/online-auction/api/search");
        when(req.getContextPath()).thenReturn("/online-auction");
        when(ctx.getResource("/index.html")).thenReturn(new URL("file:/index.html"));

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
    }

    @Test
    @DisplayName("unknown GET client route forwards to index.html")
    void spaFallback() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        RequestDispatcher dispatcher = mock(RequestDispatcher.class);

        when(req.getMethod()).thenReturn("GET");
        when(req.getRequestURI()).thenReturn("/online-auction/search");
        when(req.getContextPath()).thenReturn("/online-auction");
        when(ctx.getResource("/index.html")).thenReturn(new URL("file:/index.html"));
        when(ctx.getResource("/search")).thenReturn(null);
        when(ctx.getRequestDispatcher("/index.html")).thenReturn(dispatcher);

        filter.doFilter(req, resp, chain);

        verify(dispatcher).forward(req, resp);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("static asset extensions skip SPA fallback")
    void staticExtensionSkipsSpa() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(req.getMethod()).thenReturn("GET");
        when(req.getRequestURI()).thenReturn("/online-auction/assets/app.js");
        when(req.getContextPath()).thenReturn("/online-auction");
        when(ctx.getResource("/index.html")).thenReturn(new URL("file:/index.html"));

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
    }
}
