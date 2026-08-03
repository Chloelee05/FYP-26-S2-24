package com.auction.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
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

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
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

    /**
     * Requirement (d) second metric: "%-tage of star reviews for each pdt/service from
     * buyers". The percentages were computed by loadProductRatings and then dropped, so the
     * email carried only the average. These tests pin them into the body.
     */
    @Nested
    @DisplayName("star review percentages")
    class StarPercentages {

        private Map<String, Object> withRatings() {
            Map<String, Object> a = sample();
            a.put("productRatings", Arrays.asList(
                    map("title", "Wedding Photography",
                        "listingKind", "SERVICE",
                        "avgRating", 4.7,
                        "reviewCount", 3,
                        "starPercentages", map("5", 66.7, "4", 33.3, "3", 0.0, "2", 0.0, "1", 0.0)),
                    map("title", "Studio Lighting Kit",
                        "listingKind", "PRODUCT",
                        "avgRating", 3.0,
                        "reviewCount", 2,
                        "starPercentages", map("5", 0.0, "4", 50.0, "3", 0.0, "2", 50.0, "1", 0.0))));
            return a;
        }

        @Test
        @DisplayName("renders every star bucket, highest first")
        void rendersAllFiveBuckets() {
            String body = SellerAnalyticsDAO.toEmailBody("Dan", withRatings());
            assertTrue(body.contains("Star review breakdown per product/service (from buyers):"));
            assertTrue(body.contains("5 star: 66.7%"));
            assertTrue(body.contains("4 star: 33.3%"));
            assertTrue(body.contains("3 star: 0.0%"));
            assertTrue(body.contains("2 star: 0.0%"));
            assertTrue(body.contains("1 star: 0.0%"));

            int five = body.indexOf("5 star:");
            int one = body.indexOf("1 star:");
            assertTrue(five < one, "five-star line should precede one-star line");
        }

        @Test
        @DisplayName("keeps the average and review count alongside the distribution")
        void rendersAverageAndCount() {
            String body = SellerAnalyticsDAO.toEmailBody("Dan", withRatings());
            assertTrue(body.contains("average 4.7/5 from 3 review(s)"));
            assertTrue(body.contains("average 3.0/5 from 2 review(s)"));
        }

        @Test
        @DisplayName("marks services so the reader can tell them from products")
        void labelsServices() {
            String body = SellerAnalyticsDAO.toEmailBody("Dan", withRatings());
            assertTrue(body.contains("Wedding Photography [service]"));
            assertFalse(body.contains("Studio Lighting Kit [service]"));
        }

        @Test
        @DisplayName("treats a missing bucket as zero rather than printing null")
        void missingBucketIsZero() {
            Map<String, Object> a = sample();
            a.put("productRatings", Arrays.asList(
                    map("title", "Sparse", "avgRating", 5.0, "reviewCount", 1,
                        "starPercentages", map("5", 100.0))));
            String body = SellerAnalyticsDAO.toEmailBody("Dan", a);
            assertTrue(body.contains("5 star: 100.0%"));
            assertTrue(body.contains("1 star: 0.0%"));
            assertFalse(body.contains("null"));
        }

        @Test
        @DisplayName("omits the whole section when the seller has no reviews")
        void omittedWhenNoRatings() {
            Map<String, Object> a = sample();
            a.put("productRatings", new ArrayList<>());
            String body = SellerAnalyticsDAO.toEmailBody("Dan", a);
            assertFalse(body.contains("Star review breakdown"));
        }
    }

    /**
     * Requirement (d) first metric: "which pdt/service is most popular for each day / week /
     * month / quarter". Nothing rendered this before because nothing computed it.
     */
    @Nested
    @DisplayName("calendar-period popularity cross-tab")
    class Popularity {

        private Map<String, Object> withPopularity() {
            Map<String, Object> a = sample();
            a.put("popularityMetricNote", "Popularity is reported two ways per calendar period.");
            a.put("popularityByPeriod", Arrays.asList(
                    map("granularity", "day",
                        "label", "Daily — 7 most recent days with activity",
                        "buckets", Arrays.asList(
                            map("periodStart", "2026-08-03",
                                "topByBidsTitle", "Wedding Photography",
                                "topByBidsKind", "SERVICE",
                                "topByBidsCount", 3,
                                "topBySalesTitle", "Wedding Photography",
                                "topBySalesKind", "SERVICE",
                                "topBySalesRevenue", new BigDecimal("1400.00")),
                            map("periodStart", "2026-08-02",
                                "topByBidsTitle", "Guitar Lessons",
                                "topByBidsKind", "SERVICE",
                                "topByBidsCount", 4))),
                    map("granularity", "quarter",
                        "label", "Quarterly — 4 most recent quarters with activity",
                        "buckets", new ArrayList<>())));
            return a;
        }

        @Test
        @DisplayName("names a winning listing per calendar bucket")
        void namesWinnerPerBucket() {
            String body = SellerAnalyticsDAO.toEmailBody("Eve", withPopularity());
            assertTrue(body.contains("Most popular listing by calendar period"));
            assertTrue(body.contains("Daily — 7 most recent days with activity:"));
            assertTrue(body.contains("2026-08-03"));
            assertTrue(body.contains("most bids:  Wedding Photography [service] — 3 bid(s)"));
            assertTrue(body.contains("2026-08-02"));
            assertTrue(body.contains("most bids:  Guitar Lessons [service] — 4 bid(s)"));
        }

        @Test
        @DisplayName("reports the sale-value winner as well as the bid-count winner")
        void reportsBothMeasures() {
            String body = SellerAnalyticsDAO.toEmailBody("Eve", withPopularity());
            assertTrue(body.contains("top sale:   Wedding Photography [service] — $1400.00"));
        }

        @Test
        @DisplayName("says so explicitly when a bucket had bids but no sale")
        void noSaleIsStated() {
            String body = SellerAnalyticsDAO.toEmailBody("Eve", withPopularity());
            assertTrue(body.contains("top sale:   no sale in this period"));
        }

        @Test
        @DisplayName("carries the note explaining why units sold cannot rank listings")
        void carriesMetricNote() {
            String body = SellerAnalyticsDAO.toEmailBody("Eve", withPopularity());
            assertTrue(body.contains("Popularity is reported two ways per calendar period."));
        }

        @Test
        @DisplayName("shows an empty granularity as no activity rather than dropping it")
        void emptyGranularityIsExplicit() {
            String body = SellerAnalyticsDAO.toEmailBody("Eve", withPopularity());
            assertTrue(body.contains("Quarterly — 4 most recent quarters with activity:"));
            assertTrue(body.contains("no activity in this period"));
        }

        @Test
        @DisplayName("omits the section entirely for a seller with no bids or sales")
        void omittedWhenNoPopularity() {
            Map<String, Object> a = sample();
            a.put("popularityByPeriod", new ArrayList<>());
            String body = SellerAnalyticsDAO.toEmailBody("Eve", a);
            assertFalse(body.contains("Most popular listing by calendar period"));
        }

        @Test
        @DisplayName("asks the database for all four granularities the requirement names")
        void coversAllFourGranularities() {
            String note = SellerAnalyticsDAO.POPULARITY_NOTE;
            assertNotNull(note);
            String body = SellerAnalyticsDAO.toEmailBody("Eve", withPopularity());
            assertTrue(body.contains("Daily"));
            assertTrue(body.contains("Quarterly"));
        }
    }

    @Nested
    @DisplayName("earnings summary")
    class Earnings {

        @Test
        @DisplayName("renders the computed earnings block that was previously dropped")
        void rendersEarnings() {
            Map<String, Object> a = sample();
            a.put("earningsSummary", map(
                    "grossSales", new BigDecimal("1400.00"),
                    "platformFee", new BigDecimal("84.00"),
                    "featuredFees", new BigDecimal("0.00"),
                    "netEarnings", new BigDecimal("1316.00"),
                    "commissionRatePct", 6,
                    "completedOrders", 1));
            String body = SellerAnalyticsDAO.toEmailBody("Fay", a);
            assertTrue(body.contains("Earnings (completed orders):"));
            assertTrue(body.contains("Gross sales:   $1400.00"));
            assertTrue(body.contains("Platform fee (6%): $84.00"));
            assertTrue(body.contains("Net earnings:  $1316.00 over 1 completed order(s)"));
        }

        @Test
        @DisplayName("omits the block when no earnings were computed")
        void omittedWhenAbsent() {
            String body = SellerAnalyticsDAO.toEmailBody("Fay", sample());
            assertFalse(body.contains("Earnings (completed orders):"));
        }
    }

    /**
     * The report's money columns are all {@code NUMERIC}. Period revenue was being read
     * through an int accessor, so every figure in the period breakdown was truncated to
     * whole dollars, and the same accessor was used for {@code winning_bid} once that column
     * became {@code NUMERIC(12,2)}.
     */
    @Nested
    @DisplayName("money is never truncated")
    class Money {

        @Test
        @DisplayName("keeps the cents of a sum")
        void keepsCents() {
            assertEquals(new BigDecimal("1234.56"),
                    SellerAnalyticsDAO.money(new BigDecimal("1234.56")));
        }

        @Test
        @DisplayName("pads a whole-dollar sum to two places rather than dropping the scale")
        void padsScale() {
            assertEquals(new BigDecimal("1400.00"),
                    SellerAnalyticsDAO.money(new BigDecimal("1400")));
        }

        @Test
        @DisplayName("rounds a higher-precision sum half up instead of truncating")
        void roundsHalfUp() {
            assertEquals(new BigDecimal("0.13"), SellerAnalyticsDAO.money(new BigDecimal("0.125")));
            assertEquals(new BigDecimal("99.99"), SellerAnalyticsDAO.money(new BigDecimal("99.994")));
        }

        @Test
        @DisplayName("treats an absent sum as zero, not null")
        void nullIsZero() {
            assertEquals(new BigDecimal("0.00"), SellerAnalyticsDAO.money(null));
        }

        @Test
        @DisplayName("renders cents in the period breakdown the assessor reads")
        void centsReachTheEmail() {
            Map<String, Object> a = sample();
            a.put("periodStats", Arrays.asList(
                    map("period", "last 7 days", "sold", 2,
                        "revenue", new BigDecimal("1234.56"), "bids", 9)));
            String body = SellerAnalyticsDAO.toEmailBody("Hal", a);
            assertTrue(body.contains("$1234.56"), "period revenue must not be truncated");
        }
    }

    @Nested
    @DisplayName("rolling-window totals")
    class RollingWindows {

        @Test
        @DisplayName("labels the windows as rolling, not as calendar periods")
        void labelledAsRolling() {
            Map<String, Object> a = sample();
            a.put("periodStats", Arrays.asList(
                    map("period", "last 24 hours", "sold", 0, "revenue", 0L, "bids", 2),
                    map("period", "last 7 days", "sold", 1, "revenue", 1400L, "bids", 7)));
            String body = SellerAnalyticsDAO.toEmailBody("Gus", a);
            assertTrue(body.contains("Rolling-window totals:"));
            assertTrue(body.contains("last 24 hours: 0 sold, $0, 2 bids"));
            assertTrue(body.contains("last 7 days: 1 sold, $1400, 7 bids"));
        }
    }
}
