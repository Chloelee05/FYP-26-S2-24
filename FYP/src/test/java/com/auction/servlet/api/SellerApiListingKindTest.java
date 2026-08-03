package com.auction.servlet.api;

import com.auction.dao.AuctionDAO;
import com.auction.dao.SellerAuctionDAO;
import com.auction.model.Auction;
import com.auction.model.ListingKind;
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

import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A seller choosing whether their listing is a product or a service.
 *
 * <p>The {@code listing_kind} discriminator already existed, but only an admin could set it,
 * which made "services" a reclassification feature rather than something the platform lets a
 * seller offer. The minimum requirements name services alongside products for the seller's own
 * record-keeping as well as for admin database management, so these are the endpoint contracts
 * that make the field the seller's: {@code POST /create} accepts it, {@code POST /edit} changes
 * it, and {@code GET /{id}/edit} hands it back so the form can pre-select what is stored.</p>
 *
 * <p>The other half of the contract is what happens when nothing is sent. Create and edit
 * deliberately differ, and both defaults are asserted here: an absent kind on create is
 * PRODUCT (the column's own default), while an absent kind on edit means "leave it alone", so
 * that the legacy JSP edit form — which has no such field — cannot silently turn a service back
 * into a product.</p>
 */
@DisplayName("Seller listing kind (product / service) API")
class SellerApiListingKindTest {

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

    /** The kind the create endpoint handed to the DAO. */
    private String createdKind() throws Exception {
        ArgumentCaptor<Auction> auction = ArgumentCaptor.forClass(Auction.class);
        verify(mainDao).createAuction(auction.capture(), anyList());
        return auction.getValue().getListingKind();
    }

    // ── Creating a service ───────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /create")
    class Create {

        @BeforeEach
        void createPath() throws Exception {
            when(req.getPathInfo()).thenReturn("/create");
            param("auctionName", "10 Session Guitar Lessons");
            param("auctionDetails", "Ten one-hour lessons, in person or online.");
            param("endDate", "2030-01-31T00:00:00+08:00");
            param("itemCondition", "1");
            param("category", "Lessons");
            param("startPrice", "100");
            when(mainDao.createAuction(any(Auction.class), anyList())).thenReturn(99L);
        }

        @Test
        @DisplayName("a seller can list a service, which nothing but an admin edit could do before")
        void serviceIsStored() throws Exception {
            param("listingKind", "SERVICE");

            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
            assertEquals("SERVICE", createdKind());
        }

        @Test
        @DisplayName("PRODUCT is stored when asked for explicitly")
        void productIsStored() throws Exception {
            param("listingKind", "PRODUCT");

            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
            assertEquals("PRODUCT", createdKind());
        }

        /**
         * The reason this is a default rather than a required field: the legacy JSP create
         * form has no kind input at all, and every listing that existed before the column did
         * is a physical good. Requiring it here would break the one and mislabel the other.
         */
        @Test
        @DisplayName("an omitted kind is a PRODUCT, so existing behaviour is unchanged")
        void omittedKindIsProduct() throws Exception {
            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
            assertEquals("PRODUCT", createdKind());
        }

        @ParameterizedTest
        @ValueSource(strings = { "  ", "\t" })
        @DisplayName("a blank kind is treated as omitted rather than rejected")
        void blankKindIsProduct(String kind) throws Exception {
            param("listingKind", kind);

            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
            assertEquals("PRODUCT", createdKind());
        }

        @ParameterizedTest
        @CsvSource({ "service, SERVICE", "Service, SERVICE", "' SERVICE ', SERVICE", "product, PRODUCT" })
        @DisplayName("case and surrounding space are tolerated, and the stored value normalised")
        void kindIsNormalised(String sent, String stored) throws Exception {
            param("listingKind", sent);

            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
            assertEquals(stored, createdKind());
        }

        /**
         * Rejected rather than defaulted: a kind that was supplied and is not one of the two
         * is a client bug, and quietly storing PRODUCT instead would record something other
         * than what the seller asked for. It would also be the one way a value the
         * {@code auction_details_listing_kind_check} constraint refuses could reach the insert.
         */
        @ParameterizedTest
        @ValueSource(strings = { "GOODS", "SERVICES", "PRODUCTS", "OTHER", "'; DROP TABLE users; --" })
        @DisplayName("a kind outside PRODUCT/SERVICE is a 400 and nothing is created")
        void badKindRejected(String kind) throws Exception {
            param("listingKind", kind);

            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            assertTrue(error().contains("PRODUCT or SERVICE"), error());
            verify(mainDao, never()).createAuction(any(), anyList());
        }
    }

    // ── Changing the kind of a listing already made ──────────────────────────

    @Nested
    @DisplayName("POST /edit")
    class Edit {

        @BeforeEach
        void editPath() {
            when(req.getPathInfo()).thenReturn("/edit");
            param("auctionId", "7");
            param("title", "10 Session Guitar Lessons");
            param("description", "Ten one-hour lessons.");
        }

        /** The kind the edit endpoint handed to the DAO. */
        private String editedKind() throws Exception {
            ArgumentCaptor<String> kind = ArgumentCaptor.forClass(String.class);
            verify(sellerDao).editAuction(eq(7L), eq(SELLER_ID), anyString(), anyString(),
                    any(), kind.capture(), any(), any(), any(), any(), any(), any());
            return kind.getValue();
        }

        @Test
        @DisplayName("a seller can correct their own listing to a service")
        void kindIsForwarded() throws Exception {
            param("listingKind", "SERVICE");

            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
            assertEquals("SERVICE", editedKind());
        }

        @Test
        @DisplayName("and back to a product")
        void kindCanBeReverted() throws Exception {
            param("listingKind", "product");

            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
            assertEquals("PRODUCT", editedKind());
        }

        /**
         * Unlike create, where absent means PRODUCT. An edit that says nothing about the kind
         * must not reclassify anything: the legacy JSP edit form sends no such parameter, and
         * defaulting here would reset every service it touched.
         */
        @Test
        @DisplayName("an omitted kind leaves the stored one alone rather than resetting it")
        void omittedKindIsNotAWrite() throws Exception {
            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
            assertNull(editedKind());
        }

        @ParameterizedTest
        @ValueSource(strings = { "GOODS", "SERVICES", "BOTH" })
        @DisplayName("an unrecognised kind is a 400 and the listing is not touched")
        void badKindRejected(String kind) throws Exception {
            param("listingKind", kind);

            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            assertTrue(error().contains("PRODUCT or SERVICE"), error());
            verify(sellerDao, never()).editAuction(anyLong(), anyInt(), anyString(), anyString(),
                    any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    // ── The edit form needs to know what is stored ───────────────────────────

    @Nested
    @DisplayName("GET /{id}/edit")
    class EditForm {

        private SellerAuctionDAO.AuctionEditData data(String kind) {
            return new SellerAuctionDAO.AuctionEditData(
                    7L, SELLER_ID, 1, "Title", "Description", "Lessons", kind, 1, null,
                    1, new BigDecimal("10.00"),
                    java.time.Instant.now(), java.time.Instant.now().plusSeconds(3600),
                    Collections.emptyList());
        }

        @BeforeEach
        void editFormPath() {
            when(req.getPathInfo()).thenReturn("/7/edit");
        }

        @Test
        @DisplayName("the stored kind comes back, so the form pre-selects it instead of guessing")
        void kindIsReturned() throws Exception {
            when(sellerDao.getAuctionForEdit(7L, SELLER_ID)).thenReturn(data("SERVICE"));

            servlet.doGet(req, resp);

            verify(resp).setStatus(200);
            assertEquals("SERVICE", json().get("listingKind").asText());
        }

        /**
         * A row written before the column existed reads back as null through a mock, and the
         * form has to show it as something. PRODUCT is the honest answer and matches the
         * column's DEFAULT.
         */
        @Test
        @DisplayName("a legacy row with no kind reads back as a product, not as null")
        void nullKindReadsAsProduct() throws Exception {
            when(sellerDao.getAuctionForEdit(7L, SELLER_ID)).thenReturn(data(null));

            servlet.doGet(req, resp);

            verify(resp).setStatus(200);
            assertEquals(ListingKind.DEFAULT.name(), json().get("listingKind").asText());
        }
    }
}
