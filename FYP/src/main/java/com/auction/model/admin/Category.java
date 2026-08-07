package com.auction.model.admin;

import java.time.LocalDateTime;

/**
 * Represents a managed auction category (SCRUM-23).
 * Categories are soft-deleted rather than physically removed; {@link #isDeleted()}
 * indicates whether the category is deactivated.
 */
public final class Category {

    private final int id;
    private final String name;
    private final String description;
    private final int displayOrder;
    private final String slug;
    private final boolean deleted;
    private final LocalDateTime createdAt;
    /** Number of auction listings currently referencing this category by name. */
    private final int auctionCount;
    /**
     * Admin-uploaded picture, e.g. {@code /uploads/category/<uuid>.png}, or {@code null}.
     * Null is the normal case: the UI then falls back to a built-in icon matched to the
     * category name, so a picture is an override rather than a requirement.
     */
    private final String imageUrl;

    public Category(int id, String name, String description, int displayOrder,
                    String slug, boolean deleted, LocalDateTime createdAt, int auctionCount) {
        this(id, name, description, displayOrder, slug, deleted, createdAt, auctionCount, null);
    }

    public Category(int id, String name, String description, int displayOrder,
                    String slug, boolean deleted, LocalDateTime createdAt, int auctionCount,
                    String imageUrl) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.displayOrder = displayOrder;
        this.slug = slug;
        this.deleted = deleted;
        this.createdAt = createdAt;
        this.auctionCount = auctionCount;
        this.imageUrl = imageUrl;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getDisplayOrder() { return displayOrder; }
    public String getSlug() { return slug; }
    public boolean isDeleted() { return deleted; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public int getAuctionCount() { return auctionCount; }
    public String getImageUrl() { return imageUrl; }
}
