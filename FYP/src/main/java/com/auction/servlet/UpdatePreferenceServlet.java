package com.auction.servlet;

import com.auction.dao.NotificationDAO;
import com.auction.model.User;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

public class UpdatePreferenceServlet extends HttpServlet {

    private NotificationDAO notificationDAO;

    public UpdatePreferenceServlet()
    {
        notificationDAO = new NotificationDAO();
    }

    public void setNotificationDAO(NotificationDAO notificationDAO) {
        this.notificationDAO = notificationDAO;
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

        String temp_out_bided = req.getParameter("out_bided");
        String temp_ending_soon = req.getParameter("ending_soon");
        String temp_won_auction = req.getParameter("won_auction");

        temp_out_bided = (temp_out_bided == null) ? null : temp_out_bided.trim();
        temp_ending_soon = (temp_ending_soon == null) ? null : temp_ending_soon.trim();
        temp_won_auction = (temp_won_auction == null) ? null : temp_won_auction.trim();

        boolean out_bided = Boolean.parseBoolean(temp_out_bided);
        boolean ending_soon = Boolean.parseBoolean(temp_ending_soon);
        boolean won_auction = Boolean.parseBoolean(temp_won_auction);
        int user_id = user.getId();

        try {
            notificationDAO.saveUserPreferences(user_id, out_bided, ending_soon, won_auction);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
