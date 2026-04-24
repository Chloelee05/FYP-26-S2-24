import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.auction.servlet.RegisterServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.*;


public class TestRegisterServlet extends Mockito{

    private static class RegisterServletWrapper extends RegisterServlet{
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            super.doPost(req, resp);
        }
    }

    @Test
    public void testInvalidEmail() throws Exception{
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        when(request.getParameter("username")).thenReturn("Test1");
        when(request.getParameter("password")).thenReturn("Password1");
        when(request.getParameter("email")).thenReturn("email");
        when(request.getParameter("role")).thenReturn("buyer");

        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);

        new RegisterServletWrapper().doPost(request, response);
        verify(request).setAttribute("Error", "There is something wrong with your details");
        //assertTrue(stringWriter.toString().contains("Error"));
    }
}
