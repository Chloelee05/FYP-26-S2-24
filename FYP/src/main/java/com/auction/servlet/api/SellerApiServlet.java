package com.auction.servlet.api;

import com.auction.dao.AuctionDAO;
import com.auction.dao.AuctionTagsDAO;
import com.auction.dao.ReviewDAO;
import com.auction.dao.ReviewDAO.SellerRatingResult;
import com.auction.dao.SellerAuctionDAO;
import com.auction.dao.SellerProfileDAO;
import com.auction.model.Auction;
import com.auction.model.AuctionType;
import com.auction.model.ItemCondition;
import com.auction.model.SellerPublicProfile;
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

    private final SellerProfileDAO profileDAO  = new SellerProfileDAO();
    private final SellerAuctionDAO auctionDAO  = new SellerAuctionDAO();
    private final AuctionDAO       mainDAO     = new AuctionDAO();
    private final AuctionTagsDAO   tagsDAO     = new AuctionTagsDAO();
    private final ReviewDAO        reviewDAO   = new ReviewDAO();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] parts = parts(req);

        if (parts.length == 0) {
            error(resp, 404, "Not found"); return;
        }

        if ("auctions".equals(parts[0])) {
            handleListAuctions(req, resp);
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
            default: error(resp, 404, "Not found."); break;
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
        body.put("avgRating",       rating.getAverage());
        body.put("reviewCount",     rating.getCount());
        body.put("totalReviews",    totalReviews);
        body.put("reviews",         reviews);
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

        try {
            int total = auctionDAO.countSellerAuctions(sellerId, statusId);
            List<?> rows = auctionDAO.listSellerAuctions(sellerId, statusId, page, size);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("auctions",   rows);
            body.put("total",      total);
            body.put("page",       page);
            body.put("totalPages", (int) Math.ceil((double) total / size));
            ok(resp, body);
        } catch (Exception e) {
            serverError(resp, "Could not load auctions.");
        }
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
            body.put("conditionId",  data.itemConditionId);
            body.put("condition",    conditionName);
            body.put("maxPrice",     data.maxPrice);
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
        String auctionTypeStr = param(req, "auctionType");
        String itemCondStr    = param(req, "itemCondition");
        String[] tagIdsArr    = req.getParameterValues("tags");
        String[] imageUrlsArr = req.getParameterValues("imageUrls");

        if (auctionName == null || auctionName.isBlank()
                || auctionDetails == null || auctionDetails.isBlank()
                || endDateStr == null || endDateStr.isBlank()
                || itemCondStr == null || itemCondStr.isBlank()) {
            badRequest(resp, "auctionName, auctionDetails, endDate, and itemCondition are required."); return;
        }

        float startPrice = 0;
        if (startPriceStr != null && !startPriceStr.isBlank()) {
            try {
                startPrice = Float.parseFloat(startPriceStr);
                if (startPrice <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                badRequest(resp, "Invalid start price."); return;
            }
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

        String category = param(req, "category");

        Auction auction = new Auction(sellerId, auctionName, auctionDetails,
                startDate, endDate, startPrice, auctionType, itemCondition, tagIds);
        auction.setMaxPrice(maxPrice);
        auction.setCategory(category != null ? category : "");

        try {
            long auctionId = mainDAO.createAuction(auction, imageUrls);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("auctionId", auctionId);
            body.put("message", "Auction created successfully.");
            ok(resp, body);
        } catch (Exception e) {
            serverError(resp, "Could not create auction.");
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

        String reason = param(req, "reason");
        if (reason != null) reason = reason.trim();

        try {
            boolean cancelled = auctionDAO.cancelAuction(auctionId, sellerId, reason);
            if (!cancelled) {
                error(resp, 400, "Could not cancel auction. It may not exist, you may not own it, or it is already finished/cancelled.");
            } else {
                okMsg(resp, "Auction cancelled successfully.");
            }
        } catch (Exception e) {
            serverError(resp, "Could not cancel auction.");
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
        if (title == null || title.isBlank()) { badRequest(resp, "title is required."); return; }

        Integer itemConditionId = null;
        if (conditionStr != null && !conditionStr.isBlank()) {
            try {
                int cid = Integer.parseInt(conditionStr);
                ItemCondition.getItemCondition(cid); // validate
                itemConditionId = cid;
            } catch (Exception e) { badRequest(resp, "Invalid item condition."); return; }
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
                    category, itemConditionId, newEndDate, deleteIds, newUrls);
            okMsg(resp, "Auction updated successfully.");
        } catch (IllegalStateException e) {
            error(resp, 400, e.getMessage());
        } catch (Exception e) {
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
                case BUYER_NOT_RATED_YET:  error(resp, 400, "You can rate the buyer only after they have rated you for this auction."); break;
                case ALREADY_RATED:        error(resp, 400, "You have already rated the buyer for this auction."); break;
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
