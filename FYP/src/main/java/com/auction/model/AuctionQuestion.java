package com.auction.model;

import java.time.Instant;

/**
 * Read-only projection of a buyer question (and optional seller reply) on an auction
 * listing (SCRUM-62). Public display uses masked asker username — no PII beyond username.
 */
public final class AuctionQuestion {

    private final long id;
    private final long auctionId;
    private final String askerUsername;
    private final String questionText;
    private final String answerText;
    private final Instant askedAt;
    private final Instant answeredAt;

    public AuctionQuestion(long id, long auctionId, String askerUsername,
                           String questionText, String answerText,
                           Instant askedAt, Instant answeredAt) {
        this.id = id;
        this.auctionId = auctionId;
        this.askerUsername = askerUsername;
        this.questionText = questionText;
        this.answerText = answerText;
        this.askedAt = askedAt;
        this.answeredAt = answeredAt;
    }

    public long getId() { return id; }
    public long getAuctionId() { return auctionId; }
    public String getAskerUsername() { return askerUsername; }
    public String getQuestionText() { return questionText; }
    public String getAnswerText() { return answerText; }
    public Instant getAskedAt() { return askedAt; }
    public Instant getAnsweredAt() { return answeredAt; }
    public boolean isAnswered() { return answerText != null && !answerText.isBlank(); }

    /** JSP-friendly date for {@code fmt:formatDate}. */
    public java.util.Date getAskedAtDate() {
        return askedAt == null ? null : java.util.Date.from(askedAt);
    }

    /** JSP-friendly date for {@code fmt:formatDate}. */
    public java.util.Date getAnsweredAtDate() {
        return answeredAt == null ? null : java.util.Date.from(answeredAt);
    }
}
