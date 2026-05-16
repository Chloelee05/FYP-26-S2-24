package com.auction.servlet.admin;

import com.auction.dao.CategoryDAO;
import com.auction.model.admin.Category;
import com.auction.util.InputValidator;
import com.auction.util.SecurityUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Admin Category Management servlet (SCRUM-23).
 *
 * <ul>
 *   <li><b>GET /admin/categories</b> — lists all categories (including soft-deleted) with auction counts.</li>
 *   <li><b>POST /admin/categories</b> — {@code action=CREATE|EDIT|DELETE|RESTORE}.</li>
 * </ul>
 *
 * <p><b>RBAC (SCRUM-219):</b> {@code AdminFilter} ({@code @WebFilter("/admin/*")}) enforces
 * ADMIN-only access before this servlet is invoked. No duplicate RBAC check is needed here.</p>
 *
 * <p><b>Security (SCRUM-280):</b> All user-supplied string inputs are sanitized via
 * {@link SecurityUtil#sanitize(String)} before persistence and before echoing into flash messages.
 * Structural validation uses {@link InputValidator} category helpers. The {@code categoryId}
 * parameter is always parsed as a plain integer so injection via crafted strings is rejected
 * before any DAO call.</p>
 */
@WebServlet("/admin/categories")
public class AdminCategoriesServlet extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger(AdminCategoriesServlet.class.getName());

    private CategoryDAO categoryDAO;

    public AdminCategoriesServlet() {
        this.categoryDAO = new CategoryDAO();
    }

    public AdminCategoriesServlet(CategoryDAO categoryDAO) {
        this.categoryDAO = categoryDAO;
    }

    public void setCategoryDAO(CategoryDAO categoryDAO) {
        this.categoryDAO = categoryDAO;
    }

    // -------------------------------------------------------------------------
    // GET — list
    // -------------------------------------------------------------------------

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        HttpSession session = req.getSession();
        copyFlash(session, req, "adminFlash");
        copyFlash(session, req, "adminFlashError");

        req.setAttribute("categories", categoryDAO.listAll());
        req.setAttribute("adminActiveNav", "categories");
        req.getRequestDispatcher("/WEB-INF/views/admin/categories.jsp").forward(req, resp);
    }

    // -------------------------------------------------------------------------
    // POST — mutations
    // -------------------------------------------------------------------------

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        HttpSession session = req.getSession(false);
        if (session == null) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        String action = req.getParameter("action");
        if (action == null || action.isBlank()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        switch (action.trim().toUpperCase()) {
            case "CREATE":
                handleCreate(req, resp, session);
                break;
            case "EDIT":
                handleEdit(req, resp, session);
                break;
            case "DELETE":
                handleDelete(req, resp, session);
                break;
            case "RESTORE":
                handleRestore(req, resp, session);
                break;
            default:
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
        }
    }

    // -------------------------------------------------------------------------
    // Action handlers
    // -------------------------------------------------------------------------

    private void handleCreate(HttpServletRequest req, HttpServletResponse resp, HttpSession session)
            throws IOException {
        // SCRUM-280: sanitize all user-supplied string inputs before use
        String name = SecurityUtil.sanitize(req.getParameter("name"));
        String description = SecurityUtil.sanitize(req.getParameter("description"));
        int displayOrder = parseDisplayOrder(req.getParameter("displayOrder"));

        String nameErr = InputValidator.getCategoryNameViolation(name);
        if (nameErr != null) {
            setFlash(session, false, null, nameErr);
            resp.sendRedirect(req.getContextPath() + "/admin/categories");
            return;
        }
        String descErr = InputValidator.getCategoryDescriptionViolation(description);
        if (descErr != null) {
            setFlash(session, false, null, descErr);
            resp.sendRedirect(req.getContextPath() + "/admin/categories");
            return;
        }
        // SCRUM-218: duplicate-name check
        if (categoryDAO.nameExists(name)) {
            setFlash(session, false, null, "A category with that name already exists.");
            resp.sendRedirect(req.getContextPath() + "/admin/categories");
            return;
        }

        String slug = resolveUniqueSlug(name, -1);
        int newId = categoryDAO.insert(name, description, displayOrder, slug);
        boolean ok = newId > 0;
        if (ok) {
            LOGGER.info(String.format("Admin created category [id=%d, name=%s].", newId, name));
        }
        setFlash(session, ok, "Category created.", "Could not create category.");
        resp.sendRedirect(req.getContextPath() + "/admin/categories");
    }

    private void handleEdit(HttpServletRequest req, HttpServletResponse resp, HttpSession session)
            throws IOException {
        int id = parseCategoryId(req, resp, session);
        if (id < 0) return;

        String name = SecurityUtil.sanitize(req.getParameter("name"));
        String description = SecurityUtil.sanitize(req.getParameter("description"));
        int displayOrder = parseDisplayOrder(req.getParameter("displayOrder"));

        String nameErr = InputValidator.getCategoryNameViolation(name);
        if (nameErr != null) {
            setFlash(session, false, null, nameErr);
            resp.sendRedirect(req.getContextPath() + "/admin/categories");
            return;
        }
        String descErr = InputValidator.getCategoryDescriptionViolation(description);
        if (descErr != null) {
            setFlash(session, false, null, descErr);
            resp.sendRedirect(req.getContextPath() + "/admin/categories");
            return;
        }
        // SCRUM-218: duplicate-name check excluding self
        if (categoryDAO.nameExistsExcluding(name, id)) {
            setFlash(session, false, null, "A category with that name already exists.");
            resp.sendRedirect(req.getContextPath() + "/admin/categories");
            return;
        }

        Category existing = categoryDAO.findById(id);
        if (existing == null) {
            setFlash(session, false, null, "Category not found.");
            resp.sendRedirect(req.getContextPath() + "/admin/categories");
            return;
        }

        String slug = resolveUniqueSlug(name, id);
        boolean ok = categoryDAO.update(id, name, description, displayOrder, slug);
        if (ok) {
            LOGGER.info(String.format("Admin updated category [id=%d, name=%s].", id, name));
        }
        setFlash(session, ok, "Category updated.", "Could not update category.");
        resp.sendRedirect(req.getContextPath() + "/admin/categories");
    }

    private void handleDelete(HttpServletRequest req, HttpServletResponse resp, HttpSession session)
            throws IOException {
        int id = parseCategoryId(req, resp, session);
        if (id < 0) return;

        Category target = categoryDAO.findById(id);
        if (target == null) {
            setFlash(session, false, null, "Category not found.");
            resp.sendRedirect(req.getContextPath() + "/admin/categories");
            return;
        }

        // SCRUM-217: restrict delete if auctions reference this category
        int auctionCount = categoryDAO.countAuctions(id);
        if (auctionCount > 0) {
            setFlash(session, false, null,
                    "Category \"" + target.getName() + "\" has " + auctionCount
                            + " linked auction(s) and cannot be deleted. Remove or recategorize them first.");
            resp.sendRedirect(req.getContextPath() + "/admin/categories");
            return;
        }

        boolean ok = categoryDAO.softDelete(id);
        if (ok) {
            LOGGER.info(String.format("Admin deactivated category [id=%d, name=%s].", id, target.getName()));
        }
        setFlash(session, ok, "Category deactivated.", "Could not deactivate category.");
        resp.sendRedirect(req.getContextPath() + "/admin/categories");
    }

    private void handleRestore(HttpServletRequest req, HttpServletResponse resp, HttpSession session)
            throws IOException {
        int id = parseCategoryId(req, resp, session);
        if (id < 0) return;

        Category target = categoryDAO.findById(id);
        if (target == null) {
            setFlash(session, false, null, "Category not found.");
            resp.sendRedirect(req.getContextPath() + "/admin/categories");
            return;
        }

        boolean ok = categoryDAO.restore(id);
        if (ok) {
            LOGGER.info(String.format("Admin restored category [id=%d, name=%s].", id, target.getName()));
        }
        setFlash(session, ok, "Category restored.", "Could not restore category.");
        resp.sendRedirect(req.getContextPath() + "/admin/categories");
    }

    // -------------------------------------------------------------------------
    // Slug helpers (SCRUM-218)
    // -------------------------------------------------------------------------

    /**
     * Generates a URL-safe slug from {@code name} (lowercase, spaces → hyphens, non-alphanum stripped).
     * If the slug already exists (for a different category), appends {@code -2}, {@code -3}, etc.
     *
     * @param name        the sanitized category name
     * @param excludeId   the id of the category being edited (pass {@code -1} for new categories)
     */
    public static String generateSlug(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    private String resolveUniqueSlug(String name, int excludeId) {
        String base = generateSlug(name);
        if (base.isEmpty()) base = "category";
        String candidate = base;
        int suffix = 2;
        while (excludeId < 0 ? categoryDAO.slugExists(candidate)
                             : categoryDAO.slugExistsExcluding(candidate, excludeId)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    /**
     * Parses {@code categoryId} from the request. On parse failure, sends 400 and returns {@code -1}.
     */
    private int parseCategoryId(HttpServletRequest req, HttpServletResponse resp, HttpSession session)
            throws IOException {
        String idStr = req.getParameter("categoryId");
        if (idStr == null || idStr.isBlank()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return -1;
        }
        try {
            return Integer.parseInt(idStr.trim());
        } catch (NumberFormatException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return -1;
        }
    }

    private static int parseDisplayOrder(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void setFlash(HttpSession session, boolean ok, String success, String err) {
        session.setAttribute(ok ? "adminFlash" : "adminFlashError", ok ? success : err);
    }

    private static void copyFlash(HttpSession session, HttpServletRequest req, String key) {
        Object v = session.getAttribute(key);
        if (v != null) {
            req.setAttribute(key, v);
            session.removeAttribute(key);
        }
    }
}
