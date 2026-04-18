package com.auction.servlet;

import com.auction.dao.AuctionDAO;
import com.auction.model.Auction;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

public class SearchServlet extends HttpServlet {

    private final AuctionDAO auctionDAO = new AuctionDAO();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        String query = req.getParameter("q");
        if (query != null && !query.trim().isEmpty()) {
            List<Auction> results = auctionDAO.search(query.trim());
            req.setAttribute("auctions", results);
            req.setAttribute("searchQuery", query.trim());
        }
        req.getRequestDispatcher("/product/list.jsp").forward(req, resp);
    }
}
