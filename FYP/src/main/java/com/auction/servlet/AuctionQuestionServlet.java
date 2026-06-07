package com.auction.servlet;

import com.auction.dao.QuestionDAO;
import com.auction.dao.QuestionDAO.QuestionResult;
import com.auction.util.InputValidator;
import com.auction.util.RbacUtil;
import com.auction.util.SecurityUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Auction Q&A endpoint (SCRUM-62).
 *
 * <ul>
 *   <li>{@code GET /auction-question?auctionId=} — public list; redirects to
 *       {@code /auction/{id}#questions} where questions are rendered.</li>
 *   <li>{@code POST /protected/auction-question} — authenticated ask ({@code action=ASK})
 *       or reply ({@code action=REPLY}).</li>
 * </ul>
 *
 * <p><b>Security (SCRUM-353):</b>
 * <ul>
 *   <li>ASK: authenticated buyer only; {@code askerId} from session; seller cannot ask on own
 *       auction.</li>
 *   <li>REPLY: authenticated seller only; ownership verified in {@link QuestionDAO}.</li>
 *   <li>Wrong seller reply → HTTP 403.</li>
 *   <li>{@code auctionId} / {@code questionId} parsed as {@code long}.</li>
 *   <li>Question and answer text sanitized via {@link SecurityUtil#sanitize(String)}.</li>
 * </ul>
 * </p>
 */
@WebServlet(urlPatterns = {"/auction-question", "/protected/auction-question"})
public class AuctionQuestionServlet extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger(AuctionQuestionServlet.class.getName());

    public static final String ACTION_ASK   = "ASK";
    public static final String ACTION_REPLY = "REPLY";

    private QuestionDAO questionDAO;

    public AuctionQuestionServlet() {
        this.questionDAO = new QuestionDAO();
    }

    public AuctionQuestionServlet(QuestionDAO questionDAO) {
        this.questionDAO = questionDAO;
    }

    /** GET — public question list; redirects to the auction detail anchor. */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        long auctionId = parseAuctionIdParam(req, resp);
        if (auctionId < 0) return;

        // Verify at least one question exists OR auction is reachable — lightweight existence check
        try {
            questionDAO.listByAuction(auctionId);
        } catch (RuntimeException e) {
            LOGGER.severe("listByAuction error [auctionId=" + auctionId + "]: " + e.getMessage());
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        resp.sendRedirect(req.getContextPath() + "/auction/" + auctionId + "#questions");
    }

    /** POST — ask a question (buyer) or post a reply (seller). */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        HttpSession session = req.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Authentication required.");
            return;
        }

        String action = req.getParameter("action");
        if (ACTION_ASK.equalsIgnoreCase(action)) {
            handleAsk(req, resp, session);
        } else if (ACTION_REPLY.equalsIgnoreCase(action)) {
            handleReply(req, resp, session);
        } else {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid action.");
        }
    }

    // =========================================================================
    // ASK — buyer only
    // =========================================================================

    private void handleAsk(HttpServletRequest req, HttpServletResponse resp, HttpSession session)
            throws IOException {

        if (!RbacUtil.isBuyer(session)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Only buyers may ask questions.");
            return;
        }

        int askerId = ((Number) session.getAttribute("userId")).intValue();

        long auctionId = parseAuctionIdParam(req, resp);
        if (auctionId < 0) return;

        String rawQuestion = req.getParameter("question");
        if (rawQuestion == null || rawQuestion.isBlank()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Question text is required.");
            return;
        }

        String violation = InputValidator.getQuestionViolation(rawQuestion);
        if (violation != null) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, violation);
            return;
        }

        String questionText = SecurityUtil.sanitize(rawQuestion);

        QuestionResult result;
        try {
            result = questionDAO.insertQuestion(auctionId, askerId, questionText);
        } catch (RuntimeException e) {
            LOGGER.severe(String.format("insertQuestion error [auctionId=%d, askerId=%d]: %s",
                    auctionId, askerId, e.getMessage()));
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        if (result == QuestionResult.SUCCESS) {
            LOGGER.info(String.format("Question posted [auctionId=%d, askerId=%d].", auctionId, askerId));
            session.setAttribute("questionFlash", "Your question has been posted.");
        } else {
            session.setAttribute("questionFlashError", toMessage(result));
        }

        resp.sendRedirect(req.getContextPath() + "/auction/" + auctionId + "#questions");
    }

    // =========================================================================
    // REPLY — seller only, ownership checked in DAO
    // =========================================================================

    private void handleReply(HttpServletRequest req, HttpServletResponse resp, HttpSession session)
            throws IOException {

        if (!RbacUtil.isSeller(session)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Only sellers may reply to questions.");
            return;
        }

        int sellerId = ((Number) session.getAttribute("userId")).intValue();

        long auctionId = parseAuctionIdParam(req, resp);
        if (auctionId < 0) return;

        String questionIdStr = req.getParameter("questionId");
        if (questionIdStr == null || questionIdStr.isBlank()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "questionId is required.");
            return;
        }
        long questionId;
        try {
            questionId = Long.parseLong(questionIdStr.trim());
        } catch (NumberFormatException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid question ID.");
            return;
        }

        String rawAnswer = req.getParameter("answer");
        if (rawAnswer == null || rawAnswer.isBlank()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Answer text is required.");
            return;
        }

        String violation = InputValidator.getAnswerViolation(rawAnswer);
        if (violation != null) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, violation);
            return;
        }

        String answerText = SecurityUtil.sanitize(rawAnswer);

        QuestionResult result;
        try {
            result = questionDAO.insertReply(questionId, sellerId, answerText);
        } catch (RuntimeException e) {
            LOGGER.severe(String.format("insertReply error [questionId=%d, sellerId=%d]: %s",
                    questionId, sellerId, e.getMessage()));
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        if (result == QuestionResult.NOT_SELLER) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "You may only reply to questions on your own auctions.");
            return;
        }

        if (result == QuestionResult.SUCCESS) {
            LOGGER.info(String.format("Reply posted [questionId=%d, sellerId=%d].", questionId, sellerId));
            session.setAttribute("questionFlash", "Your reply has been posted.");
        } else {
            session.setAttribute("questionFlashError", toMessage(result));
        }

        resp.sendRedirect(req.getContextPath() + "/auction/" + auctionId + "#questions");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Parses {@code auctionId} from the request. On failure sends 400 and returns {@code -1}.
     */
    public static long parseAuctionIdParam(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        String raw = req.getParameter("auctionId");
        if (raw == null || raw.isBlank()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "auctionId is required.");
            return -1;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid auction ID.");
            return -1;
        }
    }

    public static String toMessage(QuestionResult result) {
        switch (result) {
            case AUCTION_NOT_FOUND:  return "Auction not found.";
            case SELF_QUESTION:      return "You cannot ask a question on your own auction.";
            case AUCTION_CLOSED:     return "This auction is no longer accepting questions.";
            case QUESTION_NOT_FOUND: return "Question not found.";
            case NOT_SELLER:         return "You may only reply to questions on your own auctions.";
            case ALREADY_ANSWERED:   return "This question has already been answered.";
            default:                 return "Could not complete request. Please try again.";
        }
    }
}
