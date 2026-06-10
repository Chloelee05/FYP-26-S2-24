package com.auction.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@DisplayName("SellerAnalyticsDAO.toEmailBody – report rendering")
class SellerAnalyticsEmailTest {

    private Map<String, Object> sample() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("totalListings", 10);
        a.put("activeListings", 3);
        a.put("soldCount", 5);
        a.put("totalRevenue", 4200L);
        a.put("avgSalePrice", 840.0);
        a.put("sellThroughRate", 50.0);
        a.put("bidsReceived", 37);

        List<Map<String, Object>> top = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", "Vintage Watch");
        row.put("bidCount", 12);
        row.put("topBid", new BigDecimal("1500"));
        top.add(row);
        a.put("topListings", top);
        return a;
    }

    @Test
    @DisplayName("includes the seller name and all key metrics")
    void rendersMetrics() {
        String body = SellerAnalyticsDAO.toEmailBody("Alice", sample());
        assertTrue(body.contains("Hi Alice"));
        assertTrue(body.contains("Total listings: 10"));
        assertTrue(body.contains("Items sold: 5"));
        assertTrue(body.contains("$4200"));
        assertTrue(body.contains("Sell-through rate: 50.0%"));
        assertTrue(body.contains("Total bids received: 37"));
    }

    @Test
    @DisplayName("lists top listings when present")
    void rendersTopListings() {
        String body = SellerAnalyticsDAO.toEmailBody("Bob", sample());
        assertTrue(body.contains("Top listings by bids:"));
        assertTrue(body.contains("Vintage Watch"));
        assertTrue(body.contains("12 bids"));
    }

    @Test
    @DisplayName("omits top-listings section when empty")
    void emptyTopListings() {
        Map<String, Object> a = sample();
        a.put("topListings", new ArrayList<>());
        String body = SellerAnalyticsDAO.toEmailBody("Cara", a);
        assertFalse(body.contains("Top listings by bids:"));
    }
}
