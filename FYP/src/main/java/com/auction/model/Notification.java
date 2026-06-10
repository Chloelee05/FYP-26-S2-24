package com.auction.model;

import java.time.Instant;

/** A single in-app notification row for a user. */
public final class Notification {

    private final long id;
    private final String type;
    private final String message;
    private final String link;
    private final boolean read;
    private final Instant createdAt;

    public Notification(long id, String type, String message, String link, boolean read, Instant createdAt) {
        this.id = id;
        this.type = type;
        this.message = message;
        this.link = link;
        this.read = read;
        this.createdAt = createdAt;
    }

    public long getId()         { return id; }
    public String getType()     { return type; }
    public String getMessage()  { return message; }
    public String getLink()     { return link; }
    public boolean isRead()     { return read; }
    public Instant getCreatedAt() { return createdAt; }
}
