package com.auction.servlet;

import java.io.IOException;
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
public class CreateAuctionServlet extends HttpServlet{
    private AuctionDAO auctionDAO;
    private AuctionTagsDAO auctionTagsDAO;
    private String uploadDir;
    private static final List<String> ALLOWED_EXTENSIONS = List.of(".jpg", ".jpeg", ".png", ".webp");

    public CreateAuctionServlet(){
        auctionDAO = new AuctionDAO();
        auctionTagsDAO = new AuctionTagsDAO();
    }

    public void setAuctionDAO(AuctionDAO auctionDAO, AuctionTagsDAO auctionTagsDAO){
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
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED); // 401
            return;
        }

        String role = (String) session.getAttribute("userRole");
        if (role == null || !role.equalsIgnoreCase("seller")) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        int seller_id = ((Number) session.getAttribute("userId")).intValue();
        String auction_name = req.getParameter("auction_name");
        String auction_details = req.getParameter("auction_details");
        String start_date = req.getParameter("start_date");
        String end_date = req.getParameter("end_date");
        String start_price = req.getParameter("start_price");
        String auction_type = req.getParameter("auction_type");
        String item_condition = req.getParameter("item_condition");
        AuctionType auctionTypeEnum = AuctionType.PRICE_UP;
        ItemCondition itemConditionEnum = null;
        String[] tagIds = req.getParameterValues("tags");

        auction_name = (auction_name == null) ? null : auction_name.trim();
        auction_details = (auction_details == null) ? null : auction_details.trim();
        start_date = (start_date == null) ? null : start_date.trim();
        end_date = (end_date == null) ? null : end_date.trim();
        start_price = (start_price == null) ? null : start_price.trim();
        auction_type = (auction_type == null) ? null: auction_type.trim();
        item_condition = (item_condition == null)? null: item_condition.trim();

        if (auction_name == null || auction_name.isBlank() ||
                auction_details == null || auction_details.isBlank() ||
                end_date == null || end_date.isBlank() ||
                item_condition == null || item_condition.isBlank()) {
            errorHandler(req, resp, "All fields are required",
                    auction_name, auction_details, start_date, end_date, start_price,
                    auction_type, item_condition);
            return;
        }
        //default start price if user input is null
        float price = 0;
        if (start_price != null && !start_price.isBlank()) {
            try {
                price = Float.parseFloat(start_price);
                if (price <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                errorHandler(req, resp, "Invalid start price",
                        auction_name, auction_details, start_date, end_date, start_price,
                        auction_type, item_condition);
                return;
            }
        }
        //default start_date if user input is null
        Instant auctionStart = Instant.now();
        Instant auctionEnd;

        try {
            if(start_date != null && !start_date.isBlank()) {
                auctionStart = OffsetDateTime.parse(start_date).toInstant();
            }
            auctionEnd   = OffsetDateTime.parse(end_date).toInstant();
        } catch (DateTimeParseException e) {
            errorHandler(req, resp, "Invalid date format",
                    auction_name, auction_details, start_date, end_date, start_price,
                    auction_type, item_condition);
            return;
        }

        if (auctionEnd.isBefore(auctionStart)) {
            errorHandler(req, resp, "End date must be after start date",
                    auction_name, auction_details, start_date, end_date, start_price,
                    auction_type, item_condition);
            return;
        }

        if(auction_type != null && !auction_type.isBlank())
        {
            try {
                int typeId = Integer.parseInt(auction_type);
                auctionTypeEnum = AuctionType.getAuctionType(typeId);
            } catch (IllegalArgumentException e) {
                errorHandler(req, resp, "Invalid auction type",
                        auction_name, auction_details, start_date, end_date, start_price,
                        auction_type, item_condition);
                return;
            }
        }

        if(!item_condition.isBlank()) {
            try {
                int conditionId = Integer.parseInt(item_condition);
                itemConditionEnum = ItemCondition.getItemCondition(conditionId);
            } catch (IllegalArgumentException e) {
                errorHandler(req, resp, "Invalid item condition",
                        auction_name, auction_details, start_date, end_date, start_price,
                        auction_type, item_condition);
                return;
            }
        }

        List<String> savedFilenames = new ArrayList<>();
        try {
            Collection<Part> fileParts = req.getParts()
                    .stream()
                    .filter(p -> "images".equals(p.getName()) && p.getSize() > 0)
                    .collect(Collectors.toList());

            for (Part part : fileParts) {
                String originalName = Paths.get(part.getSubmittedFileName()).getFileName().toString();
                int dotIndex = originalName.lastIndexOf('.');
                if (dotIndex == -1) {
                    errorHandler(req, resp, "File must have an extension",
                            auction_name, auction_details, start_date, end_date, start_price,
                            auction_type, item_condition);
                    return;
                }
                String ext = originalName.substring(dotIndex).toLowerCase();

                if (!ALLOWED_EXTENSIONS.contains(ext)) {
                    errorHandler(req, resp, "Only JPG, PNG, and WEBP images are allowed",
                            auction_name, auction_details, start_date, end_date, start_price,
                            auction_type, item_condition);
                    return;
                }

                String savedName = UUID.randomUUID() + ext;
                Path dest = Paths.get(uploadDir, savedName);
                part.write(dest.toString());
                savedFilenames.add(savedName); // store just the filename; build full URL when serving
            }
        } catch (Exception e) {
            errorHandler(req, resp, "Image upload failed",
                    auction_name, auction_details, start_date, end_date, start_price,
                    auction_type, item_condition);
            return;
        }

        List<Long> selectedTagIds = new ArrayList<>();
        if (tagIds != null) {
            Set<Long> validIds;
            try {
                validIds = auctionTagsDAO.getAllTags().keySet();
            } catch (SQLException e) {
                errorHandler(req, resp, "Could not validate tags",
                        auction_name, auction_details, start_date, end_date, start_price,
                        auction_type, item_condition);
                return;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            for (String tagId : tagIds) {
                try {
                    long id = Long.parseLong(tagId);
                    if (!validIds.contains(id)) {
                        errorHandler(req, resp, "Invalid tag selected",
                                auction_name, auction_details, start_date, end_date, start_price,
                                auction_type, item_condition);
                        return;
                    }
                    selectedTagIds.add(id);
                } catch (NumberFormatException e) {
                    errorHandler(req, resp, "Invalid tag",
                            auction_name, auction_details, start_date, end_date, start_price,
                            auction_type, item_condition);
                    return;
                }
            }
        }

        Auction auction = new Auction(seller_id, auction_name, auction_details, auctionStart, auctionEnd,
                price, auctionTypeEnum, itemConditionEnum, selectedTagIds);

        try {
            long auctionId = auctionDAO.createAuction(auction, savedFilenames);
            resp.sendRedirect(req.getContextPath() + "/auction?id=" + auctionId);
        } catch (Throwable ex) {
            for (String filename : savedFilenames) {
                try { Files.deleteIfExists(Paths.get(uploadDir, filename)); }
                catch (IOException ignore) {}
            }
            getServletContext().log("Auction database error", ex);
            errorHandler(req, resp,
                    "Could not reach the database. Ensure PostgreSQL is running, JDBC driver is on the classpath, "
                            + "and DBUtil settings are correct.",
                    auction_name, auction_details, start_date, end_date, start_price,
                    auction_type, item_condition);
        }
    }


    private void errorHandler(HttpServletRequest req, HttpServletResponse resp, String message, String auction_name, String auction_details,
                              String start_date,
                              String end_date,
                              String start_price,
                              String auction_type,
                              String item_condition) throws ServletException, IOException {
        req.setAttribute("Error", message);
        stickyForm(req, auction_name, auction_details, start_date, end_date, start_price, auction_type, item_condition);
        //req.getRequestDispatcher(???).forward(req, resp);
    }

    private void stickyForm(HttpServletRequest req, String auction_name, String auction_details,
                            String start_date,
                            String end_date,
                            String start_price,
                            String auction_type,
                            String item_condition) {
        req.setAttribute("auction_name", auction_name);
        req.setAttribute("auction_details", auction_details);
        req.setAttribute("start_date", start_date);
        req.setAttribute("end_date", end_date);
        req.setAttribute("start_price", start_price);
        req.setAttribute("auction_type", auction_type);
        req.setAttribute("item_condition", item_condition);
    }
}
