package com.auction.servlet;

import com.auction.dao.AuctionDAO;
import com.auction.dao.CategoryDAO;
import com.auction.model.Auction;
import com.auction.model.Category;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

public class ProductListServlet extends HttpServlet {

    private final AuctionDAO auctionDAO = new AuctionDAO();
    private final CategoryDAO categoryDAO = new CategoryDAO();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        List<Auction> auctions = auctionDAO.findActive();
        List<Category> categories = categoryDAO.findAll();

        req.setAttribute("auctions", auctions);
        req.setAttribute("categories", categories);
        req.getRequestDispatcher("/product/list.jsp").forward(req, resp);
    }
}
