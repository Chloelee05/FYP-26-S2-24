package com.auction.model.admin;

import com.auction.model.Role;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

/**
 * User row for the admin moderation table (wireframe: User Moderation).
 */
public final class AdminUserSummary {
    private final int id;
    private final String username;
    private final String email;
    private final Role role;
    private final int statusId;
    private final LocalDate joined;
    private final int bidCount;
    private final int listingCount;
    /** Whether this account may list items for sale (merged buyer/seller accounts). */
    private final boolean canSell;

    public AdminUserSummary(int id, String username, String email, Role role, int statusId,
                            LocalDate joined, int bidCount, int listingCount) {
        this(id, username, email, role, statusId, joined, bidCount, listingCount, false);
    }

    public AdminUserSummary(int id, String username, String email, Role role, int statusId,
                            LocalDate joined, int bidCount, int listingCount, boolean canSell) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.role = role;
        this.statusId = statusId;
        this.joined = joined;
        this.bidCount = bidCount;
        this.listingCount = listingCount;
        this.canSell = canSell;
    }

    public int getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public Role getRole() {
        return role;
    }

    public int getStatusId() {
        return statusId;
    }

    public LocalDate getJoined() {
        return joined;
    }

    public int getBidCount() {
        return bidCount;
    }

    public int getListingCount() {
        return listingCount;
    }

    /**
     * Whether this account may list items for sale. True for accounts that opted in,
     * and for pre-merge accounts still carrying the SELLER role.
     *
     * <p>Explicitly mapped because the admin table distinguishes accounts by
     * capability, not by role, and this is not a bean-style getter name.</p>
     */
    @JsonProperty("canSell")
    public boolean canSell() {
        return canSell || role == Role.SELLER;
    }
}
