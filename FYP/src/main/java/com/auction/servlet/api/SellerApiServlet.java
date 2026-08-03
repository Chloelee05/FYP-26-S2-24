package com.auction.servlet.api;

import com.auction.dao.AuctionDAO;
import com.auction.dao.AuctionTagsDAO;
import com.auction.dao.FeaturedListingDAO;
import com.auction.dao.PlatformRevenueDAO;
import com.auction.dao.ReviewDAO;
import com.auction.dao.ReviewDAO.SellerRatingResult;
import com.auction.dao.SellerAnalyticsDAO;
import com.auction.dao.SellerAuctionDAO;
import com.auction.dao.SellerProfileDAO;
import com.auction.dao.UserDAO;
import com.auction.model.User;
import com.auction.util.MailConfig;
import com.auction.util.OtpMailer;
import com.auction.model.Auction;
import com.auction.model.AuctionType;
import com.auction.model.ItemCondition;
import com.auction.model.ListingKind;
import com.auction.model.SellerPublicProfile;
import com.auction.notification.NotificationService;
import com.auction.util.AuthSession;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * GET  /api/seller/{id}           — public seller profile (no auth required)
 * GET  /api/seller/auctions        — seller's own listings (SELLER role)
 * GET  /api/seller/{id}/edit       — fetch auction for edit form (SELLER role)
 * POST /api/seller/create          — create auction (SELLER role)
 * POST /api/seller/cancel          — cancel auction (SELLER role)
 * POST /api/seller/edit            — edit auction text+images (SELLER role)
 */
@WebServlet("/api/seller/*")
public class SellerApiServlet extends ApiBase {

    private static final Logger LOG = Logger.getLogger(SellerApiServlet.class.getName());

    /**
     * Upper bound on a listing's unit count. Not a business rule so much as a guard against a
     * mistyped or pasted figure becoming a permanent record on a marketplace where nothing
     * ships more than one unit per auction anyway.
     */
    private static final int MAX_QUANTITY = 1000;

    private final SellerProfileDAO profileDAO  = new SellerProfileDAO();
    private       SellerAuctionDAO auctionDAO  = new SellerAuctionDAO();
    private       AuctionDAO       mainDAO     = new AuctionDAO();
    private final AuctionTagsDAO   tagsDAO     = new AuctionTagsDAO();
    private final ReviewDAO        reviewDAO   = new ReviewDAO();
    private final SellerAnalyticsDAO analyticsDAO = new SellerAnalyticsDAO();
    private final UserDAO          userDAO     = new UserDAO();

    /** Test hooks */
    public void setSellerAuctionDAO(SellerAuctionDAO dao) { this.auctionDAO = dao; }
    public void setAuctionDAO(AuctionDAO dao)             { this.mainDAO    = dao; }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] parts = parts(req);

        if (parts.length == 0) {
            error(resp, 404, "Not found"); return;
        }

        if ("auctions".equals(parts[0])) {
            handleListAuctions(req, resp);
        } else if ("analytics".equals(parts[0])) {
            handleAnalytics(req, resp);
        } else {
            // /api/seller/{id} or /api/seller/{id}/edit
            long sellerId;
            try { sellerId = Long.parseLong(parts[0]); }
            catch (NumberFormatException e) { error(resp, 404, "Not found."); return; }

            if (parts.length >= 2 && "edit".equals(parts[1])) {
                handleGetForEdit(req, resp, sellerId);
            } else {
                handlePublicProfile(resp, sellerId);
            }
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] parts = parts(req);
        if (parts.length == 0) { error(resp, 404, "Not found."); return; }

        switch (parts[0]) {
            case "create":     handleCreate(req, resp);     break;
            case "cancel":     handleCancel(req, resp);     break;
            case "edit":       handleEdit(req, resp);       break;
            case "relist":     handleRelist(req, resp);     break;
            case "rate-buyer": handleRateBuyer(req, resp);  break;
            case "analytics":  handleAnalyticsEmail(req, resp); break;
            case "reduce-quantity": handleReduceQuantity(req, resp); break;
            case "feature":    handleFeature(req, resp);    break;
            default: error(resp, 404, "Not found."); break;
        }
    }

    // ── GET: seller analytics ────────────────────────────────────────────────

    private void handleAnalytics(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        AuthSession session = authSession(req);
        if (!isSeller(session)) { forbidden(resp); return; }
        int sellerId = ((Number) session.getAttribute("userId")).intValue();
        try {
            ok(resp, analyticsDAO.generate(sellerId));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Seller analytics failed for " + sellerId, e);
            serverError(resp, "Could not generate analytics.");
        }
    }

    // ── POST: email the seller their analytics report ────────────────────────

    private void handleAnalyticsEmail(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        AuthSession session = authSession(req);
        if (!isSeller(session)) { forbidden(resp); return; }
        int sellerId = ((Number) session.getAttribute("userId")).intValue();

        try {
            Map<String, Object> analytics = analyticsDAO.generate(sellerId);
            User seller = userDAO.getUserById(sellerId);
            if (seller == null || seller.getEmail() == null) {
                serverError(resp, "Seller email not found."); return;
            }
            if (!MailConfig.isSmtpConfigured()) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("message", "Email is not configured on this server; showing the report inline instead.");
                body.put("emailConfigured", false);
                body.put("report", SellerAnalyticsDAO.toEmailBody(seller.getUsername(), analytics));
                ok(resp, body);
                return;
            }
            OtpMailer.sendNotification(seller.getEmail(),
                    "Your AuctionHub seller analytics report",
                    SellerAnalyticsDAO.toEmailBody(seller.getUsername(), analytics));
            okMsg(resp, "Analytics report emailed to " + seller.getEmail() + ".");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Seller analytics email failed for " + sellerId, e);
            serverError(resp, "Could not send analytics report.");
        }
    }

    // ── POST: seller self-features their own listing (flat fee) ──────────────

    /**
     * POST /api/seller/feature  auctionId [days]
     * Lets a seller feature their own active listing for a flat fee
     * ({@link PlatformRevenueDAO#FEATURED_LISTING_FEE}), recorded as platform revenue.
     */
    private void handleFeature(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        AuthSession session = authSession(req);
        if (!isSeller(session)) { forbidden(resp); return; }
        int sellerId = ((Number) session.getAttribute("userId")).intValue();

        String idStr = param(req, "auctionId");
        if (idStr == null) { badRequest(resp, "auctionId is required."); return; }
        long auctionId;
        try { auctionId = Long.parseLong(idStr); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid auctionId."); return; }

        int days = 7;
        String daysStr = param(req, "days");
        if (daysStr != null) {
            try { days = Math.max(1, Math.min(30, Integer.parseInt(daysStr))); }
            catch (NumberFormatException ignored) { }
        }

        FeaturedListingDAO featuredDAO = new FeaturedListingDAO();
        if (featuredDAO.sellerIdForAuction(auctionId) != sellerId) {
            forbidden(resp); return;
        }

        try {
            if (!featuredDAO.featureAuction(auctionId, days)) {
                badRequest(resp, "Could not feature this listing."); return;
            }
            new PlatformRevenueDAO().recordFeaturedListing(auctionId, sellerId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", "Listing featured for " + days + " day(s). A $"
                    + PlatformRevenueDAO.FEATURED_LISTING_FEE.toPlainString()
                    + " featuring fee has been charged (simulated billing).");
            body.put("featured", true);
            body.put("days", days);
            body.put("fee", PlatformRevenueDAO.FEATURED_LISTING_FEE);
            ok(resp, body);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Self-feature failed for auction " + auctionId, e);
            serverError(resp, "Could not feature this listing.");
        }
    }

    // ── GET: public seller profile ───────────────────────────────────────────

    private void handlePublicProfile(HttpServletResponse resp, long sellerId) throws IOException {
        SellerPublicProfile profile = profileDAO.getPublicProfile(sellerId);
        if (profile == null) { error(resp, 404, "Seller not found."); return; }

        SellerProfileDAO.AvgRating rating = profileDAO.getAvgRating(sellerId);
        int totalReviews = profileDAO.countReviews(sellerId);
        List<?> reviews  = profileDAO.getReviews(sellerId, 1, 10);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id",              profile.getSellerId());
        body.put("username",        profile.getUsername());
        body.put("email",           profile.getMaskedEmail());
        body.put("memberSince",     profile.getMemberSince() != null ? profile.getMemberSince().toString() : null);
        body.put("profileImageUrl", profile.getProfileImageUrl());
        body.put("activeListings",  profile.getActiveListingCount());
        body.put("completedSales",  profileDAO.countCompletedTransactions(sellerId));
        body.put("avgRating",       rating.getAverage());
        body.put("reviewCount",     rating.getCount());
        body.put("totalReviews",    totalReviews);
        body.put("reviews",         reviews);
        // Live listings so buyers can browse this seller's items from the profile.
        body.put("listings",        profileDAO.getActiveListings(sellerId, 48));
        ok(resp, body);
    }

    // ── GET: seller's own auction list ───────────────────────────────────────

    private void handleListAuctions(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        AuthSession session = authSession(req);
        if (!isSeller(session)) { forbidden(resp); return; }
        int sellerId = ((Number) session.getAttribute("userId")).intValue();

        Integer statusId = null;
        String statusParam = param(req, "status");
        if (statusParam != null && !statusParam.isBlank()) {
            try { statusId = Integer.parseInt(statusParam); }
            catch (NumberFormatException ignored) { }
        }
        int page = parseInt(param(req, "page"), 1);
        int size = Math.min(parseInt(param(req, "size"), 10), 50);

        // Legacy single-status filter, kept for any caller still sending ?status=N.
        if (statusId != null) {
            try {
                int total = auctionDAO.countSellerAuctions(sellerId, statusId);
                List<?> rows = auctionDAO.listSellerAuctions(sellerId, statusId, page, size);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("auctions",   rows);
                body.put("total",      total);
                body.put("page",       page);
                body.put("size",       size);
                body.put("totalPages", totalPages(total, size));
                ok(resp, body);
            } catch (Exception e) {
                serverError(resp, "Could not load auctions.");
            }
            return;
        }

        // Bucket + search + sort are resolved in SQL so that pagination is honest: a page of
        // ten rows out of twelve must not be searched or sorted in the browser, or the two
        // listings the seller cannot see would be missing from the answer without saying so.
        SellerAuctionDAO.ListingBucket bucket = SellerAuctionDAO.ListingBucket.parse(param(req, "bucket"));
        String query = param(req, "q");
        String sort  = param(req, "sort");

        try {
            Map<String, Integer> counts = auctionDAO.countByBucket(sellerId, query);
            int total = auctionDAO.countSellerAuctions(sellerId, bucket, query);
            int pages = totalPages(total, size);
            // A seller who deletes their way off page 3 should see page 3's new contents,
            // not an empty table with a pager insisting there are only two pages.
            if (page > pages) page = pages;
            List<?> rows = auctionDAO.listSellerAuctions(sellerId, bucket, query, sort, page, size);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("auctions",   rows);
            body.put("total",      total);
            body.put("page",       page);
            body.put("size",       size);
            body.put("totalPages", pages);
            body.put("bucket",     bucket.name());
            body.put("counts",     counts);
            ok(resp, body);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Seller listing page failed for " + sellerId, e);
            serverError(resp, "Could not load auctions.");
        }
    }

    /** At least one page, so an empty list still renders as "page 1 of 1" rather than 1 of 0. */
    private static int totalPages(int total, int size) {
        return Math.max(1, (int) Math.ceil((double) total / size));
    }

    // ── GET: auction edit form data ──────────────────────────────────────────

    private void handleGetForEdit(HttpServletRequest req, HttpServletResponse resp, long auctionId) throws IOException {
        if (!requireAuth(req, resp)) return;
        AuthSession session = authSession(req);
        if (!isSeller(session)) { forbidden(resp); return; }
        int sellerId = ((Number) session.getAttribute("userId")).intValue();

        try {
            SellerAuctionDAO.AuctionEditData data = auctionDAO.getAuctionForEdit(auctionId, sellerId);
            if (data == null) { error(resp, 404, "Auction not found or access denied."); return; }
            int bidCount = auctionDAO.countBids(auctionId);

            Map<String, Object> body = new LinkedHashMap<>();
            String conditionName;
            try { conditionName = ItemCondition.getItemCondition(data.itemConditionId).getDisplayName(); }
            catch (IllegalArgumentException e) { conditionName = ""; }

            body.put("auctionId",    data.auctionId);
            body.put("statusId",     data.statusId);
            body.put("title",        data.title);
            body.put("description",  data.description);
            body.put("category",     data.category);
            body.put("listingKind",  data.listingKind);
            body.put("conditionId",  data.itemConditionId);
            body.put("condition",    conditionName);
            body.put("maxPrice",     data.maxPrice);
            body.put("quantity",     data.quantity);
            // Seller-private: this endpoint is already owner-scoped (getAuctionForEdit
            // filters on seller_id), so the cost price never reaches anyone else.
            body.put("costPrice",    data.costPrice);
            body.put("startDate",    data.startDate != null ? data.startDate.toString() : null);
            body.put("endDate",      data.endDate   != null ? data.endDate.toString()   : null);
            body.put("bidCount",     bidCount);
            body.put("images",     data.images.stream().map(img -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("imageId",  img.imageId);
                m.put("imageUrl", img.imageUrl);
                return m;
            }).collect(Collectors.toList()));
            ok(resp, body);
        } catch (Exception e) {
            serverError(resp, "Could not load auction.");
        }
    }

    // ── POST: create auction ─────────────────────────────────────────────────

    private void handleCreate(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        AuthSession session = authSession(req);
        if (!isSeller(session)) { forbidden(resp); return; }
        int sellerId = ((Number) session.getAttribute("userId")).intValue();

        String auctionName    = param(req, "auctionName");
        String auctionDetails = param(req, "auctionDetails");
        String endDateStr     = param(req, "endDate");
        String startDateStr   = param(req, "startDate");
        String startPriceStr  = param(req, "startPrice");
        String maxPriceStr    = param(req, "maxPrice");
        String quantityStr    = param(req, "quantity");
        String costPriceStr   = param(req, "costPrice");
        String dutchFloorStr  = param(req, "dutchFloorPrice");
        String buyItNowStr    = param(req, "buyItNowPrice");
        String auctionTypeStr = param(req, "auctionType");
        String itemCondStr    = param(req, "itemCondition");
        String listingKindStr = param(req, "listingKind");
        String[] tagIdsArr    = req.getParameterValues("tags");
        String[] imageUrlsArr = req.getParameterValues("imageUrls");

        if (auctionName == null || auctionName.isBlank()
                || auctionDetails == null || auctionDetails.isBlank()
                || endDateStr == null || endDateStr.isBlank()
                || itemCondStr == null || itemCondStr.isBlank()) {
            badRequest(resp, "auctionName, auctionDetails, endDate, and itemCondition are required."); return;
        }

        // A missing start price used to fall through as 0, which let an item be won for any
        // amount at all. There is no such thing as a listing with no floor, so it is required.
        if (startPriceStr == null || startPriceStr.isBlank()) {
            badRequest(resp, "Starting price is required and must be greater than 0."); return;
        }
        float startPrice;
        try {
            startPrice = Float.parseFloat(startPriceStr);
            if (startPrice <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            badRequest(resp, "Invalid start price."); return;
        }

        BigDecimal maxPrice = null;
        if (maxPriceStr != null && !maxPriceStr.isBlank()) {
            try {
                maxPrice = new BigDecimal(maxPriceStr);
                if (maxPrice.compareTo(BigDecimal.ZERO) <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                badRequest(resp, "Invalid max price."); return;
            }
        }

        Instant startDate, endDate;
        try {
            startDate = startDateStr != null && !startDateStr.isBlank()
                    ? OffsetDateTime.parse(startDateStr).toInstant() : Instant.now();
            endDate   = OffsetDateTime.parse(endDateStr).toInstant();
        } catch (DateTimeParseException e) {
            badRequest(resp, "Invalid date format. Use ISO-8601 (e.g. 2025-01-31T00:00:00+08:00)."); return;
        }
        if (endDate.isBefore(startDate)) {
            badRequest(resp, "End date must be after start date."); return;
        }

        AuctionType auctionType = AuctionType.PRICE_UP;
        if (auctionTypeStr != null && !auctionTypeStr.isBlank()) {
            try { auctionType = AuctionType.getAuctionType(Integer.parseInt(auctionTypeStr)); }
            catch (IllegalArgumentException e) { badRequest(resp, "Invalid auction type."); return; }
        }

        // Product or service. Omitting it means PRODUCT — the column's default, what every
        // listing created before sellers could choose already is, and what the legacy JSP
        // create form still sends. A kind that was supplied and is not one of the two is
        // rejected rather than quietly turned into a product, which would store something
        // other than what the seller asked for.
        String listingKind = ListingKind.DEFAULT.name();
        if (listingKindStr != null && !listingKindStr.isBlank()) {
            ListingKind kind = ListingKind.parse(listingKindStr);
            if (kind == null) { badRequest(resp, "listingKind must be PRODUCT or SERVICE."); return; }
            listingKind = kind.name();
        }

        int quantity = 1;
        if (quantityStr != null && !quantityStr.isBlank()) {
            try {
                quantity = Integer.parseInt(quantityStr);
                if (quantity < 1 || quantity > MAX_QUANTITY) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                badRequest(resp, "Quantity must be a whole number between 1 and " + MAX_QUANTITY + "."); return;
            }
        }

        BigDecimal costPrice = null;
        if (costPriceStr != null && !costPriceStr.isBlank()) {
            try {
                costPrice = new BigDecimal(costPriceStr);
                if (costPrice.compareTo(BigDecimal.ZERO) < 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                badRequest(resp, "Invalid cost price."); return;
            }
        }

        // Dutch auctions need a floor price strictly below the (high) starting price.
        BigDecimal dutchFloor = null;
        if (auctionType == AuctionType.DUTCH_AUCTION) {
            if (dutchFloorStr == null || dutchFloorStr.isBlank()) {
                badRequest(resp, "Dutch auctions require a floor price."); return;
            }
            try {
                dutchFloor = new BigDecimal(dutchFloorStr);
                if (dutchFloor.compareTo(BigDecimal.ZERO) < 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                badRequest(resp, "Invalid Dutch floor price."); return;
            }
            if (startPrice <= 0) {
                badRequest(resp, "Dutch auctions require a starting (high) price."); return;
            }
            if (dutchFloor.compareTo(BigDecimal.valueOf(startPrice)) >= 0) {
                badRequest(resp, "Dutch floor price must be below the starting price."); return;
            }
        }

        // Buy It Now is optional and only valid for standard ascending auctions.
        BigDecimal buyItNow = null;
        if (buyItNowStr != null && !buyItNowStr.isBlank()) {
            if (auctionType != AuctionType.PRICE_UP) {
                badRequest(resp, "Buy It Now is only available for standard ascending auctions."); return;
            }
            try {
                buyItNow = new BigDecimal(buyItNowStr);
                if (buyItNow.compareTo(BigDecimal.ZERO) <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                badRequest(resp, "Invalid Buy It Now price."); return;
            }
            // Buy It Now below the starting bid is an instant-loss trap: the first buyer to
            // notice buys the item for less than anyone is allowed to bid. The legacy JSP path
            // (CreateAuctionServlet) has always enforced this; this path had not, and a
            // startPrice=100 / buyItNow=1 listing was reproduced live.
            if (buyItNow.compareTo(BigDecimal.valueOf(startPrice)) <= 0) {
                badRequest(resp, "Buy It Now price must be above the starting price."); return;
            }
        }

        ItemCondition itemCondition;
        try {
            itemCondition = ItemCondition.getItemCondition(Integer.parseInt(itemCondStr));
        } catch (IllegalArgumentException e) {
            badRequest(resp, "Invalid item condition."); return;
        }

        List<Long> tagIds = new ArrayList<>();
        if (tagIdsArr != null) {
            for (String t : tagIdsArr) {
                try { tagIds.add(Long.parseLong(t)); }
                catch (NumberFormatException e) { badRequest(resp, "Invalid tag ID."); return; }
            }
        }

        List<String> imageUrls = imageUrlsArr != null
                ? Arrays.stream(imageUrlsArr).filter(u -> u != null && !u.isBlank()).collect(Collectors.toList())
                : Collections.emptyList();

        // The form marks category required, but nothing enforced it server-side and an omitted
        // one was stored as "". An empty category still shows up in SELECT DISTINCT category on
        // the browse filters, where it matches nothing and can never be selected.
        String category = param(req, "category");
        if (category == null) {
            badRequest(resp, "Category is required."); return;
        }

        Auction auction = new Auction(sellerId, auctionName, auctionDetails,
                startDate, endDate, startPrice, auctionType, itemCondition, tagIds);
        auction.setMaxPrice(maxPrice);
        auction.setQuantity(quantity);
        auction.setCostPrice(costPrice);
        auction.setDutchFloorPrice(dutchFloor);
        auction.setBuyItNowPrice(buyItNow);
        auction.setCategory(category);
        auction.setListingKind(listingKind);

        try {
            long auctionId = mainDAO.createAuction(auction, imageUrls);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("auctionId", auctionId);
            body.put("message", "Auction created successfully.");
            ok(resp, body);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Create auction failed for seller " + sellerId, e);
            serverError(resp, "Could not create auction. Check server logs or run DB migrations.");
        }
    }

    // ── POST: cancel auction ─────────────────────────────────────────────────

    private void handleCancel(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        AuthSession session = authSession(req);
        if (!isSeller(session)) { forbidden(resp); return; }
        int sellerId = ((Number) session.getAttribute("userId")).intValue();

        String auctionIdStr = param(req, "auctionId");
        if (auctionIdStr == null) { badRequest(resp, "auctionId is required."); return; }
        long auctionId;
        try { auctionId = Long.parseLong(auctionIdStr); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid auction ID."); return; }

        // A reason is now required. Requirement Seller (d) is phrased "cancel entire auction
        // (due to lack of bids)", and until the UI started sending one, cancel_reason was NULL
        // on every cancelled auction in the database — so there was no evidence anywhere that
        // the requirement's own example case was ever supported.
        String reason = validCancelReason(resp, param(req, "reason"));
        if (reason == null) return;

        try {
            boolean cancelled = auctionDAO.cancelAuction(auctionId, sellerId, reason);
            if (!cancelled) {
                error(resp, 400, "Could not cancel auction. It may not exist, you may not own it, or it is already finished/cancelled.");
            } else {
                // Anyone who bid has money committed to a listing that no longer exists.
                NotificationService.notifyAuctionCancelled(auctionId, reason);
                okMsg(resp, "Auction cancelled successfully. Bidders have been notified.");
            }
        } catch (Exception e) {
            serverError(resp, "Could not cancel auction.");
        }
    }

    private static final int CANCEL_REASON_MAX = 300;

    /**
     * Validates and normalises a cancellation reason, writing the 400 itself and returning
     * null when it is unusable. Shared by cancellation and last-unit removal, which both end
     * a listing and therefore both owe the bidders an explanation.
     */
    private String validCancelReason(HttpServletResponse resp, String raw) throws IOException {
        if (raw == null) {
            badRequest(resp, "A reason is required so bidders can be told why.");
            return null;
        }
        String reason = raw.trim();
        if (reason.isEmpty()) {
            badRequest(resp, "A reason is required so bidders can be told why.");
            return null;
        }
        if (reason.length() > CANCEL_REASON_MAX) {
            badRequest(resp, "Reason must be " + CANCEL_REASON_MAX + " characters or fewer.");
            return null;
        }
        return com.auction.util.SecurityUtil.sanitize(reason);
    }

    // ── POST: remove a unit from a listing (including the last one) ───────────

    /**
     * POST /api/seller/reduce-quantity  auctionId [reason]
     *
     * <p>Removing a unit while others remain is a plain decrement. Removing the last unit ends
     * the listing, so it needs a reason and notifies the bidders exactly as a cancellation
     * does — the seller is doing the same thing to those bidders either way. The endpoint name
     * is unchanged so nothing that already calls it breaks.</p>
     */
    private void handleReduceQuantity(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        AuthSession session = authSession(req);
        if (!isSeller(session)) { forbidden(resp); return; }
        int sellerId = ((Number) session.getAttribute("userId")).intValue();

        String auctionIdStr = param(req, "auctionId");
        if (auctionIdStr == null) { badRequest(resp, "auctionId is required."); return; }
        long auctionId;
        try { auctionId = Long.parseLong(auctionIdStr); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid auction ID."); return; }

        // Only needed if this turns out to be the last unit, but it has to be validated before
        // the write: discovering afterwards that the listing just ended without a reason would
        // leave exactly the NULL cancel_reason this change exists to eliminate.
        String rawReason = param(req, "reason");
        String reason = rawReason == null
                ? null
                : validCancelReason(resp, rawReason);
        if (rawReason != null && reason == null) return;

        try {
            SellerAuctionDAO.ReduceQtyResult result = auctionDAO.removeUnit(auctionId, sellerId,
                    reason != null ? reason : "Last item removed from the listing by the seller");
            switch (result) {
                case SUCCESS:
                    okMsg(resp, "One unit removed from this listing.");
                    break;
                case LISTING_ENDED:
                    NotificationService.notifyAuctionCancelled(auctionId,
                            reason != null ? reason : "The seller removed the last item from this listing");
                    okMsg(resp, "That was the last item, so the listing has been cancelled. Bidders have been notified.");
                    break;
                case NOT_FOUND:
                    error(resp, 404, "Auction not found.");
                    break;
                case NOT_OWNER:
                    forbidden(resp);
                    break;
                case ALREADY_EMPTY:
                    error(resp, 400, "This listing has no items left to remove.");
                    break;
                case NOT_ACTIVE:
                    error(resp, 400, "Only active or scheduled auctions can have units removed.");
                    break;
                default:
                    serverError(resp, "Could not update quantity.");
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Remove unit failed for auction " + auctionId, e);
            serverError(resp, "Could not update quantity.");
        }
    }

    // ── POST: relist auction ─────────────────────────────────────────────────

    private void handleRelist(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        AuthSession session = authSession(req);
        if (!isSeller(session)) { forbidden(resp); return; }
        int sellerId = ((Number) session.getAttribute("userId")).intValue();

        String auctionIdStr = param(req, "auctionId");
        if (auctionIdStr == null) { badRequest(resp, "auctionId is required."); return; }
        long auctionId;
        try { auctionId = Long.parseLong(auctionIdStr); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid auction ID."); return; }

        try {
            boolean relisted = auctionDAO.relistAuction(auctionId, sellerId);
            if (!relisted) {
                error(resp, 400, "Could not relist. Auction must be yours and in CANCELLED or FINISHED status.");
            } else {
                okMsg(resp, "Auction relisted as pending. You can now edit it to set new dates.");
            }
        } catch (Exception e) {
            serverError(resp, "Could not relist auction.");
        }
    }

    // ── POST: edit auction ───────────────────────────────────────────────────

    private void handleEdit(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        AuthSession session = authSession(req);
        if (!isSeller(session)) { forbidden(resp); return; }
        int sellerId = ((Number) session.getAttribute("userId")).intValue();

        String auctionIdStr = param(req, "auctionId");
        if (auctionIdStr == null) { badRequest(resp, "auctionId is required."); return; }
        long auctionId;
        try { auctionId = Long.parseLong(auctionIdStr); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid auction ID."); return; }

        String title          = param(req, "title");
        String description    = param(req, "description");
        String category       = param(req, "category");
        String conditionStr   = param(req, "itemCondition");
        String endDateStr     = param(req, "endDate");
        String quantityStr    = param(req, "quantity");
        String costPriceStr   = param(req, "costPrice");
        String listingKindStr = param(req, "listingKind");
        if (title == null || title.isBlank()) { badRequest(resp, "title is required."); return; }
        // param() turns a blank string into null, so a seller who cleared the Description box
        // used to reach a NOT NULL column and get a 500 with a red "Could not update auction."
        // banner. It is a validation failure, and it says which field.
        if (description == null) { badRequest(resp, "Description is required."); return; }

        Integer quantity = null;
        if (quantityStr != null) {
            try {
                quantity = Integer.parseInt(quantityStr);
                // The floor is 1 rather than 0 because emptying a listing ends it, which is
                // reduce-quantity's job — it confirms the consequence first and tells the
                // bidders afterwards. A silent 0 typed into an edit form would do neither.
                if (quantity < 1 || quantity > MAX_QUANTITY) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                badRequest(resp, "Quantity must be a whole number between 1 and " + MAX_QUANTITY
                        + ". To remove the last item, use Remove item on My listings."); return;
            }
        }

        BigDecimal costPrice = null;
        if (costPriceStr != null) {
            try {
                costPrice = new BigDecimal(costPriceStr);
                if (costPrice.compareTo(BigDecimal.ZERO) < 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                badRequest(resp, "Invalid cost price."); return;
            }
        }

        Integer itemConditionId = null;
        if (conditionStr != null && !conditionStr.isBlank()) {
            try {
                int cid = Integer.parseInt(conditionStr);
                ItemCondition.getItemCondition(cid); // validate
                itemConditionId = cid;
            } catch (Exception e) { badRequest(resp, "Invalid item condition."); return; }
        }

        // Unlike create, an omitted kind here means "leave it as it is" rather than PRODUCT:
        // the legacy JSP edit form has no such field, and an edit through it must not silently
        // reclassify a service back into a product.
        String listingKind = null;
        if (listingKindStr != null && !listingKindStr.isBlank()) {
            ListingKind kind = ListingKind.parse(listingKindStr);
            if (kind == null) { badRequest(resp, "listingKind must be PRODUCT or SERVICE."); return; }
            listingKind = kind.name();
        }

        Instant newEndDate = null;
        if (endDateStr != null && !endDateStr.isBlank()) {
            try { newEndDate = OffsetDateTime.parse(endDateStr).toInstant(); }
            catch (DateTimeParseException e) { badRequest(resp, "Invalid end date format."); return; }
            if (newEndDate.isBefore(Instant.now())) {
                badRequest(resp, "End date must be in the future."); return;
            }
        }

        String[] deleteIdsArr = req.getParameterValues("deleteImageIds");
        List<Long> deleteIds  = new ArrayList<>();
        if (deleteIdsArr != null) {
            for (String d : deleteIdsArr) {
                try { deleteIds.add(Long.parseLong(d)); }
                catch (NumberFormatException e) { badRequest(resp, "Invalid image ID."); return; }
            }
        }

        String[] newUrlsArr = req.getParameterValues("newImageUrls");
        List<String> newUrls = newUrlsArr != null
                ? Arrays.stream(newUrlsArr).filter(u -> u != null && !u.isBlank()).collect(Collectors.toList())
                : Collections.emptyList();

        try {
            auctionDAO.editAuction(auctionId, sellerId, title, description,
                    category, listingKind, itemConditionId, quantity, costPrice,
                    newEndDate, deleteIds, newUrls);
            okMsg(resp, "Auction updated successfully.");
        } catch (IllegalStateException e) {
            error(resp, 400, e.getMessage());
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Edit auction failed for auction " + auctionId, e);
            serverError(resp, "Could not update auction.");
        }
    }

    // ── POST: rate buyer ─────────────────────────────────────────────────────

    private void handleRateBuyer(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        AuthSession session = authSession(req);
        if (!isSeller(session)) { forbidden(resp); return; }
        int sellerId = ((Number) session.getAttribute("userId")).intValue();

        String auctionIdStr = param(req, "auctionId");
        String scoreStr     = param(req, "score");
        if (auctionIdStr == null) { badRequest(resp, "auctionId is required."); return; }
        if (scoreStr     == null) { badRequest(resp, "score is required."); return; }

        long auctionId;
        try { auctionId = Long.parseLong(auctionIdStr); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid auction ID."); return; }

        int score;
        try {
            score = Integer.parseInt(scoreStr);
            if (score < 1 || score > 5) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            badRequest(resp, "Score must be between 1 and 5."); return;
        }

        String comment = param(req, "comment");
        if (comment != null) comment = com.auction.util.SecurityUtil.sanitize(comment.trim());

        try {
            SellerRatingResult result = reviewDAO.insertSellerRating(auctionId, sellerId, score, comment);
            switch (result) {
                case SUCCESS:              okMsg(resp, "Buyer rated successfully."); break;
                case AUCTION_NOT_FOUND:    error(resp, 404, "Auction not found."); break;
                case AUCTION_NOT_FINISHED: error(resp, 400, "Auction is not finished yet."); break;
                case NOT_AUCTION_OWNER:    forbidden(resp); break;
                case NO_WINNER:            error(resp, 400, "This auction has no winner to rate."); break;
                case ALREADY_RATED:        error(resp, 400, "You have already rated the buyer for this auction."); break;
                case ORDER_NOT_COMPLETED:  error(resp, 400, "You can rate the buyer after the order is marked complete."); break;
                default:                   serverError(resp, "Could not submit rating."); break;
            }
        } catch (Exception e) {
            serverError(resp, "Could not submit rating.");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String[] parts(HttpServletRequest req) {
        String p = req.getPathInfo();
        if (p == null || p.equals("/")) return new String[0];
        return p.replaceFirst("^/", "").split("/");
    }

    private int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Math.max(1, Integer.parseInt(s)); } catch (NumberFormatException e) { return def; }
    }
}
