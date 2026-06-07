package com.auction.dao;

import com.auction.model.AuctionQuestion;
import com.auction.util.DBUtil;
import com.auction.util.SecurityUtil;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-access layer for buyer questions and seller replies on auction listings (SCRUM-62).
 *
 * <p><b>IDOR prevention:</b> {@code askerId} and {@code sellerId} are always supplied by the
 * servlet from the session — never from request parameters naming another user. Seller ownership
 * for replies is verified by joining {@code auction_questions} to {@code auction} inside the
 * DAO.</p>
 *
 * <p><b>Self-question guard:</b> A buyer who is the auction's seller cannot ask a question on
 * their own listing ({@link QuestionResult#SELF_QUESTION}).</p>
 */
public class QuestionDAO {

    /** Outcome codes for question/reply operations. */
    public enum QuestionResult {
        SUCCESS,
        AUCTION_NOT_FOUND,
        /** Buyer is the seller of this auction. */
        SELF_QUESTION,
        /** Auction is closed or not active for Q&A. */
        AUCTION_CLOSED,
        QUESTION_NOT_FOUND,
        /** Replying user is not the seller who owns the auction. */
        NOT_SELLER,
        /** Question already has a seller reply. */
        ALREADY_ANSWERED
    }

    /**
     * Returns all questions (with optional replies) for an auction, oldest first.
     * Asker usernames are masked for public display.
     *
     * @param auctionId auction primary key
     * @return ordered list; empty if none
     */
    public List<AuctionQuestion> listByAuction(long auctionId) {
        String sql =
                "SELECT q.id, q.auction_id, u.username AS asker_username, "
                + "       q.question_text, q.answer_text, q.created_at, q.answered_at "
                + "FROM auction_questions q "
                + "JOIN users u ON u.id = q.asker_user_id "
                + "WHERE q.auction_id = ? "
                + "ORDER BY q.created_at ASC";

        List<AuctionQuestion> list = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRow(rs));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return list;
    }

    /**
     * Inserts a buyer question on an open, active auction.
     *
     * @param auctionId     target auction (validated as {@code long} by servlet)
     * @param askerId       buyer user id from session only
     * @param questionText  already sanitized question text
     */
    public QuestionResult insertQuestion(long auctionId, int askerId, String questionText) {
        Connection conn = null;
        try {
            conn = DBUtil.connectDB();
            conn.setAutoCommit(false);

            int sellerId;
            Timestamp dateEnd;
            String moderationState;
            String checkSql =
                    "SELECT seller_id, date_end, moderation_state FROM auction WHERE auction_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setLong(1, auctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return QuestionResult.AUCTION_NOT_FOUND;
                    }
                    sellerId = rs.getInt("seller_id");
                    dateEnd = rs.getTimestamp("date_end");
                    moderationState = rs.getString("moderation_state");
                }
            }

            if (sellerId == askerId) {
                conn.rollback();
                return QuestionResult.SELF_QUESTION;
            }

            if (!"active".equalsIgnoreCase(moderationState)
                    || dateEnd == null || !dateEnd.after(new Timestamp(System.currentTimeMillis()))) {
                conn.rollback();
                return QuestionResult.AUCTION_CLOSED;
            }

            String insertSql =
                    "INSERT INTO auction_questions (auction_id, asker_user_id, question_text) "
                    + "VALUES (?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setLong(1, auctionId);
                ps.setInt(2, askerId);
                ps.setString(3, questionText);
                ps.executeUpdate();
            }

            conn.commit();
            return QuestionResult.SUCCESS;
        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ignored) { }
            throw new RuntimeException(e);
        } finally {
            if (conn != null) try { conn.setAutoCommit(true); conn.close(); } catch (SQLException ignored) { }
        }
    }

    /**
     * Inserts a seller reply on an existing unanswered question.
     *
     * @param questionId  question primary key (parsed as {@code long} by servlet)
     * @param sellerId    seller user id from session only
     * @param answerText  already sanitized answer text
     */
    public QuestionResult insertReply(long questionId, int sellerId, String answerText) {
        Connection conn = null;
        try {
            conn = DBUtil.connectDB();
            conn.setAutoCommit(false);

            long auctionId;
            int ownerSellerId;
            String existingAnswer;
            String loadSql =
                    "SELECT q.auction_id, q.answer_text, a.seller_id "
                    + "FROM auction_questions q "
                    + "JOIN auction a ON a.auction_id = q.auction_id "
                    + "WHERE q.id = ?";
            try (PreparedStatement ps = conn.prepareStatement(loadSql)) {
                ps.setLong(1, questionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return QuestionResult.QUESTION_NOT_FOUND;
                    }
                    auctionId = rs.getLong("auction_id");
                    ownerSellerId = rs.getInt("seller_id");
                    existingAnswer = rs.getString("answer_text");
                }
            }

            if (ownerSellerId != sellerId) {
                conn.rollback();
                return QuestionResult.NOT_SELLER;
            }

            if (existingAnswer != null && !existingAnswer.isBlank()) {
                conn.rollback();
                return QuestionResult.ALREADY_ANSWERED;
            }

            String updateSql =
                    "UPDATE auction_questions "
                    + "SET answer_text = ?, answered_at = CURRENT_TIMESTAMP "
                    + "WHERE id = ? AND (answer_text IS NULL OR answer_text = '')";
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setString(1, answerText);
                ps.setLong(2, questionId);
                if (ps.executeUpdate() != 1) {
                    conn.rollback();
                    return QuestionResult.ALREADY_ANSWERED;
                }
            }

            conn.commit();
            return QuestionResult.SUCCESS;
        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ignored) { }
            throw new RuntimeException(e);
        } finally {
            if (conn != null) try { conn.setAutoCommit(true); conn.close(); } catch (SQLException ignored) { }
        }
    }

    private static AuctionQuestion mapRow(ResultSet rs) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp answered = rs.getTimestamp("answered_at");
        String rawUsername = rs.getString("asker_username");
        return new AuctionQuestion(
                rs.getLong("id"),
                rs.getLong("auction_id"),
                SecurityUtil.maskUsername(rawUsername != null ? rawUsername : "user"),
                rs.getString("question_text"),
                rs.getString("answer_text"),
                created != null ? created.toInstant() : null,
                answered != null ? answered.toInstant() : null);
    }
}
