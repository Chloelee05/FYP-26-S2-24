package com.auction.servlet;

import com.auction.dao.AuctionDAO;
import com.auction.dao.ProductDAO;
import com.auction.dao.UserDAO;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public class AdminDashboardServlet extends HttpServlet {

    private final UserDAO userDAO = new UserDAO();
    private final ProductDAO productDAO = new ProductDAO();
    private final AuctionDAO auctionDAO = new AuctionDAO();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        req.setAttribute("totalUsers", userDAO.countAll());
        req.setAttribute("totalProducts", productDAO.countAll());
        req.setAttribute("activeAuctions", auctionDAO.countActive());
        req.getRequestDispatcher("/admin/dashboard.jsp").forward(req, resp);
    }
}
