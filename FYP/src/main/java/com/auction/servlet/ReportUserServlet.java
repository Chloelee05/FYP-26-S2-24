package com.auction.servlet;

import com.auction.dao.ReportDAO;
import com.auction.dao.UserDAO;
import com.auction.model.AccountReport;
import com.auction.model.User;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.time.Instant;

public class ReportUserServlet extends HttpServlet {

    private ReportDAO reportDAO;

    public ReportUserServlet() {
        reportDAO = new ReportDAO();
    }

    public void setDAO(ReportDAO reportDAO) {
        this.reportDAO = reportDAO;
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
        Long user_id = (long) user.getId();
        String target_id_param = req.getParameter("target_id");
        String reason = req.getParameter("reason");
        String comment = req.getParameter("comment");
        reason = (reason == null) ? null : reason.trim();
        comment = (comment == null) ? null : comment.trim();
        Instant created_at = Instant.now();

        if (target_id_param == null || target_id_param.isBlank()) {
            errorHandler(req, resp, "All fields must be filled up", null, reason, comment);
            return;
        }
        Long target_id;
        try {
            target_id = Long.parseLong(target_id_param);
        } catch (NumberFormatException e) {
            errorHandler(req, resp, "Invalid target user", null, reason, comment);
            return;
        }

        if(user_id.equals(target_id))
        {
            errorHandler(req, resp, "You cannot report yourself", target_id, reason, comment);
            return;
        }

        AccountReport accountReport = new AccountReport(user_id, target_id, reason, comment, created_at);
        try {
            if (reportDAO.reportUser(accountReport)) {
                //resp.sendRedirect(req.getContextPath() + "???");
                req.setAttribute("success", "Success");
            } else {
                errorHandler(req, resp, "Failed to submit report", target_id, reason, comment);
            }
        } catch (Exception e) {
            //getServletContext().log("Report user error", e);
            errorHandler(req, resp, "Could not reach the database", target_id, reason, comment);
        }
    }

    private void errorHandler(HttpServletRequest req, HttpServletResponse resp, String message,
                              Long target_id, String reason, String comment) throws ServletException, IOException {
        req.setAttribute("Error", message);
        stickyForm(req, target_id, reason, comment);
        // req.getRequestDispatcher("???").forward(req, resp);
    }

    private void stickyForm(HttpServletRequest req, Long target_id, String reason, String comment) {
        req.setAttribute("target_id", target_id);
        req.setAttribute("reason", reason);
        req.setAttribute("comment", comment);
    }
}
