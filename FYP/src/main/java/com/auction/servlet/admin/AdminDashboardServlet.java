package com.auction.servlet.admin;

import com.auction.dao.AuctionDAO;
import com.auction.dao.UserDAO;
import com.auction.model.admin.AdminListingRow;
import com.auction.model.admin.AdminUserSummary;
import com.auction.model.admin.DashboardActivityItem;
import com.auction.model.admin.DashboardMetrics;
import com.auction.util.RelativeTime;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@WebServlet("/admin/dashboard")
public class AdminDashboardServlet extends HttpServlet {

    private final UserDAO userDAO;
    private final AuctionDAO auctionDAO;

    public AdminDashboardServlet() {
        this.userDAO = new UserDAO();
        this.auctionDAO = new AuctionDAO();
    }

    AdminDashboardServlet(UserDAO userDAO, AuctionDAO auctionDAO) {
        this.userDAO = userDAO;
        this.auctionDAO = auctionDAO;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        ZoneId zone = ZoneId.systemDefault();
        Instant now = Instant.now();

        DashboardMetrics metrics = new DashboardMetrics(
                userDAO.countNonDeletedUsers(),
                userDAO.countActiveUsers(),
                auctionDAO.countListingsModerationActive(),
                auctionDAO.countListingsTotal(),
                auctionDAO.countListingsFlagged(),
                auctionDAO.sumWinningBidDollars(),
                "+ 12.5% this month");

        List<AdminUserSummary> allUsers = userDAO.listUsersForAdminTable();
        List<AdminListingRow> allListings = auctionDAO.listListingsForModeration();
        List<AdminUserSummary> previewUsers = allUsers.size() > 5 ? allUsers.subList(0, 5) : allUsers;
        List<AdminListingRow> previewListings = allListings.size() > 5 ? allListings.subList(0, 5) : allListings;

        req.setAttribute("metrics", metrics);
        req.setAttribute("activities", buildActivity(now, zone));
        req.setAttribute("previewUsers", previewUsers);
        req.setAttribute("previewListings", previewListings);
        req.setAttribute("adminActiveNav", "overview");
        req.getRequestDispatcher("/WEB-INF/views/admin/dashboard.jsp").forward(req, resp);
    }

    private List<DashboardActivityItem> buildActivity(Instant now, ZoneId zone) {
        List<SortableActivity> buf = new ArrayList<>();
        for (UserDAO.NamedInstantEvent e : userDAO.recentRegistrations(6)) {
            buf.add(new SortableActivity(
                    e.getAt(),
                    "success",
                    "New user " + e.getName() + " registered",
                    RelativeTime.format(e.getAt(), now, zone)));
        }
        for (AuctionDAO.FlaggedTitleEvent e : auctionDAO.recentFlaggedListings(6)) {
            buf.add(new SortableActivity(
                    e.getAt(),
                    "warning",
                    "Listing '" + e.getTitle() + "' was flagged",
                    RelativeTime.format(e.getAt(), now, zone)));
        }
        for (UserDAO.NamedInstantEvent e : userDAO.recentSuspensions(6)) {
            buf.add(new SortableActivity(
                    e.getAt(),
                    "danger",
                    "User " + e.getName() + " was banned",
                    RelativeTime.format(e.getAt(), now, zone)));
        }
        buf.sort(Comparator.comparing(SortableActivity::getAt).reversed());
        List<DashboardActivityItem> out = new ArrayList<>();
        int n = Math.min(12, buf.size());
        for (int i = 0; i < n; i++) {
            SortableActivity a = buf.get(i);
            out.add(new DashboardActivityItem(a.getSeverity(), a.getMessage(), a.getTimeLabel()));
        }
        if (out.isEmpty()) {
            out.add(new DashboardActivityItem(
                    "secondary", "No recent activity yet.", ""));
        }
        return out;
    }

    private static final class SortableActivity {
        private final Instant at;
        private final String severity;
        private final String message;
        private final String timeLabel;

        private SortableActivity(Instant at, String severity, String message, String timeLabel) {
            this.at = at;
            this.severity = severity;
            this.message = message;
            this.timeLabel = timeLabel;
        }

        public Instant getAt() {
            return at;
        }

        public String getSeverity() {
            return severity;
        }

        public String getMessage() {
            return message;
        }

        public String getTimeLabel() {
            return timeLabel;
        }
    }
}
