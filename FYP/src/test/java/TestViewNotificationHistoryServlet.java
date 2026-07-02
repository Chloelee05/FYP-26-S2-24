import com.auction.dao.NotificationDAO;
import com.auction.model.Notification;
import com.auction.model.User;
import com.auction.servlet.ViewNotificationHistoryServlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.mockito.Mockito.*;

@DisplayName("Test View Notification History Servlet")
public class TestViewNotificationHistoryServlet {

    private static class ViewNotificationHistoryServletWrapper extends ViewNotificationHistoryServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            super.doGet(req, resp);
        }
    }

    private ViewNotificationHistoryServletWrapper servlet;
    private NotificationDAO mockNotificationDAO;
    private HttpServletRequest mockRequest;
    private HttpServletResponse mockResponse;
    private HttpSession mockSession;
    private User mockUser = mock(User.class);

    @BeforeEach
    void setUp() throws Exception {
        servlet = new ViewNotificationHistoryServletWrapper();
        mockNotificationDAO = mock(NotificationDAO.class);
        servlet.setNotificationDAO(mockNotificationDAO);

        mockRequest  = mock(HttpServletRequest.class);
        mockResponse = mock(HttpServletResponse.class);
        mockSession  = mock(HttpSession.class);

        when(mockRequest.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute("user")).thenReturn(mockUser);
        when(mockUser.getId()).thenReturn(1);

        ServletContext mockContext = mock(ServletContext.class);
        ServletConfig mockConfig  = mock(ServletConfig.class);
        when(mockConfig.getServletContext()).thenReturn(mockContext);
        servlet.init(mockConfig);
    }

    @Nested
    @DisplayName("Test Authentication")
    class TestAuthentication {

        @Test
        @DisplayName("No session returns 401")
        public void testNoSession() throws Exception {
            when(mockRequest.getSession(false)).thenReturn(null);
            servlet.doGet(mockRequest, mockResponse);
            verify(mockResponse).sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }

        @Test
        @DisplayName("No user returns 401")
        public void testNoUser() throws Exception {
            when(mockSession.getAttribute("user")).thenReturn(null);
            servlet.doGet(mockRequest, mockResponse);
            verify(mockResponse).sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("Test Notification History")
    class TestNotificationHistory {

        @Test
        @DisplayName("Sets notifications attribute with results")
        public void testSetsNotifications() throws Exception {
            Notification n1 = mock(Notification.class);
            Notification n2 = mock(Notification.class);
            when(mockNotificationDAO.notificationHistory(1)).thenReturn(List.of(n1, n2));
            servlet.doGet(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("notifications"), eq(List.of(n1, n2)));
        }

        @Test
        @DisplayName("Sets empty list when no notifications")
        public void testEmptyNotifications() throws Exception {
            when(mockNotificationDAO.notificationHistory(1)).thenReturn(List.of());
            servlet.doGet(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("notifications"), eq(List.of()));
        }

        @Test
        @DisplayName("Calls DAO with correct user ID")
        public void testCallsDaoWithUserId() throws Exception {
            when(mockUser.getId()).thenReturn(42);
            when(mockNotificationDAO.notificationHistory(42)).thenReturn(List.of());
            servlet.doGet(mockRequest, mockResponse);
            verify(mockNotificationDAO).notificationHistory(42);
        }

        @Test
        @DisplayName("DAO error throws RuntimeException")
        public void testDaoError() throws Exception {
            when(mockNotificationDAO.notificationHistory(anyInt()))
                    .thenThrow(new Exception("DB error"));
            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                    () -> servlet.doGet(mockRequest, mockResponse));
        }
    }
}