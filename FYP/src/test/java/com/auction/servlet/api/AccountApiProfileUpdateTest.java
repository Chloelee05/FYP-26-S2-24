package com.auction.servlet.api;

import com.auction.dao.PaymentMethodDAO;
import com.auction.dao.ProfileActivityDAO;
import com.auction.dao.UserDAO;
import com.auction.model.Role;
import com.auction.model.User;
import com.auction.test.ApiTestSupport;
import com.auction.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * POST /api/account/update — the data-loss bug and the validation that was missing entirely.
 *
 * <p>{@code updateProfile} writes every profile column unconditionally, and only
 * {@code profileImageUrl} was guarded against an absent parameter. Saving just the display
 * name therefore recomputed the phone and address ciphertexts as null and wrote them, so a
 * member who renamed themselves silently lost their phone number and postal address — in a
 * request that answered "Profile updated successfully."</p>
 *
 * <p>Absent and blank are treated differently on purpose. {@link ApiBase#param} folds
 * {@code ""} to null and cannot tell them apart, so these fields are read raw: a field the
 * request did not carry keeps what is stored, and a field the member emptied is cleared. A
 * blanket preserve-on-blank rule would have been safe against the bug but would also have
 * made it impossible to ever remove an address once entered.</p>
 */
@DisplayName("AccountApiServlet — profile update")
class AccountApiProfileUpdateTest {

    private static class Wrapper extends AccountApiServlet {
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws java.io.IOException {
            super.doPost(req, resp);
        }
    }

    private static final int USER_ID = 8;
    private static final String STORED_PHONE   = "+6591234567";
    private static final String STORED_ADDRESS = "1 Orchard Road, Singapore";

    private UserDAO userDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private String storedPhoneEnc;
    private String storedAddressEnc;

    @BeforeEach
    void setUp() throws Exception {
        userDAO = mock(UserDAO.class);
        servlet = new Wrapper();
        servlet.setUserDAO(userDAO);
        servlet.setProfileActivityDAO(mock(ProfileActivityDAO.class));
        servlet.setPaymentMethodDAO(mock(PaymentMethodDAO.class));

        storedPhoneEnc = SecurityUtil.encrypt(STORED_PHONE);
        storedAddressEnc = SecurityUtil.encrypt(STORED_ADDRESS);

        User current = new User("alice", "Alice@Email.com", "hash", Role.BUYER);
        current.setId(USER_ID);
        current.setPhoneEncrypted(storedPhoneEnc);
        current.setAddressEncrypted(storedAddressEnc);
        current.setProfileImageUrl("/uploads/alice.jpg");
        when(userDAO.getUserById(USER_ID)).thenReturn(current);
        when(userDAO.updateProfile(anyInt(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(true);

        req = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
        ApiTestSupport.withBearer(req, ApiTestSupport.newBuyerSession(USER_ID));
        when(req.getPathInfo()).thenReturn("/update");
        ApiTestSupport.bindJsonWriter(resp);
    }

    private void param(String name, String value) {
        when(req.getParameter(name)).thenReturn(value);
    }

    /** The five values handed to {@code updateProfile}, in column order. */
    private Object[] capturedProfileWrite() {
        ArgumentCaptor<String> username = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> email = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> phone = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> address = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> photo = ArgumentCaptor.forClass(String.class);
        verify(userDAO).updateProfile(eq(USER_ID), username.capture(), email.capture(),
                phone.capture(), address.capture(), photo.capture());
        return new Object[] { username.getValue(), email.getValue(), phone.getValue(),
                address.getValue(), photo.getValue() };
    }

    @Nested
    @DisplayName("absent fields are preserved")
    class Preservation {

        @Test
        @DisplayName("renaming yourself does not erase your phone number or address")
        void usernameOnlyKeepsPhoneAndAddress() throws Exception {
            param("username", "alice2");
            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
            Object[] written = capturedProfileWrite();
            assertEquals("alice2", written[0]);
            assertEquals(storedPhoneEnc, written[2], "stored phone ciphertext must be rewritten as-is");
            assertEquals(storedAddressEnc, written[3], "stored address ciphertext must be rewritten as-is");
        }

        @Test
        @DisplayName("the preserved ciphertext still decrypts to the original values")
        void preservedValuesAreStillReadable() throws Exception {
            param("username", "alice2");
            servlet.doPost(req, resp);

            Object[] written = capturedProfileWrite();
            assertEquals(STORED_PHONE, SecurityUtil.decrypt((String) written[2]));
            assertEquals(STORED_ADDRESS, SecurityUtil.decrypt((String) written[3]));
        }

        @Test
        @DisplayName("an omitted username keeps the stored one")
        void omittedUsernameKeepsStored() throws Exception {
            param("phone", "+6598887777");
            servlet.doPost(req, resp);

            assertEquals("alice", capturedProfileWrite()[0]);
        }

        @Test
        @DisplayName("the profile photo guard that was already there still holds")
        void photoStillPreserved() throws Exception {
            param("username", "alice2");
            servlet.doPost(req, resp);

            assertEquals("/uploads/alice.jpg", capturedProfileWrite()[4]);
        }

        @Test
        @DisplayName("the email is taken from the stored row, lower-cased, never from the request")
        void emailIsNotEditable() throws Exception {
            param("username", "alice2");
            param("email", "attacker@evil.test");
            servlet.doPost(req, resp);

            assertEquals("alice@email.com", capturedProfileWrite()[1]);
        }
    }

    @Nested
    @DisplayName("blank fields are cleared")
    class Clearing {

        @Test
        @DisplayName("an explicitly emptied phone is removed rather than preserved")
        void blankPhoneClears() throws Exception {
            param("username", "alice");
            param("phone", "");
            servlet.doPost(req, resp);

            Object[] written = capturedProfileWrite();
            assertNull(written[2], "the member asked for it to be gone");
            assertEquals(storedAddressEnc, written[3], "but the address was not touched");
        }

        @Test
        @DisplayName("an explicitly emptied address is removed")
        void blankAddressClears() throws Exception {
            param("username", "alice");
            param("address", "   ");
            servlet.doPost(req, resp);

            assertNull(capturedProfileWrite()[3]);
        }

        @Test
        @DisplayName("a new phone value is stored encrypted, not in clear")
        void newPhoneIsEncrypted() throws Exception {
            param("phone", " +6598887777 ");
            servlet.doPost(req, resp);

            String written = (String) capturedProfileWrite()[2];
            assertNotEquals("+6598887777", written);
            assertEquals("+6598887777", SecurityUtil.decrypt(written));
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("a blank display name is refused — it would become the member's name everywhere")
        void blankUsernameRejected() throws Exception {
            param("username", "");
            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            verify(userDAO, never()).updateProfile(anyInt(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a whitespace-only display name is refused too")
        void whitespaceUsernameRejected() throws Exception {
            param("username", "   \t  ");
            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            verify(userDAO, never()).updateProfile(anyInt(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("an over-long display name is refused rather than truncated by the column")
        void overlongUsernameRejected() throws Exception {
            param("username", "a".repeat(256));
            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            verify(userDAO, never()).updateProfile(anyInt(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a 20 000-character phone number no longer persists")
        void overlongPhoneRejected() throws Exception {
            param("phone", "9".repeat(20_000));
            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            verify(userDAO, never()).updateProfile(anyInt(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a phone number of a plausible length is accepted")
        void plausiblePhoneAccepted() throws Exception {
            param("phone", "+65 9123 4567");
            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
        }

        @Test
        @DisplayName("a phone number containing letters is refused")
        void phoneWithLettersRejected() throws Exception {
            param("phone", "+65 9123 4567 ext 8899");
            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            verify(userDAO, never()).updateProfile(anyInt(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a phone number with too few digits is refused")
        void phoneTooShortRejected() throws Exception {
            param("phone", "12345");
            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            verify(userDAO, never()).updateProfile(anyInt(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("an over-long address is refused")
        void overlongAddressRejected() throws Exception {
            param("address", "x".repeat(501));
            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            verify(userDAO, never()).updateProfile(anyInt(), any(), any(), any(), any(), any());
        }
    }
}
