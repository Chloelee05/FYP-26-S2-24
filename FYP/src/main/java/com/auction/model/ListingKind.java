package com.auction.model;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Whether a listing is a physical product or a service, mirroring the
 * {@code auction_details.listing_kind} column and its CHECK constraint
 * (migration_admin_management.sql).
 *
 * <p>The minimum requirements name "products, services, customers, auction transactions" for
 * both the seller's record-keeping and the admin's database management, so the discriminator
 * has to be settable by whoever creates the record — not only correctable by an admin
 * afterwards. This enum is where the two permitted values live, so the seller-facing create
 * and edit paths and {@link com.auction.dao.AdminManagementDAO} cannot drift from each other
 * or from the constraint.</p>
 *
 * <p>{@link #PRODUCT} is the default because every row that existed before the column did is
 * a physical good, and the column's own DEFAULT says so.</p>
 */
public enum ListingKind {
    PRODUCT("Product"),
    SERVICE("Service");

    /** What a row holds when nobody has said otherwise, matching the column's DEFAULT. */
    public static final ListingKind DEFAULT = PRODUCT;

    private final String displayName;

    ListingKind(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    /** The stored values, for callers that validate a string against the constraint. */
    public static List<String> names() {
        return Stream.of(values()).map(Enum::name).collect(Collectors.toList());
    }

    /**
     * Parses a stored or submitted value, tolerating surrounding space and case.
     *
     * @return the matching kind, or {@code null} when {@code raw} is not one of them.
     *         Null rather than an exception because both callers turn an unrecognised kind
     *         into a 400 rather than a 500, and a null kind means "not supplied" to them too.
     */
    public static ListingKind parse(String raw) {
        if (raw == null) return null;
        String key = raw.trim().toUpperCase(Locale.ROOT);
        for (ListingKind kind : values()) {
            if (kind.name().equals(key)) return kind;
        }
        return null;
    }

    /** True when {@code raw} names a service; anything else, including null, is a product. */
    public static boolean isService(String raw) {
        return parse(raw) == SERVICE;
    }
}
