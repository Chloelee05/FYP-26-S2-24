package com.auction.servlet.admin;

import com.auction.dao.ReportDAO;
import com.auction.model.AccountReport;
import com.auction.model.User;
import com.auction.util.RbacUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.List;

public class AdminReportServlet extends HttpServlet {
    private ReportDAO reportDAO;

    public AdminReportServlet()
    {
        reportDAO = new ReportDAO();
    }

    public void setReportDAO(ReportDAO reportDAO) {
        this.reportDAO = reportDAO;
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
        if(!RbacUtil.isAdmin(session))
        {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        try {
            List<AccountReport> result;
            result = reportDAO.getAllReports();
            req.setAttribute("report_list", result);
            //redirect
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
        if(!RbacUtil.isAdmin(session))
        {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        String moderationStatus = req.getParameter("moderation_status");
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
        Long report_id;
        try {
            report_id = Long.parseLong(temp);
        } catch (NumberFormatException e) {
            errorHandler(req, resp, "Invalid auction id");
            return;
        }
        try {
            if (reportDAO.setReportStatus(report_id, moderationStatus)) {
                //success message
                // req.getRequestDispatcher("???").forward(req, resp);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void errorHandler(HttpServletRequest req, HttpServletResponse resp, String message) throws ServletException, IOException {
        req.setAttribute("Error", message);
        // req.getRequestDispatcher("???").forward(req, resp);
    }
}
