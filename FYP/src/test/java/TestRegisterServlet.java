import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.auction.dao.UserDAO;
import com.auction.model.User;
import com.auction.servlet.RegisterServlet;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.io.*;

@DisplayName("Testing RegisterServlet")
public class TestRegisterServlet extends Mockito{

    private static class RegisterServletWrapper extends RegisterServlet{
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            super.doPost(req, resp);
        }
    }

    private UserDAO mockDAO;
    private RegisterServletWrapper mockServlet;
    private HttpServletRequest mockRequest;
    private HttpServletResponse mockResponse;
    
    @BeforeEach
    public void setUp()
    {
        mockDAO = mock(UserDAO.class);
        mockServlet = new RegisterServletWrapper();
        mockServlet.setUserDAO(mockDAO);

        mockRequest = mock(HttpServletRequest.class);
        mockResponse = mock(HttpServletResponse.class);
        RequestDispatcher dispatcher = mock(RequestDispatcher.class);
        when(mockRequest.getRequestDispatcher(anyString())).thenReturn(dispatcher);
    }
    
    @Test
    @DisplayName("Testing invalid emails")
    public void testInvalidEmail() throws Exception{
        when(mockRequest.getParameter("username")).thenReturn("Test1");
        when(mockRequest.getParameter("password")).thenReturn("Password1!");
        when(mockRequest.getParameter("confirmPassword")).thenReturn("Password1!");
        when(mockRequest.getParameter("termsAccept")).thenReturn("on");
        when(mockRequest.getParameter("email")).thenReturn("email");
        when(mockRequest.getParameter("role")).thenReturn("buyer");

        //until there's a response written in Servlet, check via verify()
/*        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);*/

        mockServlet.doPost(mockRequest, mockResponse);
        verify(mockRequest).setAttribute(eq("Error"), contains("Email"));
        //assertTrue(stringWriter.toString().contains("Error"));
    }

    @Test
    @DisplayName("Testing invalid username")
    public void testInvalidUsername() throws Exception{
        when(mockRequest.getParameter("username")).thenReturn(" ");
        when(mockRequest.getParameter("password")).thenReturn("Password1!");
        when(mockRequest.getParameter("confirmPassword")).thenReturn("Password1!");
        when(mockRequest.getParameter("termsAccept")).thenReturn("on");
        when(mockRequest.getParameter("email")).thenReturn("Test1@email.com");
        when(mockRequest.getParameter("role")).thenReturn("buyer");

        //until there's a response written in Servlet, check via verify()
/*        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);*/

        mockServlet.doPost(mockRequest, mockResponse);
        verify(mockRequest).setAttribute(eq("Error"), eq("Username is required."));
        //assertTrue(stringWriter.toString().contains("Error"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Password", "Password1", "Password@"})
    @DisplayName("Testing invalid passwords")
    public void testPasswordValidation(String pass) throws Exception{
        when(mockRequest.getParameter("username")).thenReturn("Test1");
        when(mockRequest.getParameter("password")).thenReturn(pass);
        when(mockRequest.getParameter("confirmPassword")).thenReturn(pass);
        when(mockRequest.getParameter("termsAccept")).thenReturn("on");
        when(mockRequest.getParameter("email")).thenReturn("Test1@email.com");
        when(mockRequest.getParameter("role")).thenReturn("buyer");

            //until there's a response written in Servlet, check via verify()
/*        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);*/

        mockServlet.doPost(mockRequest, mockResponse);
        verify(mockRequest).setAttribute(eq("Error"), contains("Password"));
        //assertTrue(stringWriter.toString().contains("Error"));
        }

    @Test
    @DisplayName("Testing invalid roles")
    public void testInvalidRole() throws Exception{
        when(mockRequest.getParameter("username")).thenReturn("Test1");
        when(mockRequest.getParameter("password")).thenReturn("Password1!");
        when(mockRequest.getParameter("confirmPassword")).thenReturn("Password1!");
        when(mockRequest.getParameter("termsAccept")).thenReturn("on");
        when(mockRequest.getParameter("email")).thenReturn("Test1@email.com");
        when(mockRequest.getParameter("role")).thenReturn("");

        //until there's a response written in Servlet, check via verify()
/*        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);*/

        mockServlet.doPost(mockRequest, mockResponse);
        verify(mockRequest).setAttribute(eq("Error"), eq("Role is required."));
        //assertTrue(stringWriter.toString().contains("Error"));
    }

    @Test
    @DisplayName("Testing existing username")
    public void testExistingUsername() throws Exception{
        when(mockDAO.checkUser("user1")).thenReturn(true);

        when(mockRequest.getParameter("username")).thenReturn("user1");
        when(mockRequest.getParameter("password")).thenReturn("Password1!");
        when(mockRequest.getParameter("confirmPassword")).thenReturn("Password1!");
        when(mockRequest.getParameter("termsAccept")).thenReturn("on");
        when(mockRequest.getParameter("email")).thenReturn("Test1@email.com");
        when(mockRequest.getParameter("role")).thenReturn("seller");

        //until there's a response written in Servlet, check via verify()
/*        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);*/

        mockServlet.doPost(mockRequest, mockResponse);
        verify(mockRequest).setAttribute(eq("Error"), eq("Username already in use!"));
        //assertTrue(stringWriter.toString().contains("Error"));
    }

    @Test
    @DisplayName("Testing existing email")
    public void testExistingEmail() throws Exception{
        when(mockDAO.checkEmail("user1@email.com")).thenReturn(true);

        when(mockRequest.getParameter("username")).thenReturn("Test1");
        when(mockRequest.getParameter("password")).thenReturn("Password1!");
        when(mockRequest.getParameter("confirmPassword")).thenReturn("Password1!");
        when(mockRequest.getParameter("termsAccept")).thenReturn("on");
        when(mockRequest.getParameter("email")).thenReturn("user1@email.com");
        when(mockRequest.getParameter("role")).thenReturn("seller");

        //until there's a response written in Servlet, check via verify()
/*        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);*/

        mockServlet.doPost(mockRequest, mockResponse);
        verify(mockRequest).setAttribute(eq("Error"), eq("Email already in use!"));
        //assertTrue(stringWriter.toString().contains("Error"));
    }

    @Test
    @DisplayName("Testing insert")
    public void testInsert() throws Exception{
        when(mockRequest.getParameter("username")).thenReturn("Test2");
        when(mockRequest.getParameter("password")).thenReturn("Password1!");
        when(mockRequest.getParameter("confirmPassword")).thenReturn("Password1!");
        when(mockRequest.getParameter("termsAccept")).thenReturn("on");
        when(mockRequest.getParameter("email")).thenReturn("test1@email.com");
        when(mockRequest.getParameter("role")).thenReturn("seller");
        when(mockDAO.insertUser(any(User.class))).thenReturn(true);
        
        //until there's a response written in Servlet, check via verify()
/*        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);*/

        mockServlet.doPost(mockRequest, mockResponse);
        verify(mockRequest).setAttribute(eq("Insert"), eq("Insert ran!"));
        //assertTrue(stringWriter.toString().contains("Error"));
    }
}
