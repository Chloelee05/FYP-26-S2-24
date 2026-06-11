package com.auction.servlet.admin;

import com.auction.dao.AuctionDAO;
import com.auction.dao.UserDAO;
import com.auction.model.User;
import com.auction.model.admin.AdminListingRow;
import com.auction.model.admin.AdminUserSummary;
import com.auction.util.RbacUtil;
import com.itextpdf.text.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;

@WebServlet("/admin/generate-report")
public class AdminGenReportServlet extends HttpServlet {

    private UserDAO userDAO;
    private AuctionDAO auctionDAO;

    public AdminGenReportServlet(){

    }

    public void setDAOs(UserDAO userDAO, AuctionDAO auctionDAO)
    {
        this.userDAO = userDAO;
        this.auctionDAO = auctionDAO;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        HttpSession session = req.getSession(false);
        if (session == null) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        User user = (User) session.getAttribute("user");
        if (user == null) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if(!RbacUtil.isAdmin(session))
        {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        String sellerUsername = req.getParameter("sellerUsername");
        String category       = req.getParameter("category");
        String from           = req.getParameter("from");
        String to             = req.getParameter("to");

        sellerUsername = (sellerUsername == null || sellerUsername.isBlank()) ? null : sellerUsername.trim();
        category       = (category == null || category.isBlank()) ? null : category.trim();

        Instant fromInstant = null;
        Instant toInstant   = null;

        try {
            if (from != null && !from.isBlank())
                fromInstant = LocalDate.parse(from).atStartOfDay(ZoneId.systemDefault()).toInstant();
            if (to != null && !to.isBlank())
                toInstant = LocalDate.parse(to).atStartOfDay(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid date format");
            return;
        }

        if (fromInstant != null && toInstant != null && toInstant.isBefore(fromInstant)) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "End date must be after start date");
            return;
        }

        resp.setContentType("application/pdf");
        resp.setHeader("Content-Disposition", "attachment; filename=admin-report.pdf");

        try {
            byte[] pdf = buildReport(sellerUsername, category, fromInstant, toInstant);
            resp.getOutputStream().write(pdf);
        } catch (Exception e) {
            getServletContext().log("PDF generation error", e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Could not generate report");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        //
    }

    private boolean hasFilters(String sellerUsername, String category, Instant from, Instant to) {
        return sellerUsername != null || category != null || from != null || to != null;
    }

    private byte[] buildReport(String sellerUsername, String category, Instant from, Instant to) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document();
        PdfWriter.getInstance(document, out);
        document.open();

        if (hasFilters(sellerUsername, category, from, to)) {
            buildFilteredReport(document, sellerUsername, category, from, to);
        } else {
            buildFullReport(document);
        }

        document.close();
        return out.toByteArray();
    }

    private void buildFullReport(Document document) throws Exception {
        Font titleFont = new Font(Font.FontFamily.HELVETICA, 18, Font.BOLD);
        Paragraph title = new Paragraph("Admin Platform Report", titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        document.add(title);
        document.add(new Paragraph("Generated: " + Instant.now()));
        document.add(Chunk.NEWLINE);

        // Metrics
        document.add(new Paragraph("Platform Metrics", new Font(Font.FontFamily.HELVETICA, 14, Font.BOLD)));
        document.add(new Paragraph("Total Users: "      + userDAO.countNonDeletedUsers()));
        document.add(new Paragraph("Active Users: "     + userDAO.countActiveUsers()));
        document.add(new Paragraph("Total Listings: "   + auctionDAO.countListingsTotal()));
        document.add(new Paragraph("Flagged Listings: " + auctionDAO.countListingsFlagged()));
        document.add(new Paragraph("Total Revenue: $"   + auctionDAO.sumWinningBidDollars()));
        document.add(Chunk.NEWLINE);

        // Users table
        document.add(new Paragraph("User Summary", new Font(Font.FontFamily.HELVETICA, 14, Font.BOLD)));
        document.add(Chunk.NEWLINE);
        PdfPTable userTable = new PdfPTable(4);
        userTable.setWidthPercentage(100);
        addTableHeader(userTable, "ID", "Username", "Email", "Role");
        for (AdminUserSummary user : userDAO.listUsersForAdminTable()) {
            userTable.addCell(String.valueOf(user.getId()));
            userTable.addCell(user.getUsername());
            userTable.addCell(user.getEmail());
            userTable.addCell(String.valueOf(user.getRole()));
        }
        document.add(userTable);
        document.add(Chunk.NEWLINE);

        // Listings table
        document.add(new Paragraph("Listings", new Font(Font.FontFamily.HELVETICA, 14, Font.BOLD)));
        document.add(Chunk.NEWLINE);
        PdfPTable listingTable = new PdfPTable(4);
        listingTable.setWidthPercentage(100);
        addTableHeader(listingTable, "ID", "Title", "Seller", "Status");
        for (AdminListingRow listing : auctionDAO.listListingsForModeration()) {
            listingTable.addCell(String.valueOf(listing.getAuctionId()));
            listingTable.addCell(listing.getTitle());
            listingTable.addCell(listing.getSellerUsername());
            listingTable.addCell(listing.getModerationState());
        }
        document.add(listingTable);
    }

    private void buildFilteredReport(Document document, String sellerUsername, String category,
                                     Instant from, Instant to) throws Exception {
        Font titleFont = new Font(Font.FontFamily.HELVETICA, 18, Font.BOLD);
        Paragraph title = new Paragraph("Admin Listings Report", titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        document.add(title);
        document.add(new Paragraph("Generated: " + Instant.now()));
        document.add(Chunk.NEWLINE);

        // Filters summary
        document.add(new Paragraph("Filters Applied", new Font(Font.FontFamily.HELVETICA, 12, Font.BOLD)));
        document.add(new Paragraph("Seller: "   + (sellerUsername != null ? sellerUsername : "All")));
        document.add(new Paragraph("Category: " + (category != null ? category : "All")));
        document.add(new Paragraph("From: "     + (from != null ? from.toString() : "Any")));
        document.add(new Paragraph("To: "       + (to != null ? to.toString() : "Any")));
        document.add(Chunk.NEWLINE);

        List<AdminListingRow> listings = auctionDAO.listForGenReport(sellerUsername, category, from, to);
        document.add(new Paragraph("Results (" + listings.size() + " listings)",
                new Font(Font.FontFamily.HELVETICA, 12, Font.BOLD)));
        document.add(Chunk.NEWLINE);

        if (listings.isEmpty()) {
            document.add(new Paragraph("No listings found for the selected filters."));
        } else {
            PdfPTable table = new PdfPTable(4);
            table.setWidthPercentage(100);
            addTableHeader(table, "ID", "Title", "Seller", "Status");
            for (AdminListingRow row : listings) {
                table.addCell(String.valueOf(row.getAuctionId()));
                table.addCell(row.getTitle());
                table.addCell(row.getSellerUsername());
                table.addCell(row.getModerationState());
            }
            document.add(table);
        }
    }

    private void addTableHeader(PdfPTable table, String... headers) {
        Font headerFont = new Font(Font.FontFamily.HELVETICA, 12, Font.BOLD);
        for (String header : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(header, headerFont));
            cell.setBackgroundColor(BaseColor.LIGHT_GRAY);
            cell.setPadding(5);
            table.addCell(cell);
        }
    }
}
