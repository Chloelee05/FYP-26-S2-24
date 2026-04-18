package com.auction.servlet;

import com.auction.dao.AuctionDAO;
import com.auction.dao.CategoryDAO;
import com.auction.dao.ProductDAO;
import com.auction.model.Auction;
import com.auction.model.Product;
import com.auction.model.User;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ProductCreateServlet extends HttpServlet {

    private final ProductDAO productDAO = new ProductDAO();
    private final AuctionDAO auctionDAO = new AuctionDAO();
    private final CategoryDAO categoryDAO = new CategoryDAO();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        HttpSession session = req.getSession(false);
        User user = (session != null) ? (User) session.getAttribute("user") : null;
        if (user == null || !user.isSeller()) {
            resp.sendRedirect(req.getContextPath() + "/auth/login");
            return;
        }
        req.setAttribute("categories", categoryDAO.findAll());
        req.getRequestDispatcher("/product/create.jsp").forward(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        HttpSession session = req.getSession(false);
        User user = (session != null) ? (User) session.getAttribute("user") : null;
        if (user == null || !user.isSeller()) {
            resp.sendRedirect(req.getContextPath() + "/auth/login");
            return;
        }

        String name = req.getParameter("name");
        String description = req.getParameter("description");
        String imageUrl = req.getParameter("imageUrl");
        String categoryIdStr = req.getParameter("categoryId");
        String startPriceStr = req.getParameter("startPrice");
        String bidIncrementStr = req.getParameter("bidIncrement");
        String durationStr = req.getParameter("duration");
        String strategy = req.getParameter("strategy");

        if (name == null || name.trim().isEmpty() || startPriceStr == null) {
            req.setAttribute("error", "Please fill in all required fields.");
            req.setAttribute("categories", categoryDAO.findAll());
            req.getRequestDispatcher("/product/create.jsp").forward(req, resp);
            return;
        }

        try {
            Product product = new Product();
            product.setSellerId(user.getId());
            product.setName(name.trim());
            product.setDescription(description != null ? description.trim() : "");
            product.setImageUrl(imageUrl != null && !imageUrl.trim().isEmpty() ? imageUrl.trim() : null);
            product.setCategoryId(categoryIdStr != null ? Integer.parseInt(categoryIdStr) : 1);

            productDAO.insert(product);

            BigDecimal startPrice = new BigDecimal(startPriceStr);
            BigDecimal bidIncrement = (bidIncrementStr != null && !bidIncrementStr.isEmpty())
                    ? new BigDecimal(bidIncrementStr) : new BigDecimal("1.00");
            int durationHours = (durationStr != null && !durationStr.isEmpty())
                    ? Integer.parseInt(durationStr) : 168; // default 7 days

            Auction auction = new Auction();
            auction.setProductId(product.getId());
            auction.setStartPrice(startPrice);
            auction.setCurrentPrice(startPrice);
            auction.setBidIncrement(bidIncrement);
            auction.setStartTime(Timestamp.valueOf(LocalDateTime.now()));
            auction.setEndTime(Timestamp.valueOf(LocalDateTime.now().plusHours(durationHours)));
            auction.setStatus("ACTIVE");
            auction.setStrategy(strategy != null ? strategy : "PRICE_UP");

            auctionDAO.insert(auction);

            resp.sendRedirect(req.getContextPath() + "/product/detail?id=" + auction.getId());
        } catch (Exception e) {
            req.setAttribute("error", "Failed to create auction: " + e.getMessage());
            req.setAttribute("categories", categoryDAO.findAll());
            req.getRequestDispatcher("/product/create.jsp").forward(req, resp);
        }
    }
}
