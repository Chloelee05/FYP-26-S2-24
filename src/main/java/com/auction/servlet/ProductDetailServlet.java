package com.auction.servlet;

import com.auction.dao.AuctionDAO;
import com.auction.dao.BidDAO;
import com.auction.model.Auction;
import com.auction.model.Bid;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

public class ProductDetailServlet extends HttpServlet {

    private final AuctionDAO auctionDAO = new AuctionDAO();
    private final BidDAO bidDAO = new BidDAO();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        String idStr = req.getParameter("id");
        if (idStr == null) {
            resp.sendRedirect(req.getContextPath() + "/product/list");
            return;
        }

        int auctionId;
        try {
            auctionId = Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            resp.sendRedirect(req.getContextPath() + "/product/list");
            return;
        }

        Auction auction = auctionDAO.findById(auctionId);
        if (auction == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Auction not found");
            return;
        }

        List<Bid> bids = bidDAO.findByAuction(auctionId);

        req.setAttribute("auction", auction);
        req.setAttribute("bids", bids);
        req.getRequestDispatcher("/product/detail.jsp").forward(req, resp);
    }
}
