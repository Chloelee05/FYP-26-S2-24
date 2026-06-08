package com.auction.servlet.admin;

import com.auction.dao.ReportDAO;
import com.auction.model.AccountReport;
import com.auction.model.User;
import com.auction.util.RbacUtil;
import com.auction.util.SecurityUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class AdminReportServlet extends HttpServlet {
    private ReportDAO reportDAO;

    public void AdminAuctionServlet()
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
        
    }
}
