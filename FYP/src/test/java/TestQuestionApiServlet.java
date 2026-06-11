import com.auction.dao.QuestionDAO;
import com.auction.dao.QuestionDAO.QuestionResult;
import com.auction.servlet.api.QuestionApiServlet;
import com.auction.test.ApiTestSupport;
import com.auction.util.AuthSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

@DisplayName("QuestionApiServlet")
class TestQuestionApiServlet {

    private static class Wrapper extends QuestionApiServlet {
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doPost(req, resp);
        }
    }

    private QuestionDAO mockDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() {
        mockDAO = mock(QuestionDAO.class);
        servlet = new Wrapper();
        servlet.setQuestionDAO(mockDAO);
        req  = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
    }

    @Test
    @DisplayName("ask requires buyer")
    void askSellerForbidden() throws Exception {
        AuthSession s = ApiTestSupport.newSellerSession(1);
        ApiTestSupport.withBearer(req, s);
        when(req.getPathInfo()).thenReturn("/ask");
        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(403);
    }

    @Test
    @DisplayName("ask success")
    void askSuccess() throws Exception {
        AuthSession s = ApiTestSupport.newBuyerSession(2);
        ApiTestSupport.withBearer(req, s);
        when(req.getPathInfo()).thenReturn("/ask");
        when(req.getParameter("auctionId")).thenReturn("5");
        when(req.getParameter("text")).thenReturn("Is this still available?");
        when(mockDAO.insertQuestion(5L, 2, "Is this still available?")).thenReturn(QuestionResult.SUCCESS);

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(200);
    }

    @Test
    @DisplayName("reply requires seller")
    void replyBuyerForbidden() throws Exception {
        AuthSession s = ApiTestSupport.newBuyerSession(2);
        ApiTestSupport.withBearer(req, s);
        when(req.getPathInfo()).thenReturn("/reply");
        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(403);
    }

    @Test
    @DisplayName("reply success")
    void replySuccess() throws Exception {
        AuthSession s = ApiTestSupport.newSellerSession(1);
        ApiTestSupport.withBearer(req, s);
        when(req.getPathInfo()).thenReturn("/reply");
        when(req.getParameter("questionId")).thenReturn("9");
        when(req.getParameter("text")).thenReturn("Yes, item is available.");
        when(mockDAO.insertReply(9L, 1, "Yes, item is available.")).thenReturn(QuestionResult.SUCCESS);

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(200);
    }
}
