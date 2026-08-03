import com.auction.dao.AuctionDAO;
import com.auction.dao.SellerAuctionDAO;
import com.auction.dao.SellerAuctionDAO.ReduceQtyResult;
import com.auction.model.Auction;
import com.auction.notification.NotificationService;
import com.auction.servlet.api.SellerApiServlet;
import com.auction.test.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Seller record maintenance through {@code /api/seller/*}: the audit gaps in minimum
 * requirements Seller (b), (c) and (d).
 *
 * <p>Covers the endpoint contracts rather than the DAO SQL, which
 * {@link TestSellerAuctionDAO} pins separately — the validation that used to be missing all
 * lived in the servlet, and two of these gaps (a 500 on a cleared description, a Buy It Now
 * below the starting bid) were reproducible only from this layer.</p>
 */
@DisplayName("Seller listing maintenance API")
class TestSellerListingMaintenance {

    /** Exposes the protected doGet/doPost hooks for direct invocation. */
    private static class Wrapper extends SellerApiServlet {
        @Override public void doGet(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doGet(req, resp);
        }
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doPost(req, resp);
        }
    }

    private static final int SELLER_ID = 42;

    private Wrapper servlet;
    private SellerAuctionDAO sellerDao;
    private AuctionDAO mainDao;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private StringWriter body;

    @BeforeEach
    void setUp() throws Exception {
        sellerDao = mock(SellerAuctionDAO.class);
        mainDao   = mock(AuctionDAO.class);
        servlet   = new Wrapper();
        servlet.setSellerAuctionDAO(sellerDao);
        servlet.setAuctionDAO(mainDao);

        req  = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
        body = ApiTestSupport.bindJsonWriter(resp);
        ApiTestSupport.withBearer(req, ApiTestSupport.newSellingBuyerSession(SELLER_ID));
    }

    private void path(String p) {
        when(req.getPathInfo()).thenReturn(p);
    }

    private void param(String name, String value) {
        when(req.getParameter(name)).thenReturn(value);
    }

    private JsonNode json() throws Exception {
        return ApiTestSupport.parse(body);
    }

    private String error() throws Exception {
        JsonNode e = json().get("error");
        return e == null ? "" : e.asText();
    }

    // ── Seller (d): cancellation records a reason and tells the bidders ────────

    @Nested
    @DisplayName("POST /cancel – a reason, and the bidders being told")
    class Cancel {

        @BeforeEach
        void cancelPath() {
            path("/cancel");
            param("auctionId", "7");
        }

        @Test
        @DisplayName("no reason → 400, and nothing is cancelled")
        void reasonIsRequired() throws Exception {
            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            assertTrue(error().contains("reason"), error());
            verify(sellerDao, never()).cancelAuction(anyLong(), anyInt(), anyString());
        }

        @Test
        @DisplayName("blank reason → 400 (a space is not an explanation)")
        void blankReasonRejected() throws Exception {
            param("reason", "   ");

            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            verify(sellerDao, never()).cancelAuction(anyLong(), anyInt(), anyString());
        }

        @Test
        @DisplayName("over-long reason → 400 rather than a truncated database write")
        void overLongReasonRejected() throws Exception {
            param("reason", "x".repeat(301));

            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            assertTrue(error().contains("300"), error());
            verify(sellerDao, never()).cancelAuction(anyLong(), anyInt(), anyString());
        }

        @Test
        @DisplayName("the reason reaches the DAO, so cancel_reason is no longer always NULL")
        void reasonIsPersisted() throws Exception {
            param("reason", "No bids received");
            when(sellerDao.cancelAuction(7L, SELLER_ID, "No bids received")).thenReturn(true);

            try (MockedStatic<NotificationService> notify = mockStatic(NotificationService.class)) {
                servlet.doPost(req, resp);

                verify(resp).setStatus(200);
                verify(sellerDao).cancelAuction(7L, SELLER_ID, "No bids received");
                notify.verify(() -> NotificationService.notifyAuctionCancelled(7L, "No bids received"));
            }
        }

        @Test
        @DisplayName("bidders are notified – previously nobody was told at all")
        void biddersAreNotified() throws Exception {
            param("reason", "Item was damaged");
            when(sellerDao.cancelAuction(anyLong(), anyInt(), anyString())).thenReturn(true);

            try (MockedStatic<NotificationService> notify = mockStatic(NotificationService.class)) {
                servlet.doPost(req, resp);

                notify.verify(() -> NotificationService.notifyAuctionCancelled(7L, "Item was damaged"));
                assertTrue(json().get("message").asText().contains("notified"));
            }
        }

        @Test
        @DisplayName("a refused cancellation notifies nobody")
        void refusedCancelSendsNothing() throws Exception {
            param("reason", "No bids received");
            when(sellerDao.cancelAuction(anyLong(), anyInt(), anyString())).thenReturn(false);

            try (MockedStatic<NotificationService> notify = mockStatic(NotificationService.class)) {
                servlet.doPost(req, resp);

                verify(resp).setStatus(400);
                notify.verifyNoInteractions();
            }
        }

        @Test
        @DisplayName("a buyer who cannot sell is still refused")
        void nonSellerForbidden() throws Exception {
            ApiTestSupport.withBearer(req, ApiTestSupport.newBuyerSession(SELLER_ID));
            param("reason", "No bids received");

            servlet.doPost(req, resp);

            verify(resp).setStatus(403);
            verify(sellerDao, never()).cancelAuction(anyLong(), anyInt(), anyString());
        }
    }

    // ── Seller (d): removing items, including the last one ────────────────────

    @Nested
    @DisplayName("POST /reduce-quantity – removing items")
    class RemoveUnit {

        @BeforeEach
        void removePath() {
            path("/reduce-quantity");
            param("auctionId", "7");
        }

        @Test
        @DisplayName("a plain decrement succeeds and notifies nobody")
        void plainDecrement() throws Exception {
            when(sellerDao.removeUnit(eq(7L), eq(SELLER_ID), anyString()))
                    .thenReturn(ReduceQtyResult.SUCCESS);

            try (MockedStatic<NotificationService> notify = mockStatic(NotificationService.class)) {
                servlet.doPost(req, resp);

                verify(resp).setStatus(200);
                notify.verifyNoInteractions();
            }
        }

        @Test
        @DisplayName("removing the last unit ends the listing and notifies the bidders")
        void lastUnitEndsListingAndNotifies() throws Exception {
            param("reason", "Item is no longer available");
            when(sellerDao.removeUnit(7L, SELLER_ID, "Item is no longer available"))
                    .thenReturn(ReduceQtyResult.LISTING_ENDED);

            try (MockedStatic<NotificationService> notify = mockStatic(NotificationService.class)) {
                servlet.doPost(req, resp);

                verify(resp).setStatus(200);
                assertTrue(json().get("message").asText().contains("cancelled"));
                notify.verify(() -> NotificationService.notifyAuctionCancelled(
                        7L, "Item is no longer available"));
            }
        }

        @Test
        @DisplayName("an unusable reason is refused before the write, not after the listing ends")
        void badReasonRejectedUpFront() throws Exception {
            param("reason", "x".repeat(400));

            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            verify(sellerDao, never()).removeUnit(anyLong(), anyInt(), anyString());
        }

        @Test
        @DisplayName("an empty listing is a 400, not a crash")
        void alreadyEmpty() throws Exception {
            when(sellerDao.removeUnit(anyLong(), anyInt(), anyString()))
                    .thenReturn(ReduceQtyResult.ALREADY_EMPTY);

            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            assertTrue(error().contains("no items left"), error());
        }

        @Test
        @DisplayName("someone else's listing is 403, a missing one is 404")
        void ownershipAndExistence() throws Exception {
            when(sellerDao.removeUnit(anyLong(), anyInt(), anyString()))
                    .thenReturn(ReduceQtyResult.NOT_OWNER);
            servlet.doPost(req, resp);
            verify(resp).setStatus(403);

            setUp();
            path("/reduce-quantity");
            param("auctionId", "7");
            when(sellerDao.removeUnit(anyLong(), anyInt(), anyString()))
                    .thenReturn(ReduceQtyResult.NOT_FOUND);
            servlet.doPost(req, resp);
            verify(resp).setStatus(404);
        }

        @Test
        @DisplayName("even with no reason supplied, the cancellation records one")
        void defaultReasonIsNeverNull() throws Exception {
            when(sellerDao.removeUnit(anyLong(), anyInt(), anyString()))
                    .thenReturn(ReduceQtyResult.LISTING_ENDED);

            try (MockedStatic<NotificationService> ignored = mockStatic(NotificationService.class)) {
                servlet.doPost(req, resp);
            }

            ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
            verify(sellerDao).removeUnit(eq(7L), eq(SELLER_ID), reason.capture());
            assertFalse(reason.getValue() == null || reason.getValue().isBlank());
        }
    }

    // ── Seller (b): editing the record ───────────────────────────────────────

    @Nested
    @DisplayName("POST /edit – maintaining the record")
    class Edit {

        @BeforeEach
        void editPath() {
            path("/edit");
            param("auctionId", "7");
            param("title", "A title");
            param("description", "A description");
        }

        @Test
        @DisplayName("clearing the description is a 400, not the HTTP 500 the audit reproduced")
        void blankDescriptionIsAValidationError() throws Exception {
            param("description", "");   // what the form sends when the box is cleared

            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            assertTrue(error().toLowerCase().contains("description"), error());
            verify(sellerDao, never()).editAuction(anyLong(), anyInt(), anyString(), anyString(),
                    any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a whitespace-only description is refused too")
        void whitespaceDescriptionRejected() throws Exception {
            param("description", "     ");

            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
        }

        @Test
        @DisplayName("an omitted title is still refused")
        void titleRequired() throws Exception {
            param("title", null);

            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            assertTrue(error().toLowerCase().contains("title"), error());
        }

        @Test
        @DisplayName("quantity and cost price reach the DAO")
        void stockAndCostAreForwarded() throws Exception {
            param("quantity", "5");
            param("costPrice", "12.34");

            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
            verify(sellerDao).editAuction(eq(7L), eq(SELLER_ID), eq("A title"), eq("A description"),
                    any(), any(), eq(5), eq(new BigDecimal("12.34")), any(), any(), any());
        }

        @Test
        @DisplayName("fields the seller left alone stay null, so nothing is overwritten")
        void omittedFieldsStayNull() throws Exception {
            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
            verify(sellerDao).editAuction(eq(7L), eq(SELLER_ID), anyString(), anyString(),
                    any(), any(), isNull(), isNull(), any(), any(), any());
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "-1", "1001", "2.5", "abc"})
        @DisplayName("an unusable quantity is refused and says how to empty a listing instead")
        void badQuantityRejected(String quantity) throws Exception {
            param("quantity", quantity);

            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            assertTrue(error().contains("Quantity"), error());
            verify(sellerDao, never()).editAuction(anyLong(), anyInt(), anyString(), anyString(),
                    any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a negative cost price is refused")
        void negativeCostRejected() throws Exception {
            param("costPrice", "-5");

            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            assertTrue(error().contains("cost price"), error());
        }

        @Test
        @DisplayName("a cost price of zero is allowed – it is how a seller clears one")
        void zeroCostAccepted() throws Exception {
            param("costPrice", "0");

            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
            verify(sellerDao).editAuction(anyLong(), anyInt(), anyString(), anyString(),
                    any(), any(), any(), eq(BigDecimal.ZERO), any(), any(), any());
        }

        @Test
        @DisplayName("a listing the seller may not edit is a 400 with the DAO's reason")
        void notEditableIsClientError() throws Exception {
            doThrow(new IllegalStateException("Auction is not editable"))
                    .when(sellerDao).editAuction(anyLong(), anyInt(), anyString(), anyString(),
                            any(), any(), any(), any(), any(), any(), any());

            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            assertEquals("Auction is not editable", error());
        }
    }

    // ── Seller (b): the edit form is given both fields ───────────────────────

    @Nested
    @DisplayName("GET /{id}/edit – the form's own data")
    class EditForm {

        @Test
        @DisplayName("quantity and the seller-private cost price are returned")
        void returnsStockAndCost() throws Exception {
            path("/7/edit");
            SellerAuctionDAO.AuctionEditData data = new SellerAuctionDAO.AuctionEditData(
                    7L, SELLER_ID, 1, "Title", "Description", "Electronics", 1, null,
                    6, new BigDecimal("40.00"),
                    java.time.Instant.now(), java.time.Instant.now().plusSeconds(3600),
                    Collections.emptyList());
            when(sellerDao.getAuctionForEdit(7L, SELLER_ID)).thenReturn(data);
            when(sellerDao.countBids(7L)).thenReturn(3);

            servlet.doGet(req, resp);

            verify(resp).setStatus(200);
            assertEquals(6, json().get("quantity").asInt());
            assertEquals(0, new BigDecimal("40.00").compareTo(json().get("costPrice").decimalValue()));
        }

        @Test
        @DisplayName("someone else's auction is not found for this seller")
        void notOwned() throws Exception {
            path("/7/edit");
            when(sellerDao.getAuctionForEdit(7L, SELLER_ID)).thenReturn(null);

            servlet.doGet(req, resp);

            verify(resp).setStatus(404);
        }
    }

    // ── Seller (c): creation-time validation ────────────────────────────────

    @Nested
    @DisplayName("POST /create – the validation holes")
    class Create {

        @BeforeEach
        void createPath() {
            path("/create");
            param("auctionName", "A thing");
            param("auctionDetails", "Some details");
            param("endDate", "2030-01-31T00:00:00+08:00");
            param("itemCondition", "1");
            param("category", "Electronics");
            param("startPrice", "100");
        }

        @Test
        @DisplayName("a valid listing is created")
        void happyPath() throws Exception {
            when(mainDao.createAuction(any(Auction.class), anyList())).thenReturn(99L);

            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
            assertEquals(99L, json().get("auctionId").asLong());
        }

        @Test
        @DisplayName("no starting price → 400, not a listing winnable for any amount")
        void startPriceRequired() throws Exception {
            param("startPrice", null);

            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            assertTrue(error().contains("Starting price"), error());
            verify(mainDao, never()).createAuction(any(), anyList());
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "0.00", "-5"})
        @DisplayName("a starting price of zero or less → 400")
        void nonPositiveStartPriceRejected(String price) throws Exception {
            param("startPrice", price);

            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            verify(mainDao, never()).createAuction(any(), anyList());
        }

        @ParameterizedTest
        @CsvSource({"100,1", "100,99.99", "100,100"})
        @DisplayName("Buy It Now at or below the starting bid → 400 (reproduced live at 100/1)")
        void buyItNowMustBeatStartPrice(String start, String bin) throws Exception {
            param("startPrice", start);
            param("buyItNowPrice", bin);

            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            assertTrue(error().contains("Buy It Now"), error());
            verify(mainDao, never()).createAuction(any(), anyList());
        }

        @Test
        @DisplayName("Buy It Now above the starting bid is accepted")
        void buyItNowAboveStartAccepted() throws Exception {
            param("buyItNowPrice", "150");
            when(mainDao.createAuction(any(Auction.class), anyList())).thenReturn(99L);

            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
        }

        @Test
        @DisplayName("no category → 400, so no empty category can reach the browse filters")
        void categoryRequired() throws Exception {
            param("category", null);

            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            assertTrue(error().contains("Category"), error());
            verify(mainDao, never()).createAuction(any(), anyList());
        }

        @Test
        @DisplayName("a blank category is the same as none")
        void blankCategoryRejected() throws Exception {
            param("category", "   ");

            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            verify(mainDao, never()).createAuction(any(), anyList());
        }

        @Test
        @DisplayName("quantity above the cap → 400")
        void quantityCapped() throws Exception {
            param("quantity", "1001");

            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            verify(mainDao, never()).createAuction(any(), anyList());
        }
    }

    // ── Seller (b): the seller can reach all of their listings ───────────────

    @Nested
    @DisplayName("GET /auctions – pagination that can actually be paged")
    class Listing {

        @BeforeEach
        void listingPath() throws Exception {
            path("/auctions");
            when(sellerDao.countByBucket(anyInt(), any())).thenReturn(new LinkedHashMap<>(
                    Map.of("ALL", 12, "ACTIVE", 5, "FINISHED", 4, "UNSOLD", 2, "CANCELLED", 1)));
            when(sellerDao.countSellerAuctions(anyInt(), any(SellerAuctionDAO.ListingBucket.class), any()))
                    .thenReturn(12);
            when(sellerDao.listSellerAuctions(anyInt(), any(SellerAuctionDAO.ListingBucket.class),
                    any(), any(), anyInt(), anyInt())).thenReturn(Collections.emptyList());
        }

        @Test
        @DisplayName("the response carries the page count the UI needs to render a pager")
        void reportsTotalPages() throws Exception {
            servlet.doGet(req, resp);

            verify(resp).setStatus(200);
            assertEquals(12, json().get("total").asInt());
            assertEquals(2, json().get("totalPages").asInt());   // 12 rows, 10 per page
            assertEquals(1, json().get("page").asInt());
        }

        @Test
        @DisplayName("page 2 is fetched, so the 11th and 12th listings are reachable")
        void secondPageIsFetched() throws Exception {
            param("page", "2");

            servlet.doGet(req, resp);

            verify(sellerDao).listSellerAuctions(eq(SELLER_ID),
                    any(SellerAuctionDAO.ListingBucket.class), any(), any(), eq(2), eq(10));
        }

        @Test
        @DisplayName("counts for every tab come back, so the pills describe the catalogue")
        void countsCoverEveryTab() throws Exception {
            servlet.doGet(req, resp);

            JsonNode counts = json().get("counts");
            assertEquals(5, counts.get("ACTIVE").asInt());
            assertEquals(2, counts.get("UNSOLD").asInt());
            assertEquals(1, counts.get("CANCELLED").asInt());
        }

        @Test
        @DisplayName("the bucket, search and sort are resolved server-side")
        void bucketSearchAndSortAreForwarded() throws Exception {
            param("bucket", "unsold");
            param("q", "gucci");
            param("sort", "priceHigh");

            servlet.doGet(req, resp);

            verify(sellerDao).listSellerAuctions(SELLER_ID,
                    SellerAuctionDAO.ListingBucket.UNSOLD, "gucci", "priceHigh", 1, 10);
            assertEquals("UNSOLD", json().get("bucket").asText());
        }

        @Test
        @DisplayName("a page past the end is clamped rather than returned empty")
        void pagePastEndIsClamped() throws Exception {
            param("page", "9");

            servlet.doGet(req, resp);

            assertEquals(2, json().get("page").asInt());
            verify(sellerDao).listSellerAuctions(eq(SELLER_ID),
                    any(SellerAuctionDAO.ListingBucket.class), any(), any(), eq(2), eq(10));
        }

        @Test
        @DisplayName("an oversized page size is capped at 50")
        void sizeIsCapped() throws Exception {
            param("size", "5000");

            servlet.doGet(req, resp);

            assertEquals(50, json().get("size").asInt());
        }

        @Test
        @DisplayName("an empty catalogue is page 1 of 1, not page 1 of 0")
        void emptyCatalogueStillHasAPage() throws Exception {
            when(sellerDao.countSellerAuctions(anyInt(), any(SellerAuctionDAO.ListingBucket.class), any()))
                    .thenReturn(0);

            servlet.doGet(req, resp);

            assertEquals(0, json().get("total").asInt());
            assertEquals(1, json().get("totalPages").asInt());
        }
    }
}
