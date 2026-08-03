import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.auction.dao.SellerAuctionDAO;
import com.auction.model.AuctionStatus;
import com.auction.util.DBUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.sql.*;
import java.util.List;

@DisplayName("SellerAuctionDAO")
public class TestSellerAuctionDAO {

    private Connection mockConn;
    private PreparedStatement mockPs;
    private ResultSet mockRs;
    private final SellerAuctionDAO dao = new SellerAuctionDAO();

    @BeforeEach
    void setUp() throws Exception {
        mockConn = mock(Connection.class);
        mockPs   = mock(PreparedStatement.class);
        mockRs   = mock(ResultSet.class);
        when(mockConn.prepareStatement(anyString())).thenReturn(mockPs);
        when(mockPs.executeQuery()).thenReturn(mockRs);
    }

    // ------------------------------------------------------------------ SCRUM-33: withinBidCap

    @Nested
    @DisplayName("withinBidCap – SCRUM-33")
    class WithinBidCap {

        @Test
        @DisplayName("null cap (no max_price set) always allows any bid")
        void nullCapAllowsAnyBid() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(true);
                when(mockRs.getBigDecimal("max_price")).thenReturn(null);

                assertTrue(dao.withinBidCap(1L, new BigDecimal("9999999")));
            }
        }

        @Test
        @DisplayName("bid amount below cap is allowed")
        void bidBelowCapAllowed() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(true);
                when(mockRs.getBigDecimal("max_price")).thenReturn(new BigDecimal("100.00"));

                assertTrue(dao.withinBidCap(1L, new BigDecimal("99.99")));
            }
        }

        @Test
        @DisplayName("bid equal to cap is allowed (hard ceiling – at cap is accepted)")
        void bidAtCapAllowed() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(true);
                when(mockRs.getBigDecimal("max_price")).thenReturn(new BigDecimal("100.00"));

                assertTrue(dao.withinBidCap(1L, new BigDecimal("100.00")));
            }
        }

        @Test
        @DisplayName("bid above cap is rejected")
        void bidAboveCapRejected() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(true);
                when(mockRs.getBigDecimal("max_price")).thenReturn(new BigDecimal("100.00"));

                assertFalse(dao.withinBidCap(1L, new BigDecimal("100.01")));
            }
        }

        @Test
        @DisplayName("auction not found returns false (fail-safe)")
        void auctionNotFoundReturnsFalse() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(false); // no row

                assertFalse(dao.withinBidCap(999L, new BigDecimal("50.00")));
            }
        }
    }

    // ------------------------------------------------------------------ SCRUM-34: cancelAuction

    @Nested
    @DisplayName("cancelAuction – SCRUM-34")
    class CancelAuction {

        @Test
        @DisplayName("ACTIVE auction owned by seller is cancelled (returns true)")
        void cancelsActiveAuction() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockPs.executeUpdate()).thenReturn(1);

                assertTrue(dao.cancelAuction(1L, 42, "Changed my mind"));
                // verify CANCELLED status id was set
                verify(mockPs).setInt(1, AuctionStatus.CANCELLED.getId());
                verify(mockPs).setLong(3, 1L);
                verify(mockPs).setInt(4, 42);
            }
        }

        @Test
        @DisplayName("PENDING auction owned by seller is cancelled (returns true)")
        void cancelsPendingAuction() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockPs.executeUpdate()).thenReturn(1);

                assertTrue(dao.cancelAuction(5L, 7, null));
            }
        }

        @Test
        @DisplayName("FINISHED auction cannot be cancelled (returns false)")
        void cannotCancelFinishedAuction() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockPs.executeUpdate()).thenReturn(0); // WHERE status_id IN (1,4) excludes FINISHED

                assertFalse(dao.cancelAuction(2L, 42, null));
            }
        }

        @Test
        @DisplayName("already CANCELLED auction returns false")
        void cannotCancelAlreadyCancelled() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockPs.executeUpdate()).thenReturn(0);

                assertFalse(dao.cancelAuction(3L, 42, null));
            }
        }

        @Test
        @DisplayName("wrong seller_id returns false (ownership check)")
        void wrongSellerReturnsFalse() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockPs.executeUpdate()).thenReturn(0); // seller_id mismatch

                assertFalse(dao.cancelAuction(1L, 999, null));
            }
        }

        @Test
        @DisplayName("existing bids are NOT deleted when cancelled – cancel only updates status")
        void bidsPreservedOnCancel() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockPs.executeUpdate()).thenReturn(1);

                dao.cancelAuction(1L, 42, null);

                // only one prepareStatement call (the UPDATE auction); no DELETE on bids
                verify(mockConn, times(1)).prepareStatement(anyString());
                verify(mockPs, never()).executeBatch();
            }
        }
    }

    // ------------------------------------------------------------------ SCRUM-37: countBids

    @Nested
    @DisplayName("countBids – SCRUM-37 precondition")
    class CountBids {

        @Test
        @DisplayName("returns correct bid count")
        void returnsCount() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(true);
                when(mockRs.getInt(1)).thenReturn(3);

                assertEquals(3, dao.countBids(10L));
            }
        }

        @Test
        @DisplayName("zero bids returns 0")
        void zeroBids() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(true);
                when(mockRs.getInt(1)).thenReturn(0);

                assertEquals(0, dao.countBids(10L));
            }
        }
    }

    // ------------------------------------------------------------------ SCRUM-38: dashboard listing

    @Nested
    @DisplayName("listSellerAuctions / countSellerAuctions – SCRUM-38")
    class Dashboard {

        @Test
        @DisplayName("returns rows mapped from result set for seller")
        void returnsRowsForSeller() throws Exception {
            Timestamp ts = Timestamp.from(java.time.Instant.now());
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(true).thenReturn(false);
                when(mockRs.getLong("auction_id")).thenReturn(1L);
                when(mockRs.getString("title")).thenReturn("Test Auction");
                when(mockRs.getBigDecimal("starting_price")).thenReturn(new BigDecimal("10.00"));
                when(mockRs.getBigDecimal("max_price")).thenReturn(null);
                when(mockRs.getBigDecimal("current_bid")).thenReturn(new BigDecimal("0"));
                when(mockRs.getInt("bid_count")).thenReturn(0);
                when(mockRs.getTimestamp("start_date")).thenReturn(ts);
                when(mockRs.getTimestamp("date_end")).thenReturn(ts);
                when(mockRs.getString("status_name")).thenReturn("Active");

                var rows = dao.listSellerAuctions(42, null, 1, 10);
                assertEquals(1, rows.size());
                assertEquals("Test Auction", rows.get(0).getTitle());
            }
        }

        @Test
        @DisplayName("empty result returns empty list (not null)")
        void emptyListReturnsEmptyNotNull() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(false);

                var rows = dao.listSellerAuctions(42, null, 1, 10);
                assertNotNull(rows);
                assertTrue(rows.isEmpty());
            }
        }

        @Test
        @DisplayName("status filter is applied when provided")
        void statusFilterPassedToQuery() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(false);

                dao.listSellerAuctions(42, AuctionStatus.ACTIVE.getId(), 1, 10);

                // with a status filter, the SQL has an extra ? for status_id; verify it's set
                verify(mockPs).setInt(2, AuctionStatus.ACTIVE.getId());
            }
        }

        @Test
        @DisplayName("pagination LIMIT and OFFSET are set correctly for page 2 / size 5")
        void paginationLimitOffset() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(false);

                dao.listSellerAuctions(42, null, 2, 5);

                // no status filter: params are seller_id(1), limit(2), offset(3)
                verify(mockPs).setInt(1, 42);
                verify(mockPs).setInt(2, 5);   // LIMIT
                verify(mockPs).setInt(3, 5);   // OFFSET = 5 * (2-1)
            }
        }

        @Test
        @DisplayName("countSellerAuctions returns correct count")
        void countReturnsTotal() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(true);
                when(mockRs.getInt(1)).thenReturn(7);

                assertEquals(7, dao.countSellerAuctions(42, null));
            }
        }

        @Test
        @DisplayName("no leakage – seller_id is always bound from session, not from request param")
        void sellerIdAlwaysBound() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(false);

                dao.listSellerAuctions(99, null, 1, 10);

                verify(mockPs).setInt(1, 99); // seller_id = 99, never any other id
            }
        }
    }

    /** Every SQL string the DAO prepared on this connection, in order. */
    private List<String> preparedSql() throws Exception {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(mockConn, atLeastOnce()).prepareStatement(sql.capture());
        return sql.getAllValues();
    }

    private boolean anySqlContains(String needle) throws Exception {
        return preparedSql().stream().anyMatch(s -> s.contains(needle));
    }

    // ------------------------------------------------------------------ Seller (d): removing items

    @Nested
    @DisplayName("removeUnit – remove items from an auction, including the last one")
    class RemoveUnit {

        /** The locked read at the top of removeUnit. */
        private void stubListing(int statusId, int ownerId, int quantity) throws Exception {
            when(mockRs.next()).thenReturn(true);
            when(mockRs.getInt("status_id")).thenReturn(statusId);
            when(mockRs.getInt("seller_id")).thenReturn(ownerId);
            when(mockRs.getInt("quantity")).thenReturn(quantity);
        }

        @Test
        @DisplayName("with units to spare it decrements and leaves the listing running")
        void decrementsWithoutEndingListing() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                stubListing(AuctionStatus.ACTIVE.getId(), 42, 3);

                assertEquals(SellerAuctionDAO.ReduceQtyResult.SUCCESS,
                        dao.removeUnit(1L, 42, "ignored while units remain"));

                assertTrue(anySqlContains("quantity = quantity - 1"));
                assertFalse(anySqlContains("UPDATE auction SET status_id"),
                        "a listing with stock left must not be cancelled");
                verify(mockConn).commit();
            }
        }

        @Test
        @DisplayName("removing the LAST unit ends the listing – the case the CHECK used to forbid")
        void lastUnitEndsListing() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                stubListing(AuctionStatus.ACTIVE.getId(), 42, 1);

                assertEquals(SellerAuctionDAO.ReduceQtyResult.LISTING_ENDED,
                        dao.removeUnit(1L, 42, "Item was damaged"));

                // Both writes, in one transaction: nothing left to sell, so nothing left to bid on.
                assertTrue(anySqlContains("quantity = quantity - 1"));
                assertTrue(anySqlContains("UPDATE auction SET status_id = ?, cancel_reason = ?"));
                verify(mockPs).setInt(1, AuctionStatus.CANCELLED.getId());
                verify(mockPs).setString(2, "Item was damaged");
                verify(mockConn).commit();
                verify(mockConn, never()).rollback();
            }
        }

        @Test
        @DisplayName("the reason reaches cancel_reason, so 'due to lack of bids' is evidenced")
        void reasonIsPersisted() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                stubListing(AuctionStatus.ACTIVE.getId(), 42, 1);

                dao.removeUnit(1L, 42, "No bids received");

                verify(mockPs).setString(2, "No bids received");
            }
        }

        @Test
        @DisplayName("an empty listing cannot go negative")
        void alreadyEmpty() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                stubListing(AuctionStatus.ACTIVE.getId(), 42, 0);

                assertEquals(SellerAuctionDAO.ReduceQtyResult.ALREADY_EMPTY,
                        dao.removeUnit(1L, 42, "r"));
                verify(mockConn).rollback();
                assertFalse(anySqlContains("quantity = quantity - 1"));
            }
        }

        @Test
        @DisplayName("another seller's listing is refused before anything is written")
        void notOwner() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                stubListing(AuctionStatus.ACTIVE.getId(), 7, 5);

                assertEquals(SellerAuctionDAO.ReduceQtyResult.NOT_OWNER,
                        dao.removeUnit(1L, 42, "r"));
                verify(mockConn).rollback();
                assertTrue(preparedSql().stream().noneMatch(s -> s.startsWith("UPDATE")));
            }
        }

        @Test
        @DisplayName("missing auction reports NOT_FOUND")
        void notFound() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(false);

                assertEquals(SellerAuctionDAO.ReduceQtyResult.NOT_FOUND,
                        dao.removeUnit(999L, 42, "r"));
                verify(mockConn).rollback();
            }
        }

        @Test
        @DisplayName("a finished listing has nothing to remove")
        void finishedIsNotActive() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                stubListing(AuctionStatus.FINISHED.getId(), 42, 2);

                assertEquals(SellerAuctionDAO.ReduceQtyResult.NOT_ACTIVE,
                        dao.removeUnit(1L, 42, "r"));
                verify(mockConn).rollback();
            }
        }

        @Test
        @DisplayName("the row is locked, so two concurrent removals cannot both take the last unit")
        void locksTheRow() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                stubListing(AuctionStatus.ACTIVE.getId(), 42, 1);

                dao.removeUnit(1L, 42, "r");

                assertTrue(preparedSql().get(0).contains("FOR UPDATE"));
                verify(mockConn).setAutoCommit(false);
            }
        }
    }

    // ------------------------------------------------------------------ Seller (b): quantity + cost_price

    @Nested
    @DisplayName("quantity and cost_price are maintainable – Seller (b)")
    class StockAndCost {

        @Test
        @DisplayName("the edit form is given the current quantity and cost price")
        void editFormReadsBothFields() throws Exception {
            Timestamp ts = Timestamp.from(java.time.Instant.now());
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                // First next() is the auction row; the second false ends the image query.
                when(mockRs.next()).thenReturn(true, false);
                when(mockRs.getLong("auction_id")).thenReturn(10L);
                when(mockRs.getLong("seller_id")).thenReturn(42L);
                when(mockRs.getInt("status_id")).thenReturn(AuctionStatus.ACTIVE.getId());
                when(mockRs.getString("title")).thenReturn("Thing");
                when(mockRs.getString("description")).thenReturn("Desc");
                when(mockRs.getString("category")).thenReturn("Electronics");
                when(mockRs.getInt("item_condition_id")).thenReturn(1);
                when(mockRs.getBigDecimal("max_price")).thenReturn(null);
                when(mockRs.getInt("quantity")).thenReturn(4);
                when(mockRs.getBigDecimal("cost_price")).thenReturn(new BigDecimal("19.90"));
                when(mockRs.getTimestamp("start_date")).thenReturn(ts);
                when(mockRs.getTimestamp("date_end")).thenReturn(ts);

                SellerAuctionDAO.AuctionEditData d = dao.getAuctionForEdit(10L, 42);

                assertNotNull(d);
                assertEquals(4, d.quantity);
                assertEquals(new BigDecimal("19.90"), d.costPrice);
                assertTrue(anySqlContains("d.quantity"));
                assertTrue(anySqlContains("d.cost_price"));
            }
        }

        @Test
        @DisplayName("both are written even when the listing already has bids")
        void writableWithBidsPresent() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                // isEditableBy row, then the bid count row.
                when(mockRs.next()).thenReturn(true, true);
                when(mockRs.getInt(1)).thenReturn(2);      // two bids
                when(mockPs.executeUpdate()).thenReturn(1);

                dao.editAuction(10L, 42, "New title", "New description", "Cat", 1,
                        7, new BigDecimal("5.55"), null, null, null);

                assertTrue(anySqlContains("quantity = ?"));
                assertTrue(anySqlContains("cost_price = ?"));
                verify(mockPs).setInt(1, 7);
                verify(mockPs).setBigDecimal(2, new BigDecimal("5.55"));
                // What is being sold is frozen once someone has bid on it.
                assertFalse(anySqlContains("SET title = ?"),
                        "title must stay frozen while bids exist");
                verify(mockConn).commit();
            }
        }

        @Test
        @DisplayName("with no bids the descriptive fields are written too")
        void detailsWritableWithoutBids() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(true, true);
                when(mockRs.getInt(1)).thenReturn(0);      // no bids
                when(mockPs.executeUpdate()).thenReturn(1);

                dao.editAuction(10L, 42, "New title", "New description", "Cat", 1,
                        2, null, null, null, null);

                assertTrue(anySqlContains("SET title = ?"));
                assertTrue(anySqlContains("quantity = ?"));
            }
        }

        @Test
        @DisplayName("omitting cost price leaves the recorded one alone rather than nulling it")
        void nullCostPriceIsNotAWrite() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(true, true);
                when(mockRs.getInt(1)).thenReturn(3);
                when(mockPs.executeUpdate()).thenReturn(1);

                dao.editAuction(10L, 42, "t", "d", "c", 1,
                        6, null, null, null, null);

                assertTrue(anySqlContains("SET quantity = ? WHERE id = ?"));
                assertFalse(anySqlContains("cost_price"));
            }
        }

        @Test
        @DisplayName("a zero cost price IS written – that is how a seller clears one")
        void zeroCostPriceIsWritten() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(true, true);
                when(mockRs.getInt(1)).thenReturn(3);
                when(mockPs.executeUpdate()).thenReturn(1);

                dao.editAuction(10L, 42, "t", "d", "c", 1,
                        null, BigDecimal.ZERO, null, null, null);

                assertTrue(anySqlContains("SET cost_price = ? WHERE id = ?"));
                verify(mockPs).setBigDecimal(1, BigDecimal.ZERO);
            }
        }

        @Test
        @DisplayName("the legacy JSP overload touches neither field")
        void legacyOverloadLeavesBothAlone() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(true, true);
                when(mockRs.getInt(1)).thenReturn(0);
                when(mockPs.executeUpdate()).thenReturn(1);

                dao.editAuction(10L, 42, "t", "d", "c", 1, null, null, null);

                assertFalse(anySqlContains("quantity = ?"));
                assertFalse(anySqlContains("cost_price = ?"));
            }
        }

        @Test
        @DisplayName("a listing the seller does not own is rejected before any write")
        void notEditable() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(false);   // isEditableBy finds nothing

                assertThrows(IllegalStateException.class, () ->
                        dao.editAuction(10L, 999, "t", "d", "c", 1,
                                5, new BigDecimal("1.00"), null, null, null));
                verify(mockConn).rollback();
            }
        }
    }

    // ------------------------------------------------------------------ stock follows sales

    @Nested
    @DisplayName("decrementStockForSale – a sold unit leaves the stock count")
    class SoldStock {

        @Test
        @DisplayName("decrements by one, floored at zero, on the caller's connection")
        void decrementsFlooredAtZero() throws Exception {
            SellerAuctionDAO.decrementStockForSale(mockConn, 12L);

            assertTrue(anySqlContains("GREATEST(quantity - 1, 0)"));
            verify(mockPs).setLong(1, 12L);
            verify(mockPs).executeUpdate();
            // Joins the caller's transaction: never commits or closes on its own.
            verify(mockConn, never()).commit();
            verify(mockConn, never()).close();
        }
    }

    @Nested
    @DisplayName("relistAuction – a relisted listing has something to sell")
    class Relist {

        @Test
        @DisplayName("restores at least one unit, so quantity = 0 cannot reach a live listing")
        void restoresStock() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockPs.executeUpdate()).thenReturn(1);

                assertTrue(dao.relistAuction(30L, 42));

                assertTrue(anySqlContains("GREATEST(quantity, 1)"));
                verify(mockPs).setInt(1, AuctionStatus.PENDING.getId());
                verify(mockConn).commit();
            }
        }

        @Test
        @DisplayName("a relist that changed nothing does not touch stock")
        void noRowNoStockChange() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockPs.executeUpdate()).thenReturn(0);

                assertFalse(dao.relistAuction(30L, 42));
                assertFalse(anySqlContains("GREATEST(quantity, 1)"));
            }
        }
    }

    // ------------------------------------------------------------------ buckets, search, sort

    @Nested
    @DisplayName("ListingBucket + search + sort – real server-side pagination")
    class Buckets {

        private void noRows() throws Exception {
            when(mockRs.next()).thenReturn(false);
        }

        @Test
        @DisplayName("UNSOLD is 'ended with no bids', not the same query as CANCELLED")
        void unsoldIsNotCancelled() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                noRows();

                dao.listSellerAuctions(42, SellerAuctionDAO.ListingBucket.UNSOLD, null, "newest", 1, 10);
                String unsold = preparedSql().get(0);

                assertTrue(unsold.contains("NOT EXISTS"), "unsold means nobody bid");
                assertFalse(unsold.contains("a.status_id = 3"), "unsold is not a cancellation");
            }
        }

        @Test
        @DisplayName("CANCELLED is only a withdrawn listing")
        void cancelledIsWithdrawnOnly() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                noRows();

                dao.listSellerAuctions(42, SellerAuctionDAO.ListingBucket.CANCELLED, null, "newest", 1, 10);

                assertTrue(preparedSql().get(0).contains("a.status_id = 3"));
            }
        }

        @Test
        @DisplayName("an ACTIVE row whose clock has run out is not shown as active")
        void activeExcludesExpired() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                noRows();

                dao.listSellerAuctions(42, SellerAuctionDAO.ListingBucket.ACTIVE, null, "newest", 1, 10);

                assertTrue(preparedSql().get(0).contains("a.date_end > CURRENT_TIMESTAMP"));
            }
        }

        @Test
        @DisplayName("ALL applies no bucket filter")
        void allHasNoFilter() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                noRows();

                dao.listSellerAuctions(42, SellerAuctionDAO.ListingBucket.ALL, null, "newest", 1, 10);
                String sql = preparedSql().get(0);

                assertFalse(sql.contains("a.status_id = 3"));
                assertFalse(sql.contains("NOT EXISTS"));
            }
        }

        @Test
        @DisplayName("a null bucket is treated as ALL rather than failing")
        void nullBucketIsAll() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                noRows();

                assertTrue(dao.listSellerAuctions(42, null, null, null, 1, 10).isEmpty());
            }
        }

        @Test
        @DisplayName("search runs in SQL, case-insensitively, across every page")
        void searchIsBoundLowercased() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                noRows();

                dao.listSellerAuctions(42, SellerAuctionDAO.ListingBucket.ALL, "  GUCCI ", "newest", 1, 10);

                assertTrue(preparedSql().get(0).contains("LOWER(d.title) LIKE ?"));
                verify(mockPs).setString(2, "%gucci%");
            }
        }

        @Test
        @DisplayName("a blank search is no search at all")
        void blankSearchIsIgnored() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                noRows();

                dao.listSellerAuctions(42, SellerAuctionDAO.ListingBucket.ALL, "   ", "newest", 1, 10);

                assertFalse(preparedSql().get(0).contains("LIKE"));
                verify(mockPs, never()).setString(anyInt(), anyString());
            }
        }

        @Test
        @DisplayName("LIMIT and OFFSET place page 2 of 10 correctly")
        void pageTwoOffset() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                noRows();

                dao.listSellerAuctions(42, SellerAuctionDAO.ListingBucket.ALL, null, "newest", 2, 10);

                verify(mockPs).setInt(1, 42);
                verify(mockPs).setInt(2, 10);  // LIMIT
                verify(mockPs).setInt(3, 10);  // OFFSET
            }
        }

        @Test
        @DisplayName("page 0 does not become a negative OFFSET")
        void pageZeroClampsOffset() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                noRows();

                dao.listSellerAuctions(42, SellerAuctionDAO.ListingBucket.ALL, null, "newest", 0, 10);

                verify(mockPs).setInt(3, 0);
            }
        }

        @Test
        @DisplayName("an unknown sort key falls back to newest instead of reaching SQL")
        void unknownSortIsNotInterpolated() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                noRows();

                dao.listSellerAuctions(42, SellerAuctionDAO.ListingBucket.ALL,
                        null, "date_end; DROP TABLE auction --", 1, 10);
                String sql = preparedSql().get(0);

                assertFalse(sql.contains("DROP TABLE"));
                assertTrue(sql.contains("ORDER BY a.date_created DESC"));
            }
        }

        @Test
        @DisplayName("every sort ends on a unique column, so paging cannot duplicate or skip a row")
        void everySortIsTotallyOrdered() throws Exception {
            for (String sort : new String[]{"newest", "oldest", "priceHigh", "priceLow", "likes", "ending", "?"}) {
                setUp();
                try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                    db.when(DBUtil::connectDB).thenReturn(mockConn);
                    noRows();

                    dao.listSellerAuctions(42, SellerAuctionDAO.ListingBucket.ALL, null, sort, 1, 10);
                    String sql = preparedSql().get(0);

                    assertTrue(sql.contains("a.auction_id DESC LIMIT"),
                            "sort '" + sort + "' has no tie-break: " + sql);
                }
            }
        }

        @Test
        @DisplayName("counts cover the whole catalogue, one row per bucket")
        void countByBucketReturnsEveryTab() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(true);
                when(mockRs.getInt("all_count")).thenReturn(12);
                when(mockRs.getInt("active_count")).thenReturn(5);
                when(mockRs.getInt("finished_count")).thenReturn(4);
                when(mockRs.getInt("unsold_count")).thenReturn(2);
                when(mockRs.getInt("cancelled_count")).thenReturn(1);

                var counts = dao.countByBucket(42, null);

                assertEquals(12, counts.get("ALL"));
                assertEquals(5, counts.get("ACTIVE"));
                assertEquals(4, counts.get("FINISHED"));
                assertEquals(2, counts.get("UNSOLD"));
                assertEquals(1, counts.get("CANCELLED"));
            }
        }

        @Test
        @DisplayName("the counts describe the search, not the unsearched catalogue")
        void countByBucketHonoursSearch() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(false);

                dao.countByBucket(42, "watch");

                verify(mockPs).setString(2, "%watch%");
            }
        }

        @Test
        @DisplayName("the bucketed count uses the same predicate as the listing query")
        void countMatchesListingPredicate() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(true);
                when(mockRs.getInt(1)).thenReturn(2);

                assertEquals(2, dao.countSellerAuctions(42, SellerAuctionDAO.ListingBucket.UNSOLD, null));
                assertTrue(preparedSql().get(0).contains("NOT EXISTS"));
            }
        }

        @Test
        @DisplayName("a bucket name from the query string is parsed leniently")
        void bucketParsing() {
            assertEquals(SellerAuctionDAO.ListingBucket.UNSOLD, SellerAuctionDAO.ListingBucket.parse("unsold"));
            assertEquals(SellerAuctionDAO.ListingBucket.ACTIVE, SellerAuctionDAO.ListingBucket.parse(" Active "));
            assertEquals(SellerAuctionDAO.ListingBucket.ALL, SellerAuctionDAO.ListingBucket.parse(null));
            assertEquals(SellerAuctionDAO.ListingBucket.ALL, SellerAuctionDAO.ListingBucket.parse(""));
            assertEquals(SellerAuctionDAO.ListingBucket.ALL, SellerAuctionDAO.ListingBucket.parse("nonsense"));
        }
    }
}
