import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.auction.dao.AuctionTagsDAO;
import com.auction.util.DBUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

@DisplayName("Auction Tag DAO Tests")
public class TestAuctionTagDAO {
    private Connection mockConn;
    private PreparedStatement mockStmt;
    private ResultSet mockRS;

    @BeforeEach
    public void setUp() throws Exception {
        mockConn = mock(Connection.class);
        mockStmt = mock(PreparedStatement.class);
        mockRS   = mock(ResultSet.class);
        when(mockConn.prepareStatement(anyString())).thenReturn(mockStmt);
        when(mockStmt.executeQuery()).thenReturn(mockRS);
    }

    @Test
    @DisplayName("Test Success")
    public void TestGetAllTag() throws Exception {
        try (MockedStatic<DBUtil> mockedDB = mockStatic(DBUtil.class)) {
            mockedDB.when(DBUtil::connectDB).thenReturn(mockConn);
            when(mockRS.next()).thenReturn(true, true, false);
            when(mockRS.getLong("id")).thenReturn(1L, 2L);
            when(mockRS.getString("tag_name")).thenReturn("Technology", "Cars");

            AuctionTagsDAO auctionTagsDAO = new AuctionTagsDAO();
            Map<Long, String> result = auctionTagsDAO.getAllTags();

            assertEquals(2, result.size());
            assertEquals("Technology", result.get(1L));
            assertEquals("Cars", result.get(2L));
        }
    }

    @Test
    @DisplayName("Test empty map")
    public void testGetAllTags_empty() throws Exception {
        try (MockedStatic<DBUtil> mockedDB = mockStatic(DBUtil.class)) {
            mockedDB.when(DBUtil::connectDB).thenReturn(mockConn);
            when(mockRS.next()).thenReturn(false);

            AuctionTagsDAO auctionTagsDAO = new AuctionTagsDAO();
            Map<Long, String> result = auctionTagsDAO.getAllTags();

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    @Test
    @DisplayName("Database error")
    public void testGetAllTags_dbError() throws Exception {
        try (MockedStatic<DBUtil> mockedDB = mockStatic(DBUtil.class)) {
            mockedDB.when(DBUtil::connectDB).thenReturn(mockConn);
            when(mockStmt.executeQuery()).thenThrow(new SQLException("DB error"));

            AuctionTagsDAO auctionTagsDAO = new AuctionTagsDAO();
            assertThrows(Exception.class, auctionTagsDAO::getAllTags);
        }
    }
}
