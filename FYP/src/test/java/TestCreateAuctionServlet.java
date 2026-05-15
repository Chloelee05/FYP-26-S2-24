import com.auction.dao.AuctionDAO;
import com.auction.dao.AuctionTagsDAO;
import com.auction.servlet.CreateAuctionServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.Part;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@DisplayName("Test Create Auction Servlet")
public class TestCreateAuctionServlet{

    private static class CreateAuctionServletWrapper extends CreateAuctionServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            super.doPost(req, resp);
        }
    }

    private AuctionDAO mockDAO;
    private AuctionTagsDAO mockTagDAO;
    private CreateAuctionServletWrapper mockServlet;
    private HttpServletRequest mockRequest;
    private HttpServletResponse mockResponse;
    private HttpSession mockSession;
    private ServletContext mockContext;
    private ServletConfig mockConfig;

    @BeforeEach
    public void setUp() throws Exception {
        mockDAO = mock(AuctionDAO.class);
        mockTagDAO = mock(AuctionTagsDAO.class);
        mockServlet = new CreateAuctionServletWrapper();
        mockServlet.setAuctionDAO(mockDAO, mockTagDAO);
        mockRequest = mock(HttpServletRequest.class);
        mockResponse = mock(HttpServletResponse.class);
        mockSession = mock(HttpSession.class);
        when(mockRequest.getSession(false)).thenReturn(mockSession);
        mockContext = mock(ServletContext.class);
        mockConfig = mock(ServletConfig.class);
        when(mockConfig.getServletContext()).thenReturn(mockContext);

        //values
        when(mockContext.getInitParameter("uploadDir")).thenReturn("/tmp/test-uploads");
        mockServlet.init(mockConfig);

        when(mockSession.getAttribute("userRole")).thenReturn("seller");
        when(mockSession.getAttribute("userId")).thenReturn(1);
        when(mockRequest.getParts()).thenReturn(Collections.emptyList());

        when(mockRequest.getParameter("auction_name")).thenReturn("Test name");
        when(mockRequest.getParameter("auction_details")).thenReturn("Testing Details");
        when(mockRequest.getParameter("start_date")).thenReturn("2026-05-15T00:00:00+00:00");
        when(mockRequest.getParameter("end_date")).thenReturn("2026-05-16T00:00:00+00:00");
        when(mockRequest.getParameter("start_price")).thenReturn("1");
        when(mockRequest.getParameter("auction_type")).thenReturn("1");
        when(mockRequest.getParameter("item_condition")).thenReturn("1");
        when(mockRequest.getParameter("tags")).thenReturn("1");
    }

    @Nested
    @DisplayName("Test uploadDirErrors")
    class TestUploadDirErrors {
        @Test
        @DisplayName("Test uploadDir error")
        public void TestNull() throws Exception {
            when(mockContext.getInitParameter("uploadDir")).thenReturn(null);
            assertThrows(ServletException.class, () -> mockServlet.init(mockConfig));
        }

        @Test
        @DisplayName("Test cannot create upload directory")
        public void testCannotCreateUploadDir() throws Exception {
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                mockedFiles.when(() -> Files.createDirectories(any(Path.class)))
                        .thenThrow(new IOException("Permission denied"));

                assertThrows(ServletException.class, () -> mockServlet.init(mockConfig));
            }
        }
    }

    @Test
    @DisplayName("Test user not seller")
    public void TestNotSeller() throws Exception{
        when(mockSession.getAttribute("userRole")).thenReturn("buyer");
        mockServlet.doPost(mockRequest, mockResponse);
        verify(mockResponse).sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Nested
    @DisplayName("Test Validate input form")
    class TestValidateInputForm{
        @Test
        @DisplayName("Test Missing input")
        public void TestMissingInput() throws Exception {
            when(mockRequest.getParameter("auction_name")).thenReturn(" ");
            mockServlet.doPost(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("Error"), eq("All fields are required"));
        }

        @Test
        @DisplayName("Test invalid starting price")
        public void TestInvalidStartingPrice() throws Exception{
            when(mockRequest.getParameter("start_price")).thenReturn("-1");
            mockServlet.doPost(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("Error"), eq("Invalid start price"));
        }

        @Test
        @DisplayName("Test default starting price")
        public void testDefaultStartingPrice() throws Exception {
            when(mockRequest.getParameter("start_price")).thenReturn(null);
            mockServlet.doPost(mockRequest, mockResponse);
            verify(mockRequest, never()).setAttribute(eq("Error"), eq("Invalid start price"));
        }

        @Test
        @DisplayName("Test invalid date format")
        public void TestInvalidDateFormat() throws Exception{
            when(mockRequest.getParameter("start_date")).thenReturn("-1");
            mockServlet.doPost(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("Error"), eq("Invalid date format"));
        }

        @Test
        @DisplayName("Test end date after start")
        public void TestEndDate() throws Exception{
            when(mockRequest.getParameter("start_date")).thenReturn("2026-05-15T00:00:00+00:00");
            when(mockRequest.getParameter("end_date")).thenReturn("2026-05-10T00:00:00+00:00");
            mockServlet.doPost(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("Error"), eq("End date must be after start date"));
        }

        @Test
        @DisplayName("Test invalid auction type")
        public void TestInvalidAuctionType() throws Exception{
            when(mockRequest.getParameter("auction_type")).thenReturn("Test Auction");
            mockServlet.doPost(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("Error"), eq("Invalid auction type"));
        }

        @Test
        @DisplayName("Test default auction type")
        public void testDefaultAuctionType() throws Exception {
            when(mockRequest.getParameter("auction_type")).thenReturn(null);
            mockServlet.doPost(mockRequest, mockResponse);
            verify(mockRequest, never()).setAttribute(eq("Error"), eq("Invalid auction type"));
        }

        @Test
        @DisplayName("Test invalid item condition")
        public void testInvalidItemCon() throws Exception {
            when(mockRequest.getParameter("item_condition")).thenReturn("GOOD");
            mockServlet.doPost(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("Error"), eq("Invalid item condition"));
        }
    }

    @Nested
    @DisplayName("Test image upload")
    class TestImageUpload {

        private Part mockPart(String filename, long size) throws Exception {
            Part part = mock(Part.class);
            when(part.getName()).thenReturn("images");
            when(part.getSize()).thenReturn(size);
            when(part.getSubmittedFileName()).thenReturn(filename);
            return part;
        }

        @Test
        @DisplayName("Test no extension")
        public void testNoExtension() throws Exception {
            Part p1 = mockPart("Test1.jpg", 100L);
            Part p2 = mockPart("Test2", 100L);     // no extension
            Part p3 = mockPart("Test3.png", 100L);

            when(mockRequest.getParts()).thenReturn(List.of(p1, p2, p3));
            mockServlet.doPost(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("Error"), eq("File must have an extension"));
        }

        @Test
        @DisplayName("Test invalid Extension")
        public void testInvalidExtension() throws Exception {
            Part p1 = mockPart("Test1.jpg", 100L);
            Part p2 = mockPart("Test2.gif", 100L);     // no extension
            Part p3 = mockPart("Test3.png", 100L);

            when(mockRequest.getParts()).thenReturn(List.of(p1, p2, p3));
            mockServlet.doPost(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("Error"), eq("Only JPG, PNG, and WEBP images are allowed"));
        }

        @Test
        @DisplayName("Test file too large")
        public void testFileTooLarge() throws Exception {
            when(mockRequest.getParts()).thenThrow(new IllegalStateException("File too large"));
            mockServlet.doPost(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("Error"), eq("Image upload failed"));
        }
    }

    @Nested
    @DisplayName("Test Tags")
    class TestTags {
        @Test
        @DisplayName("Test invalid tag")
        public void TestInvalidTags() throws Exception {
            when(mockRequest.getParameterValues("tags")).thenReturn(new String[]{"99"});
            when(mockTagDAO.getAllTags()).thenReturn(Map.of(1L, "Technology", 2L, "Cars"));
            mockServlet.doPost(mockRequest, mockResponse);
            verify(mockRequest).setAttribute(eq("Error"), eq("Invalid tag selected"));
        }

        @Test
        @DisplayName("Test valid tag")
        public void TestValidTags() throws Exception {
            when(mockRequest.getParameterValues("tags")).thenReturn(new String[]{"99"});
            when(mockTagDAO.getAllTags()).thenReturn(Map.of(1L, "Technology", 99L, "Cars"));
            mockServlet.doPost(mockRequest, mockResponse);
            verify(mockRequest, never()).setAttribute(eq("Error"), eq("Invalid tag selected"));
        }
    }
}
