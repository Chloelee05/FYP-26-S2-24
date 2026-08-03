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

    @Test
    @DisplayName("CSP allows the Google Fonts origins the page actually loads")
    void cspAllowsWebFonts() throws Exception {
        String csp = capturedCsp();

        // index.html pulls the stylesheet from fonts.googleapis.com and the font files it
        // references from fonts.gstatic.com; missing either drops the site to system fonts.
        assertTrue(directive(csp, "style-src").contains("https://fonts.googleapis.com"),
                "style-src must allow the Google Fonts stylesheet: " + csp);
        assertTrue(directive(csp, "font-src").contains("https://fonts.gstatic.com"),
                "font-src must allow the Google Fonts files: " + csp);
    }

    @Test
    @DisplayName("CSP still allows jsDelivr for the legacy JSP pages")
    void cspKeepsBootstrapCdn() throws Exception {
        String csp = capturedCsp();

        // The JSP views are still mapped and still load Bootstrap from jsDelivr.
        assertTrue(directive(csp, "style-src").contains("https://cdn.jsdelivr.net"), csp);
        assertTrue(directive(csp, "script-src").contains("https://cdn.jsdelivr.net"), csp);
    }

    private static String capturedCsp() throws Exception {
        SecurityFilter filter = new SecurityFilter();
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        filter.doFilter(req, resp, mock(FilterChain.class));

        ArgumentCaptor<String> csp = ArgumentCaptor.forClass(String.class);
        verify(resp).setHeader(eq("Content-Security-Policy"), csp.capture());
        return csp.getValue();
    }

    /** The single directive named, so an origin allowed elsewhere cannot satisfy an assertion. */
    private static String directive(String csp, String name) {
        for (String part : csp.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(name + " ")) return trimmed;
        }
        return fail("CSP has no " + name + " directive: " + csp);
    }
}
