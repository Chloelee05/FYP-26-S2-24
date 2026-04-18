package com.auction.servlet;

import com.auction.dao.AuctionDAO;
import com.auction.dao.BidDAO;
import com.auction.model.Auction;
import com.auction.model.Bid;
import com.auction.model.User;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.math.BigDecimal;

public class BidServlet extends HttpServlet {

    private final AuctionDAO auctionDAO = new AuctionDAO();
    private final BidDAO bidDAO = new BidDAO();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        HttpSession session = req.getSession(false);
        User user = (session != null) ? (User) session.getAttribute("user") : null;

        if (user == null || !user.isBuyer()) {
            resp.sendRedirect(req.getContextPath() + "/auth/login");
            return;
        }

        String auctionIdStr = req.getParameter("auctionId");
        String amountStr = req.getParameter("amount");

        if (auctionIdStr == null || amountStr == null) {
            resp.sendRedirect(req.getContextPath() + "/product/list");
            return;
        }

        int auctionId;
        BigDecimal amount;
        try {
            auctionId = Integer.parseInt(auctionIdStr);
            amount = new BigDecimal(amountStr);
        } catch (NumberFormatException e) {
            resp.sendRedirect(req.getContextPath() + "/product/list");
            return;
        }

        Auction auction = auctionDAO.findById(auctionId);
        if (auction == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        if (!auction.isActive()) {
            req.setAttribute("error", "This auction is no longer active.");
            forwardToDetail(req, resp, auctionId);
            return;
        }

        BigDecimal minimumBid = auction.getCurrentPrice().add(auction.getBidIncrement());
        if (amount.compareTo(minimumBid) < 0) {
            req.setAttribute("error", "Bid must be at least $" + minimumBid.toPlainString());
            forwardToDetail(req, resp, auctionId);
            return;
        }

        Bid bid = new Bid();
        bid.setAuctionId(auctionId);
        bid.setBuyerId(user.getId());
        bid.setAmount(amount);

        if (bidDAO.insert(bid)) {
            auctionDAO.updateCurrentPrice(auctionId, amount);
            resp.sendRedirect(req.getContextPath() + "/product/detail?id=" + auctionId);
        } else {
            req.setAttribute("error", "Failed to place bid. Please try again.");
            forwardToDetail(req, resp, auctionId);
        }
    }

    private void forwardToDetail(HttpServletRequest req, HttpServletResponse resp, int auctionId)
            throws ServletException, IOException {
        Auction auction = auctionDAO.findById(auctionId);
        req.setAttribute("auction", auction);
        req.setAttribute("bids", new BidDAO().findByAuction(auctionId));
        req.getRequestDispatcher("/product/detail.jsp").forward(req, resp);
    }
}
