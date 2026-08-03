package com.auction.servlet.api;

import com.auction.dao.PaymentMethodDAO;
import com.auction.dao.ProfileActivityDAO;
import com.auction.dao.UserDAO;
import com.auction.model.PaymentMethod;
import com.auction.test.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.time.Instant;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * POST /api/account/payment-methods — the update path, expiry validation, and truthful
 * delete/default responses.
 *
 * <p>Three gaps are pinned here. There was no way to <em>update</em> a stored payment method
 * at all, only add and delete, so the "create/update/delete personal account details (name,
 * address, credit card)" requirement was met for two of its three named examples. Expiry
 * validation checked the shape of the month and year but never compared them to today, so a
 * card that expired in 2020 was accepted and stored as a payable method. And a delete of
 * somebody else's id answered {@code 200 "Payment method removed."} — the DAO's owner-scoped
 * WHERE clause meant nothing was actually deleted, so the response was simply false.</p>
 */
@DisplayName("AccountApiServlet — payment method maintenance")
class AccountApiPaymentMethodUpdateTest {

    private static class Wrapper extends AccountApiServlet {
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws java.io.IOException {
            super.doPost(req, resp);
        }
    }

    private PaymentMethodDAO paymentDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private StringWriter body;

    private static final int USER_ID = 8;

    @BeforeEach
    void setUp() throws Exception {
        paymentDAO = mock(PaymentMethodDAO.class);
        servlet = new Wrapper();
        servlet.setUserDAO(mock(UserDAO.class));
        servlet.setProfileActivityDAO(mock(ProfileActivityDAO.class));
        servlet.setPaymentMethodDAO(paymentDAO);

        req = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
        ApiTestSupport.withBearer(req, ApiTestSupport.newBuyerSession(USER_ID));
        when(req.getPathInfo()).thenReturn("/payment-methods");
        body = ApiTestSupport.bindJsonWriter(resp);
    }

    private void param(String name, String value) {
        when(req.getParameter(name)).thenReturn(value);
    }

    private String errorMessage() throws Exception {
        JsonNode node = ApiTestSupport.parse(body);
        assertTrue(node.has("error"), "expected an error body, got: " + body);
        return node.get("error").asText();
    }

    private static PaymentMethod card(long id) {
        return new PaymentMethod(id, "CARD", "Alice Tan", "Visa", "4242",
                12, YearMonth.now().getYear() + 2, null, true, Instant.now());
    }

    private static PaymentMethod paypal(long id) {
        return new PaymentMethod(id, "PAYPAL", null, "PayPal", null,
                0, 0, "alice@paypal.com", false, Instant.now());
    }

    private static PaymentMethod bank(long id) {
        return new PaymentMethod(id, "BANK_TRANSFER", "Alice Tan", "Bank", "6789",
                0, 0, "DBS", false, Instant.now());
    }

    // ── Expiry must be in the future ─────────────────────────────────────────────

    @Nested
    @DisplayName("expiry validation")
    class ExpiryValidation {

        private void addCardWithExpiry(int month, int year) throws Exception {
            param("cardHolder", "Alice Tan");
            param("cardNumber", "4111111111111111");
            param("expMonth", String.valueOf(month));
            param("expYear", String.valueOf(year));
            servlet.doPost(req, resp);
        }

        @Test
        @DisplayName("a card that expired years ago is refused on add")
        void longExpiredCardRejected() throws Exception {
            addCardWithExpiry(12, 2020);
            verify(resp).setStatus(400);
            assertTrue(errorMessage().contains("expired"), errorMessage());
            verify(paymentDAO, never()).add(anyInt(), anyString(), anyString(), anyInt(), anyInt(), anyBoolean());
        }

        @Test
        @DisplayName("a card that expired last month is refused too")
        void justExpiredCardRejected() throws Exception {
            YearMonth lastMonth = YearMonth.now().minusMonths(1);
            addCardWithExpiry(lastMonth.getMonthValue(), lastMonth.getYear());
            verify(resp).setStatus(400);
            verify(paymentDAO, never()).add(anyInt(), anyString(), anyString(), anyInt(), anyInt(), anyBoolean());
        }

        @Test
        @DisplayName("a card expiring this month is still valid — cards run to month end")
        void expiringThisMonthAccepted() throws Exception {
            YearMonth now = YearMonth.now();
            addCardWithExpiry(now.getMonthValue(), now.getYear());
            verify(resp).setStatus(200);
            verify(paymentDAO).add(USER_ID, "Alice Tan", "4111111111111111",
                    now.getMonthValue(), now.getYear(), false);
        }

        @Test
        @DisplayName("an implausibly distant year is refused (exp_year is a smallint)")
        void farFutureYearRejected() throws Exception {
            addCardWithExpiry(6, 99999);
            verify(resp).setStatus(400);
            verify(paymentDAO, never()).add(anyInt(), anyString(), anyString(), anyInt(), anyInt(), anyBoolean());
        }

        @Test
        @DisplayName("month 13 is still refused")
        void monthOutOfRangeRejected() throws Exception {
            addCardWithExpiry(13, YearMonth.now().getYear() + 1);
            verify(resp).setStatus(400);
            verify(paymentDAO, never()).add(anyInt(), anyString(), anyString(), anyInt(), anyInt(), anyBoolean());
        }
    }

    // ── Update ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("action=update")
    class Update {

        @Test
        @DisplayName("edits a card's holder name and expiry")
        void updatesCard() throws Exception {
            int year = YearMonth.now().getYear() + 3;
            when(paymentDAO.findForUser(USER_ID, 5L)).thenReturn(card(5));
            when(paymentDAO.updateCard(USER_ID, 5L, "Alice B Tan", 9, year)).thenReturn(true);

            param("action", "update");
            param("id", "5");
            param("cardHolder", "Alice B Tan");
            param("expMonth", "9");
            param("expYear", String.valueOf(year));
            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
            verify(paymentDAO).updateCard(USER_ID, 5L, "Alice B Tan", 9, year);
        }

        @Test
        @DisplayName("refuses to re-expire a card through the update path")
        void updateRejectsExpiredExpiry() throws Exception {
            when(paymentDAO.findForUser(USER_ID, 5L)).thenReturn(card(5));

            param("action", "update");
            param("id", "5");
            param("cardHolder", "Alice Tan");
            param("expMonth", "1");
            param("expYear", "2020");
            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            verify(paymentDAO, never()).updateCard(anyInt(), anyLong(), anyString(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("says so rather than silently ignoring an attempt to change the card number")
        void updateRefusesPanChange() throws Exception {
            when(paymentDAO.findForUser(USER_ID, 5L)).thenReturn(card(5));

            param("action", "update");
            param("id", "5");
            param("cardNumber", "4111111111111111");
            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            assertTrue(errorMessage().contains("cannot be changed"), errorMessage());
            verify(paymentDAO, never()).updateCard(anyInt(), anyLong(), anyString(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("an id that is not the caller's is 404, not a false success")
        void updateUnknownIdIs404() throws Exception {
            when(paymentDAO.findForUser(USER_ID, 99L)).thenReturn(null);

            param("action", "update");
            param("id", "99");
            param("cardHolder", "Mallory");
            param("expMonth", "1");
            param("expYear", String.valueOf(YearMonth.now().getYear() + 1));
            servlet.doPost(req, resp);

            verify(resp).setStatus(404);
            verify(paymentDAO, never()).updateCard(anyInt(), anyLong(), anyString(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("the editable field set comes from the stored type, not the request")
        void storedTypeDecidesFields() throws Exception {
            when(paymentDAO.findForUser(USER_ID, 5L)).thenReturn(card(5));

            // A caller claiming type=paypal at a card row must not get an email written in.
            param("action", "update");
            param("id", "5");
            param("type", "paypal");
            param("paypalEmail", "mallory@paypal.com");
            servlet.doPost(req, resp);

            verify(paymentDAO, never()).updatePaypal(anyInt(), anyLong(), anyString());
            // Falls through to the card branch, which refuses because its fields are absent.
            verify(resp).setStatus(400);
        }

        @Test
        @DisplayName("repoints a PayPal method at a new email")
        void updatesPaypal() throws Exception {
            when(paymentDAO.findForUser(USER_ID, 6L)).thenReturn(paypal(6));
            when(paymentDAO.updatePaypal(USER_ID, 6L, "alice.new@paypal.com")).thenReturn(true);

            param("action", "update");
            param("id", "6");
            param("paypalEmail", "alice.new@paypal.com");
            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
            verify(paymentDAO).updatePaypal(USER_ID, 6L, "alice.new@paypal.com");
        }

        @Test
        @DisplayName("a malformed PayPal email is refused on update as it is on add")
        void updatePaypalValidatesEmail() throws Exception {
            when(paymentDAO.findForUser(USER_ID, 6L)).thenReturn(paypal(6));

            param("action", "update");
            param("id", "6");
            param("paypalEmail", "not-an-email");
            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            verify(paymentDAO, never()).updatePaypal(anyInt(), anyLong(), anyString());
        }

        @Test
        @DisplayName("edits a bank method's holder and bank name")
        void updatesBankTransfer() throws Exception {
            when(paymentDAO.findForUser(USER_ID, 7L)).thenReturn(bank(7));
            when(paymentDAO.updateBankTransfer(USER_ID, 7L, "Alice B Tan", "OCBC")).thenReturn(true);

            param("action", "update");
            param("id", "7");
            param("accountHolder", "Alice B Tan");
            param("bankName", "OCBC");
            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
            verify(paymentDAO).updateBankTransfer(USER_ID, 7L, "Alice B Tan", "OCBC");
        }

        @Test
        @DisplayName("refuses an attempt to change a stored bank account number")
        void updateRefusesBankNumberChange() throws Exception {
            when(paymentDAO.findForUser(USER_ID, 7L)).thenReturn(bank(7));

            param("action", "update");
            param("id", "7");
            param("accountNumber", "9999999999");
            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            verify(paymentDAO, never()).updateBankTransfer(anyInt(), anyLong(), anyString(), anyString());
        }

        @Test
        @DisplayName("a missing id is a 400 before any lookup")
        void updateNeedsId() throws Exception {
            param("action", "update");
            servlet.doPost(req, resp);
            verify(resp).setStatus(400);
            verify(paymentDAO, never()).findForUser(anyInt(), anyLong());
        }

        @Test
        @DisplayName("a row that vanishes between the read and the write is 404")
        void updateLosingTheRaceIs404() throws Exception {
            when(paymentDAO.findForUser(USER_ID, 5L)).thenReturn(card(5));
            when(paymentDAO.updateCard(anyInt(), anyLong(), anyString(), anyInt(), anyInt()))
                    .thenReturn(false);

            param("action", "update");
            param("id", "5");
            param("cardHolder", "Alice Tan");
            param("expMonth", "1");
            param("expYear", String.valueOf(YearMonth.now().getYear() + 1));
            servlet.doPost(req, resp);

            verify(resp).setStatus(404);
        }
    }

    // ── Truthful delete / default ───────────────────────────────────────────────

    @Nested
    @DisplayName("truthful responses")
    class Truthfulness {

        @Test
        @DisplayName("deleting an id that removed nothing answers 404, not 'removed'")
        void deleteNothingIs404() throws Exception {
            when(paymentDAO.delete(USER_ID, 99L)).thenReturn(false);

            param("action", "delete");
            param("id", "99");
            servlet.doPost(req, resp);

            verify(resp).setStatus(404);
            assertFalse(body.toString().contains("removed"), body.toString());
        }

        @Test
        @DisplayName("deleting one's own method still answers 200")
        void deleteOwnIs200() throws Exception {
            when(paymentDAO.delete(USER_ID, 5L)).thenReturn(true);

            param("action", "delete");
            param("id", "5");
            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
        }

        @Test
        @DisplayName("promoting an id that is not the caller's answers 404")
        void defaultNothingIs404() throws Exception {
            when(paymentDAO.setDefault(USER_ID, 99L)).thenReturn(false);

            param("action", "default");
            param("id", "99");
            servlet.doPost(req, resp);

            verify(resp).setStatus(404);
        }

        @Test
        @DisplayName("promoting one's own method answers 200")
        void defaultOwnIs200() throws Exception {
            when(paymentDAO.setDefault(USER_ID, 5L)).thenReturn(true);

            param("action", "default");
            param("id", "5");
            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
        }
    }
}
