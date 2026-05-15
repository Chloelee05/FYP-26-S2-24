import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.auction.dao.AuctionDAO;
import com.auction.model.Auction;
import com.auction.model.AuctionType;
import com.auction.model.ItemCondition;
import com.auction.util.DBUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.sql.*;
import java.time.Instant;
import java.util.List;

@DisplayName("Test AuctionDAO")
public class TestAuctionDAO {
    private Connection mockConn;
    private PreparedStatement mockStmt;
    private ResultSet mockRS;


    private final List<Long> tagList = List.of(1L, 2L);
    private final List<String> imgList = List.of("image1.jpg", "image2.jpg");

    private final Auction auction = new Auction(1, "Test1", "Test1 Description", Instant.now(),
            Instant.now().plusSeconds(180L), 1.0f, AuctionType.PRICE_UP, ItemCondition.BRAND_NEW,
            tagList);

    @BeforeEach
    public void setUp() throws Exception {
        mockConn = mock(Connection.class);
        mockStmt = mock(PreparedStatement.class);
        mockRS   = mock(ResultSet.class);

        when(mockConn.prepareStatement(anyString())).thenReturn(mockStmt);
        when(mockConn.prepareStatement(anyString(), eq(PreparedStatement.RETURN_GENERATED_KEYS))).thenReturn(mockStmt);
        when(mockStmt.executeQuery()).thenReturn(mockRS);
        when(mockStmt.getGeneratedKeys()).thenReturn(mockRS);

        when(mockRS.next()).thenReturn(true);
        when(mockRS.getLong(1)).thenReturn(1L);

        when(mockStmt.executeUpdate()).thenReturn(1);
    }

    @Nested
    @DisplayName("Test Create Auction")
    class TestCreateAuction {
        @Test
        @DisplayName("Test createAuction Method")
        public void TestCreateAuctionMethod() throws Exception {

            try (MockedStatic<DBUtil> mockedDB = mockStatic(DBUtil.class)) {
                mockedDB.when(DBUtil::connectDB).thenReturn(mockConn);
                AuctionDAO auctionDAO = new AuctionDAO();
                assertEquals(1L, auctionDAO.createAuction(auction, imgList));

                verify(mockConn).commit();
                verify(mockConn, never()).rollback();
            }
        }

        @Test
        @DisplayName("Rolls back when auction insert fails")
        public void testCreateAuction_auctionInsertFails() throws Exception {
            try (MockedStatic<DBUtil> mockedDB = mockStatic(DBUtil.class)) {
                mockedDB.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockConn.prepareStatement(anyString(), eq(PreparedStatement.RETURN_GENERATED_KEYS)))
                        .thenThrow(new SQLException("auction insert failed"));
                AuctionDAO auctionDAO = new AuctionDAO();
                assertThrows(Exception.class, () -> auctionDAO.createAuction(auction, imgList));
                verify(mockConn).rollback();
                verify(mockConn, never()).commit();
            }
        }

        @Test
        @DisplayName("Rolls back when generated key not returned")
        public void testCreateAuction_noGeneratedKey() throws Exception {
            try (MockedStatic<DBUtil> mockedDB = mockStatic(DBUtil.class)) {
                mockedDB.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRS.next()).thenReturn(false); // no key returned

                AuctionDAO auctionDAO = new AuctionDAO();
                assertThrows(Exception.class, () -> auctionDAO.createAuction(auction, imgList));
                verify(mockConn).rollback();
                verify(mockConn, never()).commit();
            }
        }

        @Test
        @DisplayName("Rolls back when auction details insert fails")
        public void testCreateAuction_detailInsertFails() throws Exception {
            try (MockedStatic<DBUtil> mockedDB = mockStatic(DBUtil.class)) {
                mockedDB.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRS.next()).thenReturn(true);

                // details insert returns 0 rows affected
                when(mockConn.prepareStatement(anyString())).thenReturn(mockStmt);
                when(mockStmt.executeUpdate()).thenReturn(0);

                AuctionDAO auctionDAO = new AuctionDAO();
                assertThrows(Exception.class, () -> auctionDAO.createAuction(auction, imgList));
                verify(mockConn).rollback();
                verify(mockConn, never()).commit();
            }
        }

        @Test
        @DisplayName("Rolls back when image insert fails")
        public void testCreateAuction_imageInsertFails() throws Exception {
            try (MockedStatic<DBUtil> mockedDB = mockStatic(DBUtil.class)) {
                mockedDB.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRS.next()).thenReturn(true);
                when(mockConn.prepareStatement(anyString())).thenReturn(mockStmt);
                when(mockStmt.executeUpdate()).thenReturn(1);
                when(mockStmt.executeBatch()).thenThrow(new BatchUpdateException());

                AuctionDAO auctionDAO = new AuctionDAO();
                assertThrows(Exception.class, () -> auctionDAO.createAuction(auction, imgList));
                verify(mockConn).rollback();
                verify(mockConn, never()).commit();
            }
        }

        @Test
        @DisplayName("Rolls back when tag insert fails")
        public void testCreateAuction_tagInsertFails() throws Exception {
            try (MockedStatic<DBUtil> mockedDB = mockStatic(DBUtil.class)) {
                mockedDB.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRS.next()).thenReturn(true);
                when(mockConn.prepareStatement(anyString())).thenReturn(mockStmt);
                when(mockStmt.executeUpdate()).thenReturn(1);

                // images succeed, tags fail
                when(mockStmt.executeBatch())
                        .thenReturn(new int[]{1}) // images batch succeeds
                        .thenThrow(new BatchUpdateException()); // tags batch fails

                AuctionDAO auctionDAO = new AuctionDAO();
                assertThrows(Exception.class, () -> auctionDAO.createAuction(auction, imgList));
                verify(mockConn).rollback();
                verify(mockConn, never()).commit();
            }
        }

        @Test
        @DisplayName("Creates auction with no images and no tags")
        public void testCreateAuction_noImagesNoTags() throws Exception {
            try (MockedStatic<DBUtil> mockedDB = mockStatic(DBUtil.class)) {
                mockedDB.when(DBUtil::connectDB).thenReturn(mockConn);

                Auction auctionNoTags = new Auction(1, "Test1", "Test1 Description", Instant.now(),
                        Instant.now().plusSeconds(180L), 1.0f, AuctionType.PRICE_UP, ItemCondition.BRAND_NEW,
                        List.of()); // empty tags

                AuctionDAO auctionDAO = new AuctionDAO();
                assertEquals(1L, auctionDAO.createAuction(auctionNoTags, List.of())); // empty images
                verify(mockConn).commit();
                verify(mockStmt, never()).executeBatch(); // batch should never be called
                verify(mockConn, never()).rollback();
            }
        }
    }
}
