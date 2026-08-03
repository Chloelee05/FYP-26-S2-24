package com.auction.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The product/service value set.
 *
 * <p>Small, but load-bearing in two directions: it is the only definition of what
 * {@code auction_details.listing_kind} may hold, so it has to stay in step with the
 * {@code auction_details_listing_kind_check} constraint; and everything that reads a kind —
 * the seller's create and edit forms, the seller's listing table, the public auction page and
 * admin listing management — resolves an unrecognised or missing value through here, so
 * "anything that is not a service is a product" has to hold in one place rather than five.</p>
 */
@DisplayName("ListingKind")
class ListingKindTest {

    @Test
    @DisplayName("holds exactly the two values the CHECK constraint permits")
    void twoValuesOnly() {
        assertEquals(2, ListingKind.values().length);
        assertEquals(java.util.List.of("PRODUCT", "SERVICE"), ListingKind.names());
    }

    @Test
    @DisplayName("defaults to PRODUCT, matching the column's own DEFAULT")
    void defaultsToProduct() {
        assertEquals(ListingKind.PRODUCT, ListingKind.DEFAULT);
    }

    @ParameterizedTest
    @CsvSource({
            "PRODUCT, PRODUCT",
            "SERVICE, SERVICE",
            "product, PRODUCT",
            "service, SERVICE",
            "Service, SERVICE",
            "'  SERVICE  ', SERVICE",
    })
    @DisplayName("parses either value regardless of case or surrounding space")
    void parsesLeniently(String raw, ListingKind expected) {
        assertEquals(expected, ListingKind.parse(raw));
    }

    /**
     * Null rather than an exception: both callers turn an unrecognised kind into a 400 rather
     * than a 500, and for the edit path a null kind is also how "the client said nothing"
     * arrives — so one return value covers both without a try/catch at either call site.
     */
    @ParameterizedTest
    @ValueSource(strings = { "GOODS", "SERVICES", "PRODUCTS", "OTHER", "'; DROP TABLE users; --" })
    @DisplayName("returns null for anything else instead of throwing")
    void unknownParsesToNull(String raw) {
        assertNull(ListingKind.parse(raw));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("a null or empty kind does not parse")
    void nullAndEmptyParseToNull(String raw) {
        assertNull(ListingKind.parse(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = { "SERVICE", "service", " Service " })
    @DisplayName("isService recognises a service")
    void isServiceTrue(String raw) {
        assertTrue(ListingKind.isService(raw));
    }

    /** A legacy row, an older API response and a typo all read as a product. */
    @ParameterizedTest
    @ValueSource(strings = { "PRODUCT", "", "SERVICES", "anything" })
    @DisplayName("everything that is not a service is a product")
    void isServiceFalse(String raw) {
        assertFalse(ListingKind.isService(raw));
    }

    @Test
    @DisplayName("a null kind is not a service")
    void nullIsNotService() {
        assertFalse(ListingKind.isService(null));
    }

    @Test
    @DisplayName("displays as sentence case for the forms and badges")
    void displayNames() {
        assertEquals("Product", ListingKind.PRODUCT.getDisplayName());
        assertEquals("Service", ListingKind.SERVICE.getDisplayName());
    }
}
