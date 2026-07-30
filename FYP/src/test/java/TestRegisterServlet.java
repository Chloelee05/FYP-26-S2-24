import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.auction.dao.UserDAO;
import com.auction.model.Role;
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
import org.mockito.ArgumentCaptor;
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

    /**
     * Buying and selling are one account type, so sign-up presents no role choice.
     * A supplied {@code role} must neither be required nor honoured.
     */
    @Test
    @DisplayName("Registration succeeds without a role parameter")
    public void testRoleNotRequired() throws Exception{
        when(mockRequest.getParameter("username")).thenReturn("Test1");
        when(mockRequest.getParameter("password")).thenReturn("Password1!");
        when(mockRequest.getParameter("confirmPassword")).thenReturn("Password1!");
        when(mockRequest.getParameter("termsAccept")).thenReturn("on");
        when(mockRequest.getParameter("email")).thenReturn("Test1@email.com");
        when(mockRequest.getParameter("role")).thenReturn(null);
        when(mockDAO.insertUser(any(User.class))).thenReturn(true);

        mockServlet.doPost(mockRequest, mockResponse);

        verify(mockRequest).setAttribute(eq("Insert"), eq("Insert ran!"));
        verify(mockRequest, never()).setAttribute(eq("Error"), anyString());
    }

    @Test
    @DisplayName("A supplied role is ignored — the account is always a unified BUYER")
    public void testSuppliedRoleIsIgnored() throws Exception{
        when(mockRequest.getParameter("username")).thenReturn("Test3");
        when(mockRequest.getParameter("password")).thenReturn("Password1!");
        when(mockRequest.getParameter("confirmPassword")).thenReturn("Password1!");
        when(mockRequest.getParameter("termsAccept")).thenReturn("on");
        when(mockRequest.getParameter("email")).thenReturn("test3@email.com");
        when(mockRequest.getParameter("role")).thenReturn("ADMIN");
        when(mockDAO.insertUser(any(User.class))).thenReturn(true);

        mockServlet.doPost(mockRequest, mockResponse);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(mockDAO).insertUser(captor.capture());
        assertEquals(Role.BUYER, captor.getValue().getRole());
        assertFalse(captor.getValue().canSell());
    }

    @Test
    @DisplayName("The sign-up form no longer echoes a role back to the view")
    public void testNoStickyRoleAttribute() throws Exception{
        when(mockRequest.getParameter("username")).thenReturn(" ");
        when(mockRequest.getParameter("password")).thenReturn("Password1!");
        when(mockRequest.getParameter("confirmPassword")).thenReturn("Password1!");
        when(mockRequest.getParameter("termsAccept")).thenReturn("on");
        when(mockRequest.getParameter("email")).thenReturn("Test1@email.com");
        when(mockRequest.getParameter("role")).thenReturn("seller");

        mockServlet.doPost(mockRequest, mockResponse);

        verify(mockRequest, never()).setAttribute(eq("signupRole"), any());
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
