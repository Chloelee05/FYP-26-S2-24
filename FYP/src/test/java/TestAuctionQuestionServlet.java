import com.auction.dao.QuestionDAO;
import com.auction.dao.QuestionDAO.QuestionResult;
import com.auction.servlet.AuctionQuestionServlet;
import com.auction.util.InputValidator;
import com.auction.util.SecurityUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AuctionQuestionServlet} (SCRUM-62 / SCRUM-354).
 */
@DisplayName("AuctionQuestionServlet")
public class TestAuctionQuestionServlet {

    private static class Wrapper extends AuctionQuestionServlet {
        Wrapper(QuestionDAO dao) { super(dao); }

        @Override
        public void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException { super.doGet(req, resp); }

        @Override
        public void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException { super.doPost(req, resp); }
    }

    private QuestionDAO mockDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private HttpSession session;

    @BeforeEach
    void setUp() {
        mockDAO = mock(QuestionDAO.class);
        servlet = new Wrapper(mockDAO);
        req     = mock(HttpServletRequest.class);
        resp    = mock(HttpServletResponse.class);
        session = mock(HttpSession.class);
        when(req.getContextPath()).thenReturn("/app");
    }

    private void stubBuyerSession(int userId) {
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("userRole")).thenReturn("BUYER");
        when(session.getAttribute("userId")).thenReturn(userId);
    }

    private void stubSellerSession(int userId) {
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("userRole")).thenReturn("SELLER");
        when(session.getAttribute("userId")).thenReturn(userId);
    }

    // =========================================================================
    // GET — list redirect
    // =========================================================================

    @Nested
    @DisplayName("GET /auction-question")
    class GetTests {

        @Test
        @DisplayName("valid auctionId → redirects to auction detail #questions")
        void testGetRedirectsToAuctionAnchor() throws Exception {
            when(req.getParameter("auctionId")).thenReturn("42");
            when(mockDAO.listByAuction(42L)).thenReturn(java.util.Collections.emptyList());

            servlet.doGet(req, resp);

            verify(resp).sendRedirect("/app/auction/42#questions");
        }

        @Test
        @DisplayName("missing auctionId → 400")
        void testGetMissingAuctionId() throws Exception {
            when(req.getParameter("auctionId")).thenReturn(null);
            servlet.doGet(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
        }

        @Test
        @DisplayName("non-numeric auctionId → 400")
        void testGetInvalidAuctionId() throws Exception {
            when(req.getParameter("auctionId")).thenReturn("abc");
            servlet.doGet(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
        }
    }

    // =========================================================================
    // POST ASK — RBAC & validation
    // =========================================================================

    @Nested
    @DisplayName("POST action=ASK")
    class AskTests {

        @BeforeEach
        void commonAskParams() {
            when(req.getParameter("action")).thenReturn("ASK");
            when(req.getParameter("auctionId")).thenReturn("42");
        }

        @Test
        @DisplayName("no session → 403")
        void testAskNoSession() throws Exception {
            when(req.getSession(false)).thenReturn(null);
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("SELLER role → 403")
        void testAskSellerForbidden() throws Exception {
            stubSellerSession(5);
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_FORBIDDEN), contains("buyer"));
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("blank question → 400")
        void testAskBlankQuestion() throws Exception {
            stubBuyerSession(10);
            when(req.getParameter("question")).thenReturn("   ");
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), contains("required"));
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("question exceeding max length → 400")
        void testAskQuestionTooLong() throws Exception {
            stubBuyerSession(10);
            when(req.getParameter("question")).thenReturn("a".repeat(InputValidator.QUESTION_MAX_LENGTH + 1));
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), contains("1000"));
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("invalid auctionId → 400")
        void testAskInvalidAuctionId() throws Exception {
            stubBuyerSession(10);
            when(req.getParameter("auctionId")).thenReturn("not-a-number");
            when(req.getParameter("question")).thenReturn("Is this item new?");
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), contains("Invalid"));
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("seller asking on own auction → flash error (SELF_QUESTION)")
        void testAskSelfQuestionFlashError() throws Exception {
            stubBuyerSession(10);
            when(req.getParameter("question")).thenReturn("Is this available?");
            when(mockDAO.insertQuestion(eq(42L), eq(10), anyString())).thenReturn(QuestionResult.SELF_QUESTION);

            servlet.doPost(req, resp);

            verify(mockDAO).insertQuestion(eq(42L), eq(10), anyString());
            verify(session).setAttribute(eq("questionFlashError"), contains("own auction"));
            verify(resp).sendRedirect("/app/auction/42#questions");
        }

        @Test
        @DisplayName("successful ask → sanitized text passed to DAO and flash success")
        void testAskSuccessSanitized() throws Exception {
            stubBuyerSession(10);
            String xss = "<script>alert(1)</script>Is it new?";
            when(req.getParameter("question")).thenReturn(xss);
            when(mockDAO.insertQuestion(eq(42L), eq(10), anyString())).thenReturn(QuestionResult.SUCCESS);

            servlet.doPost(req, resp);

            verify(mockDAO).insertQuestion(eq(42L), eq(10), argThat(text -> {
                String sanitized = SecurityUtil.sanitize(xss);
                return sanitized.equals(text) && !text.contains("<script>");
            }));
            verify(session).setAttribute(eq("questionFlash"), anyString());
            verify(resp).sendRedirect("/app/auction/42#questions");
        }

        @Test
        @DisplayName("askerId comes from session only (IDOR prevention)")
        void testAskerIdFromSession() throws Exception {
            stubBuyerSession(99);
            when(req.getParameter("question")).thenReturn("Any defects?");
            when(mockDAO.insertQuestion(eq(42L), eq(99), anyString())).thenReturn(QuestionResult.SUCCESS);

            servlet.doPost(req, resp);

            verify(mockDAO).insertQuestion(eq(42L), eq(99), anyString());
        }
    }

    // =========================================================================
    // POST REPLY — RBAC & ownership
    // =========================================================================

    @Nested
    @DisplayName("POST action=REPLY")
    class ReplyTests {

        @BeforeEach
        void commonReplyParams() {
            when(req.getParameter("action")).thenReturn("REPLY");
            when(req.getParameter("auctionId")).thenReturn("42");
            when(req.getParameter("questionId")).thenReturn("7");
        }

        @Test
        @DisplayName("no session → 403")
        void testReplyNoSession() throws Exception {
            when(req.getSession(false)).thenReturn(null);
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
        }

        @Test
        @DisplayName("BUYER role → 403")
        void testReplyBuyerForbidden() throws Exception {
            stubBuyerSession(10);
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_FORBIDDEN), contains("seller"));
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("blank answer → 400")
        void testReplyBlankAnswer() throws Exception {
            stubSellerSession(5);
            when(req.getParameter("answer")).thenReturn("");
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), contains("required"));
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("wrong seller → 403 (NOT_SELLER)")
        void testReplyWrongSeller403() throws Exception {
            stubSellerSession(5);
            when(req.getParameter("answer")).thenReturn("Yes, it is brand new.");
            when(mockDAO.insertReply(eq(7L), eq(5), anyString())).thenReturn(QuestionResult.NOT_SELLER);

            servlet.doPost(req, resp);

            verify(resp).sendError(eq(HttpServletResponse.SC_FORBIDDEN), contains("own auctions"));
            verify(session, never()).setAttribute(eq("questionFlash"), any());
        }

        @Test
        @DisplayName("successful reply → sanitized answer passed to DAO")
        void testReplySuccessSanitized() throws Exception {
            stubSellerSession(5);
            String xss = "<img onerror=alert(1)>Yes, included.";
            when(req.getParameter("answer")).thenReturn(xss);
            when(mockDAO.insertReply(eq(7L), eq(5), anyString())).thenReturn(QuestionResult.SUCCESS);

            servlet.doPost(req, resp);

            verify(mockDAO).insertReply(eq(7L), eq(5), argThat(text -> {
                String sanitized = SecurityUtil.sanitize(xss);
                return sanitized.equals(text) && !text.contains("<img");
            }));
            verify(session).setAttribute(eq("questionFlash"), anyString());
            verify(resp).sendRedirect("/app/auction/42#questions");
        }

        @Test
        @DisplayName("invalid questionId → 400")
        void testReplyInvalidQuestionId() throws Exception {
            stubSellerSession(5);
            when(req.getParameter("questionId")).thenReturn("xyz");
            when(req.getParameter("answer")).thenReturn("Reply text.");
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), contains("Invalid"));
            verifyNoInteractions(mockDAO);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    @Nested
    @DisplayName("toMessage helper")
    class ToMessageTests {

        @Test
        @DisplayName("SELF_QUESTION message")
        void testSelfQuestionMessage() {
            assertTrue(AuctionQuestionServlet.toMessage(QuestionResult.SELF_QUESTION)
                    .contains("own auction"));
        }

        @Test
        @DisplayName("NOT_SELLER message")
        void testNotSellerMessage() {
            assertTrue(AuctionQuestionServlet.toMessage(QuestionResult.NOT_SELLER)
                    .contains("own auctions"));
        }
    }

    @Nested
    @DisplayName("InputValidator question/answer")
    class ValidatorTests {

        @Test
        @DisplayName("blank question violation")
        void testBlankQuestionViolation() {
            assertNotNull(InputValidator.getQuestionViolation("  "));
        }

        @Test
        @DisplayName("valid question within limit")
        void testValidQuestion() {
            assertNull(InputValidator.getQuestionViolation("Is the battery included?"));
        }
    }
}
