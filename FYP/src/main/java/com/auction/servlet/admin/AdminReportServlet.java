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

/**
 * Lists the account reports filed by users and lets an admin set the status of one of them.
 * Reads and writes through {@link ReportDAO}.
 *
 * <p>Legacy code from the JSP era, and currently dead: no {@code @WebServlet} annotation, no
 * {@code web.xml} entry, no view to forward to, and the session lookup expects a {@code user}
 * object the current login path never sets. Reports are handled in the live system through
 * {@code /api/admin/*} in {@code AdminApiServlet}.</p>
 *
 * <p>Unlike its two dead siblings in this package, it does at least call
 * {@link RbacUtil#isAdmin}, so the intended access rule is visible in the code.</p>
 */
public class AdminReportServlet extends HttpServlet {
    private ReportDAO reportDAO;

    public AdminReportServlet()
    {
        reportDAO = new ReportDAO();
    }

    /** Injection point for a stub DAO in unit tests. */
    public void setReportDAO(ReportDAO reportDAO) {
        this.reportDAO = reportDAO;
    }

    /** Loads every report onto the request as {@code report_list} for a page to render. */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (!requireAdmin(req, resp)) return;
        try {
            List<AccountReport> result = reportDAO.getAllReports();
            req.setAttribute("report_list", result);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Sets the status of one report. Expects {@code moderation_status} and an id sent under the
     * parameter name {@code auction_id}, which the local variable treats as a report id.
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (!requireAdmin(req, resp)) return;

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

    /** Records an error message; the forward is commented out because no view exists. */
    private void errorHandler(HttpServletRequest req, HttpServletResponse resp, String message) throws ServletException, IOException {
        req.setAttribute("Error", message);
        // req.getRequestDispatcher("???").forward(req, resp);
    }

    /** @return false when the caller is unauthenticated or not an admin (response already sent). */
    private boolean requireAdmin(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        if (session == null) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        User user = (User) session.getAttribute("user");
        if (user == null) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        if (!RbacUtil.isAdmin(session)) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        return true;
    }
}
