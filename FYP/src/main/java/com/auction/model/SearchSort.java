package com.auction.model;

/**
 * Whitelist of allowed search-result sort orders (SCRUM-60 / SCRUM-349).
 *
 * <p>Each constant maps a client {@code sortBy} parameter value to a fixed SQL
 * {@code ORDER BY} fragment. The fragments are hard-coded in Java and are never
 * built from user input — this prevents ORDER BY injection.</p>
 *
 * <p>Invalid or missing {@code sortBy} values resolve to {@link #DEFAULT} ({@code newest}).</p>
 */
public enum SearchSort {

    /** Newly listed first — default when sortBy is absent or invalid. */
    NEWEST("newest"),

    /** Auctions ending soonest first. */
    ENDING_SOON("endingSoon"),

    /** Lowest current price first. */
    PRICE_LOW("priceLow"),

    /** Highest current price first. */
    PRICE_HIGH("priceHigh");

    /** Default sort applied when sortBy is missing or invalid (SCRUM-349). */
    public static final SearchSort DEFAULT = NEWEST;

    private final String paramValue;

    SearchSort(String paramValue) {
        this.paramValue = paramValue;
    }

    /** Client-facing parameter value (e.g. {@code "endingSoon"}). */
    public String getParamValue() {
        return paramValue;
    }

    /**
     * Fixed {@code ORDER BY} clause for a simple (non price-wrapped) search query.
     * Column references use table aliases from the inner query.
     */
    public String orderBySimple() {
        switch (this) {
            case ENDING_SOON: return "ORDER BY a.date_end ASC ";
            case PRICE_LOW:   return "ORDER BY current_price ASC ";
            case PRICE_HIGH:  return "ORDER BY current_price DESC ";
            case NEWEST:
            default:          return "ORDER BY a.date_created DESC ";
        }
    }

    /**
     * Fixed {@code ORDER BY} clause for a price-wrapped derived-table query.
     * Column references use aliases from the inner SELECT list.
     */
    public String orderByWrapped() {
        switch (this) {
            case ENDING_SOON: return "ORDER BY date_end ASC ";
            case PRICE_LOW:   return "ORDER BY current_price ASC ";
            case PRICE_HIGH:  return "ORDER BY current_price DESC ";
            case NEWEST:
            default:          return "ORDER BY date_created DESC ";
        }
    }

    /**
     * Resolves a raw {@code sortBy} request parameter against the enum whitelist.
     *
     * @param raw client parameter; may be {@code null} or blank
     * @return matching {@link SearchSort}, or {@link #DEFAULT} when absent/unknown (SCRUM-349)
     */
    public static SearchSort fromParam(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT;
        String trimmed = raw.trim();
        for (SearchSort sort : values()) {
            if (sort.paramValue.equals(trimmed)) return sort;
        }
        return DEFAULT;
    }
}
