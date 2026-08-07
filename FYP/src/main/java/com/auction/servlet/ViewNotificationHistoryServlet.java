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

/**
 * Loads the signed-in user's past notifications and puts them on the request as
 * {@code notifications} for a page to render.
 *
 * <p>Legacy code from the JSP era, and currently dead. It has no {@code @WebServlet} mapping,
 * it never forwards to a view, and it expects a {@code user} object in the session that the
 * current login path does not set. The live feature is {@code NotificationApiServlet} on
 * {@code /api/notifications}, which the SPA calls.</p>
 */
public class ViewNotificationHistoryServlet extends HttpServlet {

    private NotificationDAO notificationDAO;

    public ViewNotificationHistoryServlet()
    {
        notificationDAO = new NotificationDAO();
    }

    /** Injection point for a stub DAO in unit tests. */
    public void setNotificationDAO(NotificationDAO notificationDAO) {
        this.notificationDAO = notificationDAO;
    }

    /**
     * Fetches the notification history for the session's user. The id comes from the session
     * only, so one user cannot request another's notifications.
     */
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
            List<Notification> result = notificationDAO.notificationHistory(user_id);
            req.setAttribute("notifications", result);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
