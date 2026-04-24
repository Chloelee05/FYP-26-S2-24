import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.auction.servlet.RegisterServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
        when(request.getParameter("password")).thenReturn("Password1!");
        when(request.getParameter("email")).thenReturn("email");
        when(request.getParameter("role")).thenReturn("buyer");

        //until there's a response written in Servlet, check via verify()
/*        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);*/

        new RegisterServletWrapper().doPost(request, response);
        verify(request).setAttribute(eq("Error"), contains("Email"));
        //assertTrue(stringWriter.toString().contains("Error"));
    }

    @Test
    public void testInvalidUsername() throws Exception{
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        when(request.getParameter("username")).thenReturn(" ");
        when(request.getParameter("password")).thenReturn("Password1!");
        when(request.getParameter("email")).thenReturn("Test1@email.com");
        when(request.getParameter("role")).thenReturn("buyer");

        //until there's a response written in Servlet, check via verify()
/*        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);*/

        new RegisterServletWrapper().doPost(request, response);
        verify(request).setAttribute(eq("Error"), eq("Username is required."));
        //assertTrue(stringWriter.toString().contains("Error"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Password", "Password1", "Password@"})
    public void testPasswordValidation(String pass) throws Exception{
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        when(request.getParameter("username")).thenReturn("Test1");
        when(request.getParameter("password")).thenReturn(pass);
        when(request.getParameter("email")).thenReturn("Test1@email.com");
        when(request.getParameter("role")).thenReturn("buyer");

            //until there's a response written in Servlet, check via verify()
/*        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);*/

        new RegisterServletWrapper().doPost(request, response);
        verify(request).setAttribute(eq("Error"), contains("Password"));
        //assertTrue(stringWriter.toString().contains("Error"));
        }

    @Test
    public void testInvalidRole() throws Exception{
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        when(request.getParameter("username")).thenReturn("Test1");
        when(request.getParameter("password")).thenReturn("Password1!");
        when(request.getParameter("email")).thenReturn("Test1@email.com");
        when(request.getParameter("role")).thenReturn("");

        //until there's a response written in Servlet, check via verify()
/*        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);*/

        new RegisterServletWrapper().doPost(request, response);
        verify(request).setAttribute(eq("Error"), eq("Role is required."));
        //assertTrue(stringWriter.toString().contains("Error"));
    }

    @Test
    public void testExistingUsername() throws Exception{
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        when(request.getParameter("username")).thenReturn("user1");
        when(request.getParameter("password")).thenReturn("Password1!");
        when(request.getParameter("email")).thenReturn("Test1@email.com");
        when(request.getParameter("role")).thenReturn("seller");

        //until there's a response written in Servlet, check via verify()
/*        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);*/

        new RegisterServletWrapper().doPost(request, response);
        verify(request).setAttribute(eq("Error"), eq("Username already in use!"));
        //assertTrue(stringWriter.toString().contains("Error"));
    }

    @Test
    public void testExistingEmail() throws Exception{
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        when(request.getParameter("username")).thenReturn("Test1");
        when(request.getParameter("password")).thenReturn("Password1!");
        when(request.getParameter("email")).thenReturn("user1@email.com");
        when(request.getParameter("role")).thenReturn("seller");

        //until there's a response written in Servlet, check via verify()
/*        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);*/

        new RegisterServletWrapper().doPost(request, response);
        verify(request).setAttribute(eq("Error"), eq("Email already in use!"));
        //assertTrue(stringWriter.toString().contains("Error"));
    }

    @Test
    public void testInsert() throws Exception{
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        when(request.getParameter("username")).thenReturn("Test1");
        when(request.getParameter("password")).thenReturn("Password1!");
        when(request.getParameter("email")).thenReturn("test1@email.com");
        when(request.getParameter("role")).thenReturn("seller");

        //until there's a response written in Servlet, check via verify()
/*        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);*/

        new RegisterServletWrapper().doPost(request, response);
        verify(request).setAttribute(eq("Insert"), eq("Insert ran!"));
        //assertTrue(stringWriter.toString().contains("Error"));
    }
}
