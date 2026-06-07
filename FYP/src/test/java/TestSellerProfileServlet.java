import com.auction.dao.SellerProfileDAO;
import com.auction.dao.SellerProfileDAO.AvgRating;
import com.auction.model.SellerPublicProfile;
import com.auction.model.profile.ProfileReviewRow;
import com.auction.servlet.SellerProfileServlet;
import com.auction.util.SecurityUtil;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SellerProfileServlet} (SCRUM-63 / SCRUM-358).
 */
@DisplayName("SellerProfileServlet")
public class TestSellerProfileServlet {

    private static class Wrapper extends SellerProfileServlet {
        Wrapper(SellerProfileDAO dao) { super(dao); }

        @Override
        public void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException { super.doGet(req, resp); }
    }

    private SellerProfileDAO mockDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    private static SellerPublicProfile sampleProfile(long id, String username, String email) {
        return new SellerPublicProfile(
                id, username, SecurityUtil.maskEmail(email),
                Instant.parse("2024-01-15T00:00:00Z"), null, 5);
    }

    @BeforeEach
    void setUp() {
        mockDAO = mock(SellerProfileDAO.class);
        servlet = new Wrapper(mockDAO);
        req     = mock(HttpServletRequest.class);
        resp    = mock(HttpServletResponse.class);
        when(req.getContextPath()).thenReturn("/app");
        when(req.getParameter("page")).thenReturn(null);
        when(req.getParameter("size")).thenReturn(null);
    }

    // =========================================================================
    // Public access
    // =========================================================================

    @Nested
    @DisplayName("Public access")
    class PublicAccessTests {

        @Test
        @DisplayName("no session required — profile loads successfully")
        void testPublicAccessNoSession() throws Exception {
            when(req.getPathInfo()).thenReturn("/42");
            when(req.getSession(false)).thenReturn(null);
            when(mockDAO.getPublicProfile(42L)).thenReturn(
                    sampleProfile(42L, "sellerJoe", "seller@example.com"));
            when(mockDAO.getAvgRating(42L)).thenReturn(new AvgRating(4.5, 3));
            when(mockDAO.getReviews(42L, 1, SellerProfileServlet.DEFAULT_REVIEW_PAGE_SIZE))
                    .thenReturn(Collections.emptyList());
            RequestDispatcher rd = mock(RequestDispatcher.class);
            when(req.getRequestDispatcher("/WEB-INF/views/seller-profile.jsp")).thenReturn(rd);

            servlet.doGet(req, resp);

            verify(resp, never()).sendRedirect(contains("login"));
            verify(rd).forward(req, resp);
        }
    }

    // =========================================================================
    // Invalid seller ID → 400
    // =========================================================================

    @Nested
    @DisplayName("Invalid seller ID")
    class InvalidIdTests {

        @Test
        @DisplayName("non-numeric pathInfo → 400")
        void testNonNumericSellerId() throws Exception {
            when(req.getPathInfo()).thenReturn("/abc");
            servlet.doGet(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), contains("Invalid"));
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("missing pathInfo → 400")
        void testMissingSellerId() throws Exception {
            when(req.getPathInfo()).thenReturn("/");
            servlet.doGet(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("parseSellerId returns -1 for invalid input")
        void testParseSellerIdInvalid() throws Exception {
            when(req.getPathInfo()).thenReturn("/xyz");
            long id = SellerProfileServlet.parseSellerId(req, resp);
            assertEquals(-1, id);
        }
    }

    // =========================================================================
    // Non-seller → 404
    // =========================================================================

    @Nested
    @DisplayName("Non-seller user")
    class NonSellerTests {

        @Test
        @DisplayName("buyer userId → 404")
        void testBuyerUserReturns404() throws Exception {
            when(req.getPathInfo()).thenReturn("/99");
            when(mockDAO.getPublicProfile(99L)).thenReturn(null);
            servlet.doGet(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_NOT_FOUND), anyString());
            verify(mockDAO, never()).getReviews(anyLong(), anyInt(), anyInt());
        }
    }

    // =========================================================================
    // No reviews (empty)
    // =========================================================================

    @Nested
    @DisplayName("Empty reviews")
    class EmptyReviewsTests {

        @Test
        @DisplayName("seller with zero reviews sets reviewsEmpty=true")
        void testNoReviewsEmptyFlag() throws Exception {
            when(req.getPathInfo()).thenReturn("/42");
            when(mockDAO.getPublicProfile(42L)).thenReturn(
                    sampleProfile(42L, "newSeller", "new@example.com"));
            when(mockDAO.getAvgRating(42L)).thenReturn(new AvgRating(0, 0));
            when(mockDAO.getReviews(42L, 1, SellerProfileServlet.DEFAULT_REVIEW_PAGE_SIZE))
                    .thenReturn(Collections.emptyList());
            RequestDispatcher rd = mock(RequestDispatcher.class);
            when(req.getRequestDispatcher("/WEB-INF/views/seller-profile.jsp")).thenReturn(rd);

            servlet.doGet(req, resp);

            verify(req).setAttribute(eq("reviewsEmpty"), eq(true));
            verify(req).setAttribute(eq("reviewCount"), eq(0));
            verify(req).setAttribute(eq("avgRating"), eq(0.0));
        }
    }

    // =========================================================================
    // Successful response with masked PII
    // =========================================================================

    @Nested
    @DisplayName("Successful profile with masked PII")
    class SuccessTests {

        @Test
        @DisplayName("profile email is masked in request attribute")
        void testMaskedEmailInProfile() throws Exception {
            when(req.getPathInfo()).thenReturn("/42");
            SellerPublicProfile profile = sampleProfile(42L, "trustedSeller", "alice@example.com");
            when(mockDAO.getPublicProfile(42L)).thenReturn(profile);
            when(mockDAO.getAvgRating(42L)).thenReturn(new AvgRating(4.8, 2));
            ProfileReviewRow review = new ProfileReviewRow(
                    "b***r", 5, "Great seller!", LocalDate.of(2025, 3, 1), "Vintage Watch");
            when(mockDAO.getReviews(42L, 1, SellerProfileServlet.DEFAULT_REVIEW_PAGE_SIZE))
                    .thenReturn(List.of(review));
            RequestDispatcher rd = mock(RequestDispatcher.class);
            when(req.getRequestDispatcher("/WEB-INF/views/seller-profile.jsp")).thenReturn(rd);

            servlet.doGet(req, resp);

            verify(req).setAttribute(eq("profile"), argThat(p -> {
                SellerPublicProfile sp = (SellerPublicProfile) p;
                return sp.getMaskedEmail().equals(SecurityUtil.maskEmail("alice@example.com"))
                        && !sp.getMaskedEmail().equals("alice@example.com");
            }));
            verify(req).setAttribute(eq("reviews"), eq(List.of(review)));
            verify(req).setAttribute(eq("reviewsEmpty"), eq(false));
            verify(req).setAttribute(eq("avgRating"), eq(4.8));
            verify(req).setAttribute(eq("reviewCount"), eq(2));
            verify(req).setAttribute(eq("profile"), argThat(p ->
                    ((SellerPublicProfile) p).getActiveListingCount() == 5));
        }

        @Test
        @DisplayName("review pagination params passed to DAO")
        void testReviewPagination() throws Exception {
            when(req.getPathInfo()).thenReturn("/42");
            when(req.getParameter("page")).thenReturn("2");
            when(req.getParameter("size")).thenReturn("5");
            when(mockDAO.getPublicProfile(42L)).thenReturn(
                    sampleProfile(42L, "seller", "s@example.com"));
            when(mockDAO.getAvgRating(42L)).thenReturn(new AvgRating(3.0, 12));
            when(mockDAO.getReviews(42L, 2, 5)).thenReturn(Collections.emptyList());
            RequestDispatcher rd = mock(RequestDispatcher.class);
            when(req.getRequestDispatcher("/WEB-INF/views/seller-profile.jsp")).thenReturn(rd);

            servlet.doGet(req, resp);

            verify(mockDAO).getReviews(42L, 2, 5);
            verify(req).setAttribute(eq("reviewPage"), eq(2));
            verify(req).setAttribute(eq("reviewPageSize"), eq(5));
        }
    }

    // =========================================================================
    // parseSellerId helper
    // =========================================================================

    @Test
    @DisplayName("parseSellerId valid numeric path")
    void testParseSellerIdValid() throws Exception {
        when(req.getPathInfo()).thenReturn("/12345");
        assertEquals(12345L, SellerProfileServlet.parseSellerId(req, resp));
    }
}
