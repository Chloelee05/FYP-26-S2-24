import com.auction.dao.NotificationDAO;
import com.auction.model.User;
import com.auction.servlet.UpdatePreferenceServlet;
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

import static org.mockito.Mockito.*;

@DisplayName("Test Update Preference Servlet")
public class TestUpdatePreferenceServlet {

    private static class UpdatePreferenceServletWrapper extends UpdatePreferenceServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            super.doPost(req, resp);
        }
    }

    private UpdatePreferenceServletWrapper servlet;
    private NotificationDAO mockNotificationDAO;
    private HttpServletRequest mockRequest;
    private HttpServletResponse mockResponse;
    private HttpSession mockSession;
    private User mockUser = mock(User.class);

    @BeforeEach
    void setUp() throws Exception {
        servlet = new UpdatePreferenceServletWrapper();
        mockNotificationDAO = mock(NotificationDAO.class);
        servlet.setNotificationDAO(mockNotificationDAO);

        mockRequest  = mock(HttpServletRequest.class);
        mockResponse = mock(HttpServletResponse.class);
        mockSession  = mock(HttpSession.class);

        when(mockRequest.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute("user")).thenReturn(mockUser);
        when(mockUser.getId()).thenReturn(1);

        when(mockRequest.getParameter("out_bided")).thenReturn("true");
        when(mockRequest.getParameter("ending_soon")).thenReturn("true");
        when(mockRequest.getParameter("won_auction")).thenReturn("true");

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
            servlet.doPost(mockRequest, mockResponse);
            verify(mockResponse).sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }

        @Test
        @DisplayName("No user returns 401")
        public void testNoUser() throws Exception {
            when(mockSession.getAttribute("user")).thenReturn(null);
            servlet.doPost(mockRequest, mockResponse);
            verify(mockResponse).sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("Test Preference Parsing")
    class TestPreferenceParsing {

        @Test
        @DisplayName("All true saves correctly")
        public void testAllTrue() throws Exception {
            when(mockRequest.getParameter("out_bided")).thenReturn("true");
            when(mockRequest.getParameter("ending_soon")).thenReturn("true");
            when(mockRequest.getParameter("won_auction")).thenReturn("true");
            servlet.doPost(mockRequest, mockResponse);
            verify(mockNotificationDAO).saveUserPreferences(1, true, true, true);
        }

        @Test
        @DisplayName("All false saves correctly")
        public void testAllFalse() throws Exception {
            when(mockRequest.getParameter("out_bided")).thenReturn("false");
            when(mockRequest.getParameter("ending_soon")).thenReturn("false");
            when(mockRequest.getParameter("won_auction")).thenReturn("false");
            servlet.doPost(mockRequest, mockResponse);
            verify(mockNotificationDAO).saveUserPreferences(1, false, false, false);
        }

        @Test
        @DisplayName("Null parameters default to false")
        public void testNullParameters() throws Exception {
            when(mockRequest.getParameter("out_bided")).thenReturn(null);
            when(mockRequest.getParameter("ending_soon")).thenReturn(null);
            when(mockRequest.getParameter("won_auction")).thenReturn(null);
            servlet.doPost(mockRequest, mockResponse);
            verify(mockNotificationDAO).saveUserPreferences(1, false, false, false);
        }

        @Test
        @DisplayName("Invalid values default to false")
        public void testInvalidValues() throws Exception {
            when(mockRequest.getParameter("out_bided")).thenReturn("yes");
            when(mockRequest.getParameter("ending_soon")).thenReturn("1");
            when(mockRequest.getParameter("won_auction")).thenReturn("on");
            servlet.doPost(mockRequest, mockResponse);
            verify(mockNotificationDAO).saveUserPreferences(1, false, false, false);
        }

        @Test
        @DisplayName("Mixed values save correctly")
        public void testMixedValues() throws Exception {
            when(mockRequest.getParameter("out_bided")).thenReturn("true");
            when(mockRequest.getParameter("ending_soon")).thenReturn("false");
            when(mockRequest.getParameter("won_auction")).thenReturn("true");
            servlet.doPost(mockRequest, mockResponse);
            verify(mockNotificationDAO).saveUserPreferences(1, true, false, true);
        }
    }

    @Nested
    @DisplayName("Test Database Errors")
    class TestDatabaseErrors {

        @Test
        @DisplayName("DAO error throws RuntimeException")
        public void testDaoError() throws Exception {
            when(mockNotificationDAO.saveUserPreferences(anyInt(), anyBoolean(), anyBoolean(), anyBoolean()))
                    .thenThrow(new Exception("DB error"));
            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                    () -> servlet.doPost(mockRequest, mockResponse));
        }
    }
}