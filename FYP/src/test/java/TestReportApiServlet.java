import com.auction.dao.ReportDAO;
import com.auction.dao.ReportDAO.ReportResult;
import com.auction.dao.UserDAO;
import com.auction.servlet.api.ReportApiServlet;
import com.auction.test.ApiTestSupport;
import com.auction.util.AuthSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

@DisplayName("ReportApiServlet")
class TestReportApiServlet {

    private static class Wrapper extends ReportApiServlet {
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doPost(req, resp);
        }
    }

    private ReportDAO mockReportDAO;
    private UserDAO mockUserDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() {
        mockReportDAO = mock(ReportDAO.class);
        mockUserDAO   = mock(UserDAO.class);
        servlet = new Wrapper();
        servlet.setReportDAO(mockReportDAO);
        servlet.setUserDAO(mockUserDAO);
        req  = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
    }

    @Test
    @DisplayName("listing report requires auth")
    void listingReportUnauthorized() throws Exception {
        when(req.getPathInfo()).thenReturn("/");
        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(401);
    }

    @Test
    @DisplayName("listing report success")
    void listingReportSuccess() throws Exception {
        AuthSession s = ApiTestSupport.newBuyerSession(3);
        ApiTestSupport.withBearer(req, s);
        when(req.getPathInfo()).thenReturn("/");
        when(req.getParameter("auctionId")).thenReturn("42");
        when(req.getParameter("description")).thenReturn("Misleading photos");
        when(mockReportDAO.insertReport(42L, 3, "Misleading photos")).thenReturn(ReportResult.SUCCESS);

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(200);
    }

    @Test
    @DisplayName("user report rejects self-report")
    void userReportSelf() throws Exception {
        AuthSession s = ApiTestSupport.newBuyerSession(3);
        ApiTestSupport.withBearer(req, s);
        when(req.getPathInfo()).thenReturn("/user");
        when(req.getParameter("reportedId")).thenReturn("3");
        when(req.getParameter("reason")).thenReturn("Spam");

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(400);
        verify(mockReportDAO, never()).reportUser(any());
    }
}
