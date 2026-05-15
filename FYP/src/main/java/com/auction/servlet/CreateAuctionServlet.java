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

    public CreateAuctionServlet() {
        auctionDAO = new AuctionDAO();
        auctionTagsDAO = new AuctionTagsDAO();
    }

    public void setAuctionDAO(AuctionDAO auctionDAO, AuctionTagsDAO auctionTagsDAO) {
        this.auctionDAO = auctionDAO;
        this.auctionTagsDAO = auctionTagsDAO;
    }


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

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        //
    }

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

    private void cleanupFiles(List<String> filenames) {
        for (String filename : filenames) {
            try {
                Files.deleteIfExists(Paths.get(uploadDir, filename));
            }
            catch (IOException ignore)
            {}
        }
    }

    private void errorHandler(HttpServletRequest req, HttpServletResponse resp, String message, AuctionFormInput input) throws ServletException, IOException {
        req.setAttribute("Error", message);
        stickyForm(req, input);
        // req.getRequestDispatcher("???").forward(req, resp);
    }

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
