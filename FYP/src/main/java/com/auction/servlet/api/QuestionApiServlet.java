package com.auction.servlet.api;

import com.auction.dao.QuestionDAO;
import com.auction.dao.QuestionDAO.QuestionResult;
import com.auction.util.AuthSession;
import com.auction.util.SecurityUtil;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * POST /api/question/ask    — buyer asks a question (params: auctionId, text)
 * POST /api/question/reply  — seller replies (params: questionId, text)
 */
@WebServlet("/api/question/*")
public class QuestionApiServlet extends ApiBase {

    private final QuestionDAO questionDAO = new QuestionDAO();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        AuthSession session = authSession(req);
        String path = req.getPathInfo();

        if (path != null && path.startsWith("/reply")) {
            handleReply(req, resp, session);
        } else {
            handleAsk(req, resp, session);
        }
    }

    private void handleAsk(HttpServletRequest req, HttpServletResponse resp, AuthSession session)
            throws IOException {
        if (!isBuyer(session)) { forbidden(resp); return; }
        int askerId = ((Number) session.getAttribute("userId")).intValue();

        String auctionIdStr = param(req, "auctionId");
        String text         = param(req, "text");
        if (auctionIdStr == null) { badRequest(resp, "auctionId is required."); return; }
        if (text == null || text.isBlank()) { badRequest(resp, "Question text is required."); return; }

        long auctionId;
        try { auctionId = Long.parseLong(auctionIdStr); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid auction ID."); return; }

        String sanitized = SecurityUtil.sanitize(text.trim());
        QuestionResult result = questionDAO.insertQuestion(auctionId, askerId, sanitized);
        if (result == QuestionResult.SUCCESS) {
            okMsg(resp, "Question submitted.");
        } else {
            error(resp, 400, toMessage(result));
        }
    }

    private void handleReply(HttpServletRequest req, HttpServletResponse resp, AuthSession session)
            throws IOException {
        if (!isSeller(session)) { forbidden(resp); return; }
        int sellerId = ((Number) session.getAttribute("userId")).intValue();

        String questionIdStr = param(req, "questionId");
        String text          = param(req, "text");
        if (questionIdStr == null) { badRequest(resp, "questionId is required."); return; }
        if (text == null || text.isBlank()) { badRequest(resp, "Reply text is required."); return; }

        long questionId;
        try { questionId = Long.parseLong(questionIdStr); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid question ID."); return; }

        String sanitized = SecurityUtil.sanitize(text.trim());
        QuestionResult result = questionDAO.insertReply(questionId, sellerId, sanitized);
        if (result == QuestionResult.SUCCESS) {
            com.auction.notification.NotificationService.notifyQuestionAnsweredByQuestionId(questionId);
            okMsg(resp, "Reply posted.");
        } else {
            error(resp, 400, toMessage(result));
        }
    }

    private String toMessage(QuestionResult r) {
        switch (r) {
            case AUCTION_NOT_FOUND:  return "Auction not found.";
            case SELF_QUESTION:      return "You cannot ask a question on your own listing.";
            case AUCTION_CLOSED:     return "This auction is no longer accepting questions.";
            case QUESTION_NOT_FOUND: return "Question not found.";
            case NOT_SELLER:         return "You do not own this auction.";
            case ALREADY_ANSWERED:   return "This question has already been answered.";
            default:                 return "Could not process request. Please try again.";
        }
    }
}
