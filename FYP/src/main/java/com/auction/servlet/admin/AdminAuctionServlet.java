package com.auction.servlet.admin;

import com.auction.dao.AuctionDAO;
import com.auction.model.User;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

public class AdminAuctionServlet extends HttpServlet{

        private AuctionDAO auctionDAO;

        public AdminAuctionServlet() {
            auctionDAO = new AuctionDAO();
        }

    public void setAuctionDAO(AuctionDAO auctionDAO) {
        this.auctionDAO = auctionDAO;
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
            User user = (User) session.getAttribute("user");
            if (user == null) {
                resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }

            String moderationStatus = req.getParameter("auction_status");
            moderationStatus = (moderationStatus == null) ? null : moderationStatus.trim().toLowerCase();
            String temp = req.getParameter("auction_id");
            if(moderationStatus == null || moderationStatus.isBlank())
            {
                errorHandler(req, resp, "Invalid moderation status:");
                return;
            }

            temp = (temp == null) ? null : temp.trim();
            if(temp == null || temp.isBlank())
            {
                errorHandler(req, resp, "Invalid auction_id:");
                return;
            }
            Long auction_id;
            try {
                auction_id = Long.parseLong(temp);
            } catch (NumberFormatException e) {
                errorHandler(req, resp, "Invalid auction id");
                return;
            }

            try {
                if(auctionDAO.updateAuctionState(auction_id, moderationStatus))
                {
                    //success message
                    // req.getRequestDispatcher("???").forward(req, resp);
                }
                else{
                    errorHandler(req, resp, "Error with database. Please try again");
                    return;
                }
            } catch (Exception e) {
                getServletContext().log("Update moderation state error", e);
                errorHandler(req, resp, "Could not reach the database");
            }
        }

    private void errorHandler(HttpServletRequest req, HttpServletResponse resp, String message) throws ServletException, IOException {
        req.setAttribute("Error", message);
        // req.getRequestDispatcher("???").forward(req, resp);
    }
}
