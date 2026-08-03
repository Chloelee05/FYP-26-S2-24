package com.auction.servlet.api;

import com.auction.dao.PaymentMethodDAO;
import com.auction.dao.ProfileActivityDAO;
import com.auction.dao.ProfileActivityDAO.TxFilter;
import com.auction.dao.UserDAO;
import com.auction.model.PaymentMethod;
import com.auction.model.User;
import com.auction.util.AuthSession;
import com.auction.util.SecurityUtil;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET  /api/account            — current user's full profile + stats
 * POST /api/account/update     — update display name, phone, address, profileImageUrl
 * POST /api/account/delete     — delete account (param: confirm=DELETE)
 * GET  /api/account/transactions — transaction history (param: filter=ALL|PURCHASE|SALE)
 * GET  /api/account/rating      — rating summary
 * GET  /api/account/reviews     — reviews about this user
 * GET  /api/account/payment-methods  — saved cards / PayPal / bank accounts
 * POST /api/account/payment-methods  — action=add|update|delete|default
 */
@WebServlet("/api/account/*")
public class AccountApiServlet extends ApiBase {

    /**
     * How far into the future a card expiry may be. Cards are issued for a handful of years;
     * anything beyond this is a typo, and {@code exp_year} is a {@code smallint} that a
     * six-digit year would not fit in anyway.
     */
    private static final int MAX_EXPIRY_YEARS_AHEAD = 30;

    private UserDAO            userDAO;
    private ProfileActivityDAO actDAO;
    private PaymentMethodDAO   paymentDAO;

    public AccountApiServlet() {
        this.userDAO    = new UserDAO();
        this.actDAO     = new ProfileActivityDAO();
        this.paymentDAO = new PaymentMethodDAO();
    }

    /** Test hook */
    public void setUserDAO(UserDAO userDAO)                   { this.userDAO    = userDAO; }
    public void setProfileActivityDAO(ProfileActivityDAO dao) { this.actDAO     = dao; }
    public void setPaymentMethodDAO(PaymentMethodDAO pm)      { this.paymentDAO = pm; }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        int userId = sessionUserId(req);

        String sub = sub(req);
        switch (sub) {
            case "transactions":    handleTransactions(req, resp, userId); break;
            case "rating":          ok(resp, actDAO.getRatingSummary(userId)); break;
            case "reviews":         ok(resp, actDAO.listReviewsAboutUser(userId)); break;
            case "payment-methods": ok(resp, paymentDAO.listForUser(userId)); break;
            default:                handleProfile(resp, userId); break;
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        int userId = sessionUserId(req);

        String sub = sub(req);
        switch (sub) {
            case "update":          handleUpdate(req, resp, userId); break;
            case "delete":          handleDelete(req, resp, userId); break;
            case "payment-methods": handlePaymentMethodWrite(req, resp, userId); break;
            case "enable-selling":  handleEnableSelling(req, resp, userId); break;
            default: error(resp, 404, "Unknown account endpoint"); break;
        }
    }

    /**
     * POST /api/account/enable-selling — turns on the selling capability for the
     * signed-in buyer. Buying and selling share one account, so this is an opt-in
     * rather than a separate registration.
     *
     * <p>Admins are rejected: the admin console is a separate surface and an admin
     * listing their own items would muddy moderation.</p>
     */
    private void handleEnableSelling(HttpServletRequest req, HttpServletResponse resp, int userId)
            throws IOException {
        AuthSession session = authSession(req);
        if (isAdmin(session)) {
            error(resp, 403, "Admin accounts cannot list items for sale.");
            return;
        }

        User user = userDAO.getUserById(userId);
        if (user == null) { error(resp, 404, "Account not found."); return; }

        Map<String, Object> body = new LinkedHashMap<>();
        if (user.canSell()) {
            // Idempotent: re-enabling is a no-op rather than an error.
            body.put("canSell", true);
            body.put("message", "Selling is already enabled on this account.");
            ok(resp, body);
            return;
        }

        if (!userDAO.enableSelling(userId)) {
            serverError(resp, "Could not enable selling. Please try again.");
            return;
        }

        // Reflect it on the live session so seller endpoints accept the very next call.
        if (session != null) session.setAttribute("canSell", Boolean.TRUE);

        body.put("canSell", true);
        body.put("message", "Selling enabled. You can now create listings.");
        ok(resp, body);
    }

    /** POST /api/account/payment-methods  action=add|update|delete|default */
    private void handlePaymentMethodWrite(HttpServletRequest req, HttpServletResponse resp, int userId)
            throws IOException {
        String action = param(req, "action");
        if (action == null) action = "add";

        if ("delete".equalsIgnoreCase(action)) {
            Long id = parseLong(param(req, "id"));
            if (id == null) { badRequest(resp, "id is required."); return; }
            // The DAO scopes the DELETE to the owner, so somebody else's id never deletes
            // anything — but the row count has to be honoured, or the caller is told
            // "removed" about a row that is still there (or never existed).
            if (!paymentDAO.delete(userId, id)) {
                error(resp, 404, "Payment method not found."); return;
            }
            okMsg(resp, "Payment method removed.");
            return;
        }
        if ("default".equalsIgnoreCase(action)) {
            Long id = parseLong(param(req, "id"));
            if (id == null) { badRequest(resp, "id is required."); return; }
            if (!paymentDAO.setDefault(userId, id)) {
                error(resp, 404, "Payment method not found."); return;
            }
            okMsg(resp, "Default payment method updated.");
            return;
        }
        if ("update".equalsIgnoreCase(action)) {
            try {
                updatePaymentMethod(req, resp, userId);
            } catch (RuntimeException e) {
                getServletContext().log("update payment method failed", e);
                serverError(resp, "Could not update payment method. Please try again.");
            }
            return;
        }

        // add — dispatch on the requested method type (default: card)
        String type = param(req, "type");
        boolean makeDefault = "true".equalsIgnoreCase(param(req, "makeDefault"));

        try {
            if (type != null && type.equalsIgnoreCase("paypal")) {
                addPaypal(req, resp, userId, makeDefault);
            } else if (type != null && (type.equalsIgnoreCase("bank_transfer") || type.equalsIgnoreCase("bank"))) {
                addBankTransfer(req, resp, userId, makeDefault);
            } else {
                addCard(req, resp, userId, makeDefault);
            }
        } catch (RuntimeException e) {
            getServletContext().log("add payment method failed", e);
            serverError(resp, "Could not save payment method. Run DB migrations and try again.");
        }
    }

    /**
     * POST /api/account/payment-methods action=update — edits a stored method in place.
     *
     * <p>The editable field set is decided by the method's own type, read from the database
     * rather than trusted from the request, so a caller cannot send {@code type=paypal} at a
     * card row and have an email written into it.</p>
     *
     * <p>Card and bank <em>numbers</em> are not editable. Only their ciphertext plus a brand
     * and last-4 for display are stored, so replacing the number replaces everything the
     * member identifies the row by and requires fresh encryption — that is an add, and the
     * endpoint says so instead of silently ignoring the field.</p>
     */
    private void updatePaymentMethod(HttpServletRequest req, HttpServletResponse resp, int userId)
            throws IOException {
        Long id = parseLong(param(req, "id"));
        if (id == null) { badRequest(resp, "id is required."); return; }

        PaymentMethod existing = paymentDAO.findForUser(userId, id);
        if (existing == null) { error(resp, 404, "Payment method not found."); return; }

        switch (existing.getMethodType()) {
            case "PAYPAL":        updatePaypal(req, resp, userId, id);       break;
            case "BANK_TRANSFER": updateBankTransfer(req, resp, userId, id); break;
            default:              updateCard(req, resp, userId, id);         break;
        }
    }

    private void updateCard(HttpServletRequest req, HttpServletResponse resp, int userId, long id)
            throws IOException {
        if (param(req, "cardNumber") != null) {
            badRequest(resp, "A saved card number cannot be changed. "
                    + "Add the new card and remove this one."); return;
        }
        String holder = param(req, "cardHolder");
        String monthS = param(req, "expMonth");
        String yearS  = param(req, "expYear");
        if (holder == null || monthS == null || yearS == null) {
            badRequest(resp, "cardHolder, expMonth and expYear are required."); return;
        }
        Integer[] expiry = parseExpiry(resp, monthS, yearS);
        if (expiry == null) return;

        if (!paymentDAO.updateCard(userId, id, holder.trim(), expiry[0], expiry[1])) {
            error(resp, 404, "Payment method not found."); return;
        }
        okMsg(resp, "Card updated.");
    }

    private void updatePaypal(HttpServletRequest req, HttpServletResponse resp, int userId, long id)
            throws IOException {
        String email = paypalEmail(req, resp);
        if (email == null) return;
        if (!paymentDAO.updatePaypal(userId, id, email)) {
            error(resp, 404, "Payment method not found."); return;
        }
        okMsg(resp, "PayPal account updated.");
    }

    private void updateBankTransfer(HttpServletRequest req, HttpServletResponse resp, int userId, long id)
            throws IOException {
        if (param(req, "accountNumber") != null) {
            badRequest(resp, "A saved bank account number cannot be changed. "
                    + "Add the new account and remove this one."); return;
        }
        String holder   = param(req, "accountHolder");
        String bankName = param(req, "bankName");
        if (holder == null || bankName == null) {
            badRequest(resp, "accountHolder and bankName are required."); return;
        }
        if (!paymentDAO.updateBankTransfer(userId, id, holder.trim(), bankName.trim())) {
            error(resp, 404, "Payment method not found."); return;
        }
        okMsg(resp, "Bank account updated.");
    }

    private void addCard(HttpServletRequest req, HttpServletResponse resp, int userId, boolean makeDefault)
            throws IOException {
        String holder = param(req, "cardHolder");
        String number = param(req, "cardNumber");
        String monthS = param(req, "expMonth");
        String yearS  = param(req, "expYear");

        if (holder == null || number == null || monthS == null || yearS == null) {
            badRequest(resp, "cardHolder, cardNumber, expMonth and expYear are required."); return;
        }
        String digits = number.replaceAll("\\D", "");
        if (digits.length() < 13 || digits.length() > 19) {
            badRequest(resp, "Enter a valid card number."); return;
        }
        Integer[] expiry = parseExpiry(resp, monthS, yearS);
        if (expiry == null) return;

        paymentDAO.add(userId, holder.trim(), digits, expiry[0], expiry[1], makeDefault);
        okMsg(resp, "Payment method added.");
    }

    /**
     * Parses and validates a card expiry, writing the 400 itself and returning null when it
     * is unusable. Returns {@code {month, year}} on success.
     *
     * <p>An already-past expiry is refused. The month/year pair was previously only checked
     * for shape — 1–12 and any year from 2000 — so {@code 12/2020} was accepted and stored as
     * a payable method, which is a payment method that cannot take a payment. The comparison
     * is on whole months because that is the granularity a card carries: a card marked 08/26
     * is valid until the last day of August 2026.</p>
     */
    private Integer[] parseExpiry(HttpServletResponse resp, String monthS, String yearS)
            throws IOException {
        int month, year;
        try { month = Integer.parseInt(monthS); year = Integer.parseInt(yearS); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid expiry."); return null; }

        if (month < 1 || month > 12) { badRequest(resp, "Expiry month must be 1–12."); return null; }
        YearMonth now = YearMonth.now();
        if (year < 2000 || year > now.getYear() + MAX_EXPIRY_YEARS_AHEAD) {
            badRequest(resp, "Invalid expiry year."); return null;
        }
        if (YearMonth.of(year, month).isBefore(now)) {
            badRequest(resp, "That card has already expired. Check the expiry date."); return null;
        }
        return new Integer[] { month, year };
    }

    private void addPaypal(HttpServletRequest req, HttpServletResponse resp, int userId, boolean makeDefault)
            throws IOException {
        String email = paypalEmail(req, resp);
        if (email == null) return;
        paymentDAO.addPaypal(userId, email, makeDefault);
        okMsg(resp, "PayPal account linked.");
    }

    /** The PayPal email from the request, or null after writing the 400 itself. */
    private String paypalEmail(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String email = param(req, "paypalEmail");
        if (email == null) email = param(req, "email");
        if (email == null || email.isBlank()) {
            badRequest(resp, "A PayPal email is required."); return null;
        }
        email = email.trim();
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            badRequest(resp, "Enter a valid PayPal email."); return null;
        }
        return email;
    }

    private void addBankTransfer(HttpServletRequest req, HttpServletResponse resp, int userId, boolean makeDefault)
            throws IOException {
        String holder   = param(req, "accountHolder");
        String account  = param(req, "accountNumber");
        String bankName = param(req, "bankName");

        if (holder == null || account == null || bankName == null
                || holder.isBlank() || account.isBlank() || bankName.isBlank()) {
            badRequest(resp, "accountHolder, accountNumber and bankName are required."); return;
        }
        String digits = account.replaceAll("\\D", "");
        if (digits.length() < 4 || digits.length() > 20) {
            badRequest(resp, "Enter a valid bank account number."); return;
        }
        paymentDAO.addBankTransfer(userId, holder.trim(), digits, bankName.trim(), makeDefault);
        okMsg(resp, "Bank account added.");
    }

    private Long parseLong(String s) {
        if (s == null) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }

    private void handleProfile(HttpServletResponse resp, int userId) throws IOException {
        User user = userDAO.getUserById(userId);
        if (user == null) { error(resp, 404, "User not found."); return; }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id",       user.getId());
        body.put("username", user.getUsername());
        body.put("email",    user.getEmail());
        body.put("role",     user.getRole() != null ? user.getRole().name() : null);
        body.put("canSell",     user.canSell());
        body.put("profileImageUrl", user.getProfileImageUrl());
        body.put("memberSince",     user.getMemberSince() != null ? user.getMemberSince().toString() : null);
        body.put("twoFactorEnabled", user.isTwoFactorEnabled());

        // Decrypt phone / address for the account owner
        String phone = null, address = null;
        try {
            if (user.getPhoneEncrypted() != null)
                phone = SecurityUtil.decrypt(user.getPhoneEncrypted());
            if (user.getAddressEncrypted() != null)
                address = SecurityUtil.decrypt(user.getAddressEncrypted());
        } catch (Exception ignored) {}

        body.put("phone",   phone);
        body.put("address", address);

        try {
            body.put("rating",       actDAO.getRatingSummary(userId));
            body.put("transactions", actDAO.listTransactions(userId, TxFilter.ALL));
        } catch (Exception e) {
            body.put("rating",       null);
            body.put("transactions", java.util.Collections.emptyList());
        }
        ok(resp, body);
    }

    private void handleTransactions(HttpServletRequest req, HttpServletResponse resp, int userId)
            throws IOException {
        TxFilter filter = TxFilter.fromParam(param(req, "filter"));
        ok(resp, actDAO.listTransactions(userId, filter));
    }

    private void handleUpdate(HttpServletRequest req, HttpServletResponse resp, int userId)
            throws IOException {
        User current = userDAO.getUserById(userId);
        if (current == null) { error(resp, 404, "User not found."); return; }

        String username        = param(req, "username");
        String phone           = param(req, "phone");
        String address         = param(req, "address");
        String profileImageUrl = param(req, "profileImageUrl");

        if (username == null) username = current.getUsername();
        // The email address is the sign-in identity and is not editable from the
        // profile form, so any submitted value is ignored rather than trusted.
        String email = current.getEmail();
        // updateProfile writes profile_image_url unconditionally, so an omitted
        // parameter has to fall back to the stored value — otherwise saving the
        // profile form wipes the photo that /upload-photo just stored.
        if (profileImageUrl == null) profileImageUrl = current.getProfileImageUrl();

        String phoneEncrypted   = null;
        String addressEncrypted = null;
        try {
            if (phone   != null && !phone.isBlank())   phoneEncrypted   = SecurityUtil.encrypt(phone.trim());
            if (address != null && !address.isBlank()) addressEncrypted = SecurityUtil.encrypt(address.trim());
        } catch (Exception e) {
            serverError(resp, "Encryption error."); return;
        }

        boolean ok = userDAO.updateProfile(userId, username, email.toLowerCase(),
                phoneEncrypted, addressEncrypted, profileImageUrl);
        if (!ok) { serverError(resp, "Update failed."); return; }

        // Refresh session
        AuthSession session = authSession(req);
        if (session != null) {
            session.setAttribute("sessionEmail",   email.toLowerCase());
            session.setAttribute("maskedEmail",    SecurityUtil.maskEmail(email.toLowerCase()));
            session.setAttribute("maskedUsername", SecurityUtil.maskUsername(username));
        }
        okMsg(resp, "Profile updated successfully.");
    }

    private void handleDelete(HttpServletRequest req, HttpServletResponse resp, int userId)
            throws IOException {
        String confirm = param(req, "confirm");
        if (!"DELETE".equals(confirm)) {
            badRequest(resp, "Type DELETE to confirm account deletion."); return;
        }
        boolean ok = userDAO.deleteAccount(userId);
        if (!ok) { serverError(resp, "Could not delete account."); return; }
        AuthSession session = authSession(req);
        if (session != null) session.invalidate();
        okMsg(resp, "Account deleted.");
    }

    private String sub(HttpServletRequest req) {
        String p = req.getPathInfo();
        if (p == null || p.equals("/")) return "";
        return p.replaceFirst("^/", "").split("/")[0];
    }
}
