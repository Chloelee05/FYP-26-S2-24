package com.auction.servlet.seller;

import com.auction.dao.SellerAuctionDAO;
import com.auction.dao.SellerAuctionDAO.AuctionEditData;
import com.auction.model.AuctionStatus;
import com.auction.util.RbacUtil;
import com.auction.util.SecurityUtil;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * SCRUM-37 – Edit auction details before any bids are placed.
 *
 * GET  /seller/edit-auction?id=<auctionId>
 *   Verifies ownership and zero-bid precondition, then forwards to the edit form JSP.
 *
 * POST /seller/edit-auction
 *   auction_id        (required)
 *   title             (required, non-blank)
 *   description       (required, non-blank)
 *   delete_image_ids  (optional, multi-value) – IDs of images to remove
 *   images            (optional, multi-part)  – new image files to add
 *
 * Preconditions (re-checked on every submit to prevent TOCTOU):
 *   - Session user is the owning seller
 *   - Auction status is ACTIVE or PENDING
 *   - No bids have been placed (bidCount == 0)
 *
 * <p>Legacy JSP flow; the SPA edits listings through {@code /api/seller/*} in
 * {@code SellerApiServlet}. The zero-bid rule is the important business constraint: once
 * anybody has bid, the description they bid against is fixed, otherwise a seller could swap
 * the item out from under a live auction.</p>
 *
 * <p>The GET checks the preconditions to decide what form to show, and the POST checks them
 * again inside the DAO transaction. That repetition is the TOCTOU guard noted above: a bid can
 * arrive while the seller is typing.</p>
 */
@MultipartConfig(
        fileSizeThreshold = 1024 * 1024,
        maxFileSize       = 1024 * 1024 * 5,
        maxRequestSize    = 1024 * 1024 * 20
)
@WebServlet("/seller/edit-auction")
public class EditAuctionServlet extends HttpServlet {

    private SellerAuctionDAO dao;
    private String uploadDir;

    private static final List<String> ALLOWED_EXTENSIONS = List.of(".jpg", ".jpeg", ".png", ".webp");

    /**
     * Resolves the {@code uploadDir} context parameter. Unlike {@code CreateAuctionServlet} this
     * one does not create the directory, since by the time a listing is being edited the create
     * path has already made it.
     */
    @Override
    public void init() throws ServletException {
        dao = new SellerAuctionDAO();
        uploadDir = getServletContext().getInitParameter("uploadDir");
        if (uploadDir == null) throw new ServletException("uploadDir context param is not set");
    }

    /** Injection points for unit tests, which need a stub DAO and a temporary upload directory. */
    public void setDao(SellerAuctionDAO dao) { this.dao = dao; }
    public void setUploadDir(String uploadDir) { this.uploadDir = uploadDir; }

    // ------------------------------------------------------------------ GET

    /**
     * Shows the edit form for {@code id}. The DAO is queried with both the auction id and the
     * seller id, so a listing belonging to somebody else comes back as null and is answered with
     * 403; the response does not distinguish "not yours" from "does not exist".
     * An auction that already has bids still renders the form, but with an explanatory error
     * instead of editable fields.
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        HttpSession session = req.getSession(false);
        if (!RbacUtil.isSeller(session)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        int sellerId = ((Number) session.getAttribute("userId")).intValue();

        long auctionId = resolveAuctionId(req, resp);
        if (auctionId < 0) return;

        AuctionEditData data;
        try {
            data = dao.getAuctionForEdit(auctionId, sellerId);
        } catch (Exception e) {
            getServletContext().log("EditAuctionServlet GET error", e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        if (data == null) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        if (data.statusId != AuctionStatus.ACTIVE.getId()
                && data.statusId != AuctionStatus.PENDING.getId()) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Auction is not editable");
            return;
        }

        try {
            int bidCount = dao.countBids(auctionId);
            if (bidCount > 0) {
                req.setAttribute("Error", "Auction cannot be edited once bids have been placed");
                req.setAttribute("auctionId", auctionId);
                req.getRequestDispatcher("/WEB-INF/views/seller/edit-auction.jsp").forward(req, resp);
                return;
            }
        } catch (Exception e) {
            getServletContext().log("EditAuctionServlet bid count error", e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        req.setAttribute("auction", data);
        req.getRequestDispatcher("/WEB-INF/views/seller/edit-auction.jsp").forward(req, resp);
    }

    // ------------------------------------------------------------------ POST

    /**
     * Saves the edit: new title and description, images to delete, images to add. Title and
     * description are sanitized before storage because they are rendered on the public listing.
     * If the DAO rejects the change on re-check, the images just uploaded are deleted again so
     * a refused edit leaves no files behind.
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        HttpSession session = req.getSession(false);
        if (!RbacUtil.isSeller(session)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        int sellerId = ((Number) session.getAttribute("userId")).intValue();

        long auctionId = resolveAuctionId(req, resp);
        if (auctionId < 0) return;

        String title = trimOrNull(req.getParameter("title"));
        String description = trimOrNull(req.getParameter("description"));

        if (title == null || title.isBlank() || description == null || description.isBlank()) {
            req.setAttribute("Error", "Title and description are required");
            req.setAttribute("auctionId", auctionId);
            req.getRequestDispatcher("/WEB-INF/views/seller/edit-auction.jsp").forward(req, resp);
            return;
        }
        title       = SecurityUtil.sanitize(title);
        description = SecurityUtil.sanitize(description);

        // Parse image IDs to delete
        List<Long> deleteImageIds = parseDeleteImageIds(req, resp);
        if (deleteImageIds == null) return;

        // Process new image uploads
        List<String> newFilenames = processImages(req, resp);
        if (newFilenames == null) return;

        try {
            // editAuction re-checks ownership, status, and bid count inside a transaction
            dao.editAuction(auctionId, sellerId, title, description, null, null, null, deleteImageIds, newFilenames);
            resp.sendRedirect(req.getContextPath() + "/auction?id=" + auctionId);
        } catch (IllegalStateException e) {
            cleanupFiles(newFilenames);
            req.setAttribute("Error", e.getMessage());
            req.setAttribute("auctionId", auctionId);
            req.getRequestDispatcher("/WEB-INF/views/seller/edit-auction.jsp").forward(req, resp);
        } catch (Exception e) {
            cleanupFiles(newFilenames);
            getServletContext().log("EditAuctionServlet POST error", e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Returns the auction_id from query string or form param; writes error and returns -1 on failure. */
    private long resolveAuctionId(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // Two names because the GET arrives as ?id= from a link while the POST carries the
        // hidden auction_id field, and the same helper serves both.
        String param = req.getParameter("id");
        if (param == null) param = req.getParameter("auction_id");
        if (param == null || param.isBlank()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing auction id");
            return -1;
        }
        try {
            return Long.parseLong(param.trim());
        } catch (NumberFormatException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid auction id");
            return -1;
        }
    }

    /**
     * Reads the checked {@code delete_image_ids}, or an empty list when none were ticked.
     * Whether those images actually belong to this auction is checked in the DAO, alongside the
     * ownership test, so a hand-edited id cannot delete another seller's photo.
     */
    private List<Long> parseDeleteImageIds(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        String[] raw = req.getParameterValues("delete_image_ids");
        if (raw == null) return Collections.emptyList();
        List<Long> ids = new ArrayList<>();
        for (String s : raw) {
            try {
                ids.add(Long.parseLong(s.trim()));
            } catch (NumberFormatException e) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid image id");
                return null;
            }
        }
        return ids;
    }

    /**
     * Writes any newly attached photos and returns their stored names, or null if one was
     * rejected. Same two rules as the create path: the extension must be in the allowed list,
     * and the stored name is a UUID rather than the client's filename.
     */
    private List<String> processImages(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        List<String> saved = new ArrayList<>();
        try {
            Collection<Part> parts = req.getParts().stream()
                    .filter(p -> "images".equals(p.getName()) && p.getSize() > 0)
                    .collect(Collectors.toList());

            for (Part part : parts) {
                String original = Paths.get(part.getSubmittedFileName()).getFileName().toString();
                int dot = original.lastIndexOf('.');
                if (dot == -1) {
                    cleanupFiles(saved);
                    resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "File must have an extension");
                    return null;
                }
                String ext = original.substring(dot).toLowerCase();
                if (!ALLOWED_EXTENSIONS.contains(ext)) {
                    cleanupFiles(saved);
                    resp.sendError(HttpServletResponse.SC_BAD_REQUEST,
                            "Only JPG, PNG, and WEBP images are allowed");
                    return null;
                }
                String name = UUID.randomUUID() + ext;
                part.write(Paths.get(uploadDir, name).toString());
                saved.add(name);
            }
        } catch (Exception e) {
            cleanupFiles(saved);
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Image upload failed");
            return null;
        }
        return saved;
    }

    /** Deletes photos written for an edit that is not going to be saved. */
    private void cleanupFiles(List<String> filenames) {
        for (String fn : filenames) {
            try { Files.deleteIfExists(Paths.get(uploadDir, fn)); } catch (IOException ignore) {}
        }
    }

    private String trimOrNull(String v) {
        return v == null ? null : v.trim();
    }
}
