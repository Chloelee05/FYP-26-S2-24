import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.auction.dao.UserDAO;
import com.auction.model.Role;
import com.auction.model.User;
import com.auction.util.DBUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@DisplayName("UserDAO tests")
public class TestUserDAO extends Mockito {

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

    @Nested
    @DisplayName("Test CheckUser()")
    class TestCheckUser {

        @Test
        @DisplayName("Test User exist")
        public void TestUserExist() throws Exception {
            try (MockedStatic<DBUtil> mockedDB = mockStatic(DBUtil.class)) {
                mockedDB.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRS.next()).thenReturn(true);

                UserDAO dao = new UserDAO();
                assertTrue(dao.checkUser("test1"));
            }
        }

        @Test
        @DisplayName("Test non-existing user")
        public void TestNonUser() throws Exception {
            try (MockedStatic<DBUtil> mockedDB = mockStatic(DBUtil.class)) {
                mockedDB.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRS.next()).thenReturn(false);

                UserDAO dao = new UserDAO();
                assertFalse(dao.checkUser("test999"));
            }
        }

        @Test
        @DisplayName("Test null username")
        public void TestNullUsername() throws Exception {
            try (MockedStatic<DBUtil> mockedDB = mockStatic(DBUtil.class)) {
                mockedDB.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRS.next()).thenReturn(false);

                UserDAO dao = new UserDAO();
                assertFalse(dao.checkUser(null));
            }
        }

        @Test
        @DisplayName("Test runtime exception on DB failure")
        public void TestDBFailure() throws Exception {
            try (MockedStatic<DBUtil> mockedDB = mockStatic(DBUtil.class)) {
                mockedDB.when(DBUtil::connectDB).thenThrow(new SQLException("Connection failed"));

                UserDAO dao = new UserDAO();
                assertThrows(RuntimeException.class, () -> dao.checkUser("test1"));
            }
        }
    }

    @Nested
    @DisplayName("Test checkEmail()")
    class TestCheckEmail {

        @Test
        @DisplayName("Test Email exist")
        public void TestEmailExist() throws Exception {
            try (MockedStatic<DBUtil> mockedDB = mockStatic(DBUtil.class)) {
                mockedDB.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRS.next()).thenReturn(true);

                UserDAO dao = new UserDAO();
                assertTrue(dao.checkEmail("test1@email.com"));
            }
        }

        @Test
        @DisplayName("Test non-existing email")
        public void TestNonEmail() throws Exception {
            try (MockedStatic<DBUtil> mockedDB = mockStatic(DBUtil.class)) {
                mockedDB.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRS.next()).thenReturn(false);

                UserDAO dao = new UserDAO();
                assertFalse(dao.checkEmail("test999@email.com"));
            }
        }

        @Test
        @DisplayName("Test null email")
        public void TestNullEmail() throws Exception {
            try (MockedStatic<DBUtil> mockedDB = mockStatic(DBUtil.class)) {
                mockedDB.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRS.next()).thenReturn(false);

                UserDAO dao = new UserDAO();
                assertFalse(dao.checkEmail(null));
            }
        }

        @Test
        @DisplayName("Test runtime exception on DB failure")
        public void TestDBFailure() throws Exception {
            try (MockedStatic<DBUtil> mockedDB = mockStatic(DBUtil.class)) {
                mockedDB.when(DBUtil::connectDB).thenThrow(new SQLException("Connection failed"));

                UserDAO dao = new UserDAO();
                assertThrows(RuntimeException.class, () -> dao.checkEmail("test1@email.com"));
            }
        }
    }

    @Nested
    @DisplayName("Test insertUser()")
    class TestInsertUser{
        private User testUser;

        @BeforeEach
        public void setUp() throws Exception {
            testUser = new User();
            testUser.setUsername("test1");
            testUser.setEmail("test1@email.com");
            testUser.setPassword("password123");
            testUser.setRole(Role.BUYER);
        }

        @Test
        @DisplayName("Test Success")
        public void TestSuccess() throws Exception {
            try (MockedStatic<DBUtil> mockedDB = mockStatic(DBUtil.class)) {
                mockedDB.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockStmt.executeUpdate()).thenReturn(1);

                UserDAO dao = new UserDAO();
                assertTrue(dao.insertUser(testUser));
            }
        }

        @Test
        @DisplayName("Test insert user fails when no rows affected")
        public void TestFails() throws Exception {
            try (MockedStatic<DBUtil> mockedDB = mockStatic(DBUtil.class)) {
                mockedDB.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockStmt.executeUpdate()).thenReturn(0);

                UserDAO dao = new UserDAO();
                assertFalse(dao.insertUser(testUser));
            }
        }

        @Test
        @DisplayName("Test null exception")
        public void TestInsertNull() throws Exception {
            try (MockedStatic<DBUtil> mockedDB = mockStatic(DBUtil.class)) {
                mockedDB.when(DBUtil::connectDB).thenReturn(mockConn);

                UserDAO dao = new UserDAO();
                assertThrows(RuntimeException.class, () -> dao.insertUser(null));
            }
        }

        @Test
        @DisplayName("Test exception on DB failure")
        public void TestInsertUserDBFailure() throws Exception {
            try (MockedStatic<DBUtil> mockedDB = mockStatic(DBUtil.class)) {
                mockedDB.when(DBUtil::connectDB).thenThrow(new SQLException("Connection failed"));

                UserDAO dao = new UserDAO();
                assertThrows(RuntimeException.class, () -> dao.insertUser(testUser));
            }
        }
    }

    @Nested
    @DisplayName("Test updateStatus()")
    class TestUpdateStatus {

        @Test
        @DisplayName("Test User Suspend")
        public void TestUpdateSuspend() throws Exception {
            try (MockedStatic<DBUtil> mockedDB = mockStatic(DBUtil.class)) {
                mockedDB.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockStmt.executeUpdate()).thenReturn(1);

                UserDAO dao = new UserDAO();
                assertTrue(dao.updateStatus(1, 2));
            }
        }

        @Test
        @DisplayName("Test runtime exception on DB failure")
        public void TestDBFailure() throws Exception {
            try (MockedStatic<DBUtil> mockedDB = mockStatic(DBUtil.class)) {
                mockedDB.when(DBUtil::connectDB).thenThrow(new SQLException("Connection failed"));

                UserDAO dao = new UserDAO();
                assertThrows(RuntimeException.class, () -> dao.updateStatus(1, 2));
            }
        }
    }

    @Nested
    @DisplayName("Test viewAllUsers()")
    class TestViewAllUsers {

        @Test
        @DisplayName("Test returns populated list")
        public void TestViewAllUsersPopulated() throws Exception {
            try (MockedStatic<DBUtil> mockedDB = mockStatic(DBUtil.class)) {
                mockedDB.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRS.next()).thenReturn(true, true, false);
                when(mockRS.getInt("id")).thenReturn(1, 2);
                when(mockRS.getString("username")).thenReturn("test1", "test2");
                when(mockRS.getString("email")).thenReturn("test1@email.com", "test2@email.com");
                when(mockRS.getInt("role_id")).thenReturn(2, 2);

                UserDAO dao = new UserDAO();
                List<User> result = dao.viewAllUsers();

                assertEquals(2, result.size());
                assertEquals("test1", result.get(0).getUsername());
                assertEquals("test2", result.get(1).getUsername());
            }
        }

        @Test
        @DisplayName("Test returns empty list")
        public void TestViewAllUsersEmpty() throws Exception {
            try (MockedStatic<DBUtil> mockedDB = mockStatic(DBUtil.class)) {
                mockedDB.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRS.next()).thenReturn(false);

                UserDAO dao = new UserDAO();
                List<User> result = dao.viewAllUsers();

                assertTrue(result.isEmpty());
            }
        }

        @Test
        @DisplayName("Test runtime exception on DB failure")
        public void TestViewAllUsersDBFailure() throws Exception {
            try (MockedStatic<DBUtil> mockedDB = mockStatic(DBUtil.class)) {
                mockedDB.when(DBUtil::connectDB).thenThrow(new SQLException("Connection failed"));

                UserDAO dao = new UserDAO();
                assertThrows(RuntimeException.class, dao::viewAllUsers);
            }
        }
    }
}
