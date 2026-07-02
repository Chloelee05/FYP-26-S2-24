package com.auction.servlet;

import com.auction.dao.NotificationDAO;
import com.auction.model.Notification;
import com.auction.model.User;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ViewNotificationHistoryServlet extends HttpServlet {

    private NotificationDAO notificationDAO;

    public ViewNotificationHistoryServlet()
    {
        notificationDAO = new NotificationDAO();
    }

    public void setNotificationDAO(NotificationDAO notificationDAO) {
        this.notificationDAO = notificationDAO;
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

        int user_id = user.getId();
        try{
            List<Notification> result = new ArrayList<>();
            result = notificationDAO.notificationHistory(user_id);
            for(Notification each: result)
            {
                req.setAttribute("id", each.getId());
                req.setAttribute("message", each.getMessage());
                req.setAttribute("link", each.getLink());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
