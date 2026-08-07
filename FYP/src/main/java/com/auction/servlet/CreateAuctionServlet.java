package com.auction.servlet;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

import com.auction.dao.AuctionDAO;
import com.auction.dao.AuctionTagsDAO;
import com.auction.model.Auction;
import com.auction.model.AuctionTags;
import com.auction.model.AuctionType;
import com.auction.model.ItemCondition;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;

/**
 * Legacy JSP listing-creation endpoint. Takes the multipart new-auction form, validates it,
 * writes the uploaded photos to the upload directory, and inserts the auction plus its tags
 * through {@link AuctionDAO} and {@link AuctionTagsDAO}.
 *
 * <p>The SPA creates listings through {@code /api/auction/*} in {@code AuctionApiServlet}.</p>
 *
 * <p>Sellers only: the session's role is checked before anything is parsed. The auction type
 * chosen here decides the whole later lifecycle, PRICE_UP for an ascending auction,
 * DUTCH_AUCTION for a declining clock, BLIND for sealed bids, and defaults to PRICE_UP when
 * the form leaves it out.</p>
 *
 * <p>Uploads are handled defensively. Only a fixed list of image extensions is accepted, the
 * submitted filename is reduced to its last path segment and then replaced with a UUID so a
 * crafted name cannot escape the upload directory, and any files already written are deleted
 * if a later step fails, so a rejected submission leaves nothing behind on disk.</p>
 */
@MultipartConfig(
        fileSizeThreshold = 1024 * 1024,      // 1MB - buffer in memory before writing to disk
        maxFileSize       = 1024 * 1024 * 5,  // 5MB per file
        maxRequestSize    = 1024 * 1024 * 20  // 20MB total request
)

@WebServlet("/create-auction")
public class CreateAuctionServlet extends HttpServlet {
    private AuctionDAO auctionDAO;
    private AuctionTagsDAO auctionTagsDAO;
    private String uploadDir;
    private static final List<String> ALLOWED_EXTENSIONS = List.of(".jpg", ".jpeg", ".png", ".webp");
    // NEW for the "platform-wide auction rules" admin story. Mirrors the guard added to the SPA
    // create path, SellerApiServlet.handleCreate, for this legacy JSP path as well.
    private com.auction.dao.PlatformSettingsDAO platformSettingsDAO = new com.auction.dao.PlatformSettingsDAO();

    public CreateAuctionServlet() {
        auctionDAO = new AuctionDAO();
        auctionTagsDAO = new AuctionTagsDAO();
    }

    /** Injection point for stub DAOs in unit tests. */
    public void setAuctionDAO(AuctionDAO auctionDAO, AuctionTagsDAO auctionTagsDAO) {
        this.auctionDAO = auctionDAO;
        this.auctionTagsDAO = auctionTagsDAO;
    }


    /**
     * Resolves the {@code uploadDir} context parameter from {@code web.xml} and creates the
     * directory if it is missing. Failing here refuses to start the servlet at all, which is
     * better than accepting a listing and silently losing its photos at write time.
     */
    @Override
    public void init() throws ServletException {
        uploadDir = getServletContext().getInitParameter("uploadDir");
        if (uploadDir == null) throw new ServletException("uploadDir context param is not set");
        try {
            Files.createDirectories(Paths.get(uploadDir));
        } catch (IOException e) {
            throw new ServletException("Could not create upload directory: " + uploadDir, e);
        }
    }

    /** Not implemented: the create-listing form was only ever built in the React front end. */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        //
    }

    /**
     * Creates the auction. Runs in four stages, each of which can abort the request: parse and
     * validate the fields, write the images, validate the tag ids against the tag table, then
     * insert. If the insert throws, the images written a moment earlier are removed so a failed
     * attempt does not leave orphaned files.
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        HttpSession session = req.getSession(false);
        if (session == null) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        String role = (String) session.getAttribute("userRole");
        if (role == null || !role.equalsIgnoreCase("seller")) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        int seller_id = ((Number) session.getAttribute("userId")).intValue();

        AuctionFormInput input = parseFormInput(req);
        if (!validateFormInput(req, resp, input)) return;

        List<String> savedFilenames = processImages(req, resp, input);
        if (savedFilenames == null) return;

        List<Long> selectedTagIds = validateTags(req, resp, input);
        if (selectedTagIds == null) return;

        Auction auction = new Auction(seller_id, input.auctionName, input.auctionDetails,
                input.auctionStart, input.auctionEnd, input.price,
                input.auctionTypeEnum, input.itemConditionEnum, selectedTagIds);
        auction.setMaxPrice(input.maxPriceParsed);

        try {
            long auctionId = auctionDAO.createAuction(auction, savedFilenames);
            resp.sendRedirect(req.getContextPath() + "/auction?id=" + auctionId);
        } catch (Throwable ex) {
            for (String filename : savedFilenames) {
                try {
                    Files.deleteIfExists(Paths.get(uploadDir, filename));
                } catch (IOException ignore){}
            }
            cleanupFiles(savedFilenames);
            getServletContext().log("Auction database error", ex);
            errorHandler(req, resp, "Could not reach the database.", input);
        }
    }


    /**
     * Carrier for the submitted form. Holds both the raw strings, which are needed to repopulate
     * the form after an error, and the parsed values used for the insert.
     */
    private static class AuctionFormInput {
        String auctionName, auctionDetails, startDate, endDate, startPrice, maxPrice, auctionType, itemCondition;
        String[] tagIds;
        float price;
        BigDecimal maxPriceParsed; // null when not provided (SCRUM-33)
        Instant auctionStart, auctionEnd;
        AuctionType auctionTypeEnum;
        ItemCondition itemConditionEnum;
    }

    private String trimOrNull(String value) {
        return (value == null) ? null : value.trim();
    }

    /** Pulls the text fields off the multipart request without judging them yet. */
    private AuctionFormInput parseFormInput(HttpServletRequest req) {
        AuctionFormInput input = new AuctionFormInput();
        input.auctionName    = trimOrNull(req.getParameter("auction_name"));
        input.auctionDetails = trimOrNull(req.getParameter("auction_details"));
        input.startDate      = trimOrNull(req.getParameter("start_date"));
        input.endDate        = trimOrNull(req.getParameter("end_date"));
        input.startPrice     = trimOrNull(req.getParameter("start_price"));
        input.maxPrice       = trimOrNull(req.getParameter("max_price"));
        input.auctionType    = trimOrNull(req.getParameter("auction_type"));
        input.itemCondition  = trimOrNull(req.getParameter("item_condition"));
        input.tagIds         = req.getParameterValues("tags");
        return input;
    }

    /**
     * Checks the form and fills in the parsed fields on {@code input} as it goes.
     * Covers the required fields, a positive starting price, a max price above the starting
     * price when one is given, parseable dates with the end after the start, and both enum
     * values. An omitted start date means "start now".
     */
    // returns false if validation fails
    private boolean validateFormInput(HttpServletRequest req, HttpServletResponse resp, AuctionFormInput input) throws ServletException, IOException {
        if (input.auctionName == null || input.auctionName.isBlank() ||
                input.auctionDetails == null || input.auctionDetails.isBlank() ||
                input.endDate == null || input.endDate.isBlank() ||
                input.itemCondition == null || input.itemCondition.isBlank()) {
            errorHandler(req, resp, "All fields are required", input);
            return false;
        }

        input.price = 0;
        if (input.startPrice != null && !input.startPrice.isBlank()) {
            try {
                input.price = Float.parseFloat(input.startPrice);
                if (input.price <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                errorHandler(req, resp, "Invalid start price", input);
                return false;
            }
        }

        input.maxPriceParsed = null;
        if (input.maxPrice != null && !input.maxPrice.isBlank()) {
            try {
                input.maxPriceParsed = new BigDecimal(input.maxPrice);
                if (input.maxPriceParsed.compareTo(BigDecimal.ZERO) <= 0) throw new NumberFormatException();
                if (input.price > 0 && input.maxPriceParsed.compareTo(BigDecimal.valueOf(input.price)) <= 0) {
                    errorHandler(req, resp, "Max price must be greater than starting price", input);
                    return false;
                }
            } catch (NumberFormatException e) {
                errorHandler(req, resp, "Invalid max price", input);
                return false;
            }
        }

        try {
            input.auctionStart = Instant.now();
            if (input.startDate != null && !input.startDate.isBlank())
                input.auctionStart = OffsetDateTime.parse(input.startDate).toInstant();
            input.auctionEnd = OffsetDateTime.parse(input.endDate).toInstant();
        } catch (DateTimeParseException e) {
            errorHandler(req, resp, "Invalid date format", input);
            return false;
        }

        if (input.auctionEnd.isBefore(input.auctionStart)) {
            errorHandler(req, resp, "End date must be after start date", input);
            return false;
        }
        // NEW for the "platform-wide auction rules" admin story: same additional guard as
        // SellerApiServlet.handleCreate's SPA path, alongside (not replacing) the "end after
        // start" check just above.
        {
            int maxDurationDays = platformSettingsDAO.getInt(
                    "max_auction_duration_days",
                    com.auction.servlet.api.SellerApiServlet.DEFAULT_MAX_AUCTION_DURATION_DAYS);
            if (maxDurationDays > 0 && java.time.Duration.between(
                    input.auctionStart, input.auctionEnd).toDays() > maxDurationDays) {
                errorHandler(req, resp, "Auction duration cannot exceed " + maxDurationDays
                        + " day(s)", input);
                return false;
            }
        }

        input.auctionTypeEnum = AuctionType.PRICE_UP;
        if (input.auctionType != null && !input.auctionType.isBlank()) {
            try {
                input.auctionTypeEnum = AuctionType.getAuctionType(Integer.parseInt(input.auctionType));
            } catch (IllegalArgumentException e) {
                errorHandler(req, resp, "Invalid auction type", input);
                return false;
            }
        }

        try {
            input.itemConditionEnum = ItemCondition.getItemCondition(Integer.parseInt(input.itemCondition));
        } catch (IllegalArgumentException e) {
            errorHandler(req, resp, "Invalid item condition", input);
            return false;
        }

        return true;
    }

    /**
     * Writes the uploaded photos and returns the names they were stored under, or null if any
     * file was rejected. Two safeguards on each file: the extension must be in the allowed list,
     * and the stored name is a fresh UUID rather than anything the client supplied, so an
     * uploaded "../../shell.jsp" cannot land outside the upload directory or be served as code.
     */
    // returns null if processing fails
    private List<String> processImages(HttpServletRequest req, HttpServletResponse resp, AuctionFormInput input) throws ServletException, IOException {
        List<String> savedFilenames = new ArrayList<>();
        try {
            Collection<Part> fileParts = req.getParts().stream()
                    .filter(p -> "images".equals(p.getName()) && p.getSize() > 0)
                    .collect(Collectors.toList());

            for (Part part : fileParts) {
                String originalName = Paths.get(part.getSubmittedFileName()).getFileName().toString();
                int dotIndex = originalName.lastIndexOf('.');
                if (dotIndex == -1)
                {
                    cleanupFiles(savedFilenames);
                    errorHandler(req, resp, "File must have an extension", input);
                    return null;
                }

                String ext = originalName.substring(dotIndex).toLowerCase();
                if (!ALLOWED_EXTENSIONS.contains(ext))
                {
                    cleanupFiles(savedFilenames);
                    errorHandler(req, resp, "Only JPG, PNG, and WEBP images are allowed", input);
                    return null;
                }

                String savedName = UUID.randomUUID() + ext;
                part.write(Paths.get(uploadDir, savedName).toString());
                savedFilenames.add(savedName);
            }
        } catch (Exception e) {
            errorHandler(req, resp, "Image upload failed", input);
            return null;
        }
        return savedFilenames;
    }

    /**
     * Turns the submitted tag ids into longs and keeps only ids that exist in the tag table.
     * Checking against the real set rather than trusting the form stops a hand-edited request
     * attaching a listing to a tag that was never offered.
     */
    // returns null if validation fails
    private List<Long> validateTags(HttpServletRequest req, HttpServletResponse resp, AuctionFormInput input) throws ServletException, IOException {
        List<Long> selectedTagIds = new ArrayList<>();
        if (input.tagIds == null) return selectedTagIds;

        Set<Long> validIds;
        try {
            validIds = auctionTagsDAO.getAllTags().keySet();
        } catch (Exception e) {
            errorHandler(req, resp, "Could not validate tags", input);
            return null;
        }

        for (String tagId : input.tagIds) {
            try {
                long id = Long.parseLong(tagId);
                if (!validIds.contains(id)) { errorHandler(req, resp, "Invalid tag selected", input); return null; }
                selectedTagIds.add(id);
            } catch (NumberFormatException e) {
                errorHandler(req, resp, "Invalid tag", input);
                return null;
            }
        }
        return selectedTagIds;
    }

    /** Deletes photos already written for a submission that is not going to be saved. */
    private void cleanupFiles(List<String> filenames) {
        for (String filename : filenames) {
            try {
                Files.deleteIfExists(Paths.get(uploadDir, filename));
            }
            catch (IOException ignore)
            {}
        }
    }

    /** Records the error message and the submitted values for redisplay. */
    private void errorHandler(HttpServletRequest req, HttpServletResponse resp, String message, AuctionFormInput input) throws ServletException, IOException {
        req.setAttribute("Error", message);
        stickyForm(req, input);
        // req.getRequestDispatcher("???").forward(req, resp);
    }

    /** Puts the submitted field values back on the request under their form parameter names. */
    private void stickyForm(HttpServletRequest req, AuctionFormInput input) {
        req.setAttribute("auction_name",    input.auctionName);
        req.setAttribute("auction_details", input.auctionDetails);
        req.setAttribute("start_date",      input.startDate);
        req.setAttribute("end_date",        input.endDate);
        req.setAttribute("start_price",     input.startPrice);
        req.setAttribute("max_price",       input.maxPrice);
        req.setAttribute("auction_type",    input.auctionType);
        req.setAttribute("item_condition",  input.itemCondition);
    }
}
