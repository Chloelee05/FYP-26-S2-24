import com.auction.dao.CategoryDAO;
import com.auction.model.admin.Category;
import com.auction.servlet.admin.AdminCategoriesServlet;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AdminCategoriesServlet} (SCRUM-23).
 *
 * <p><b>RBAC enforcement (SCRUM-219):</b> The {@code /admin/categories} endpoint is protected
 * by {@code AdminFilter} ({@code @WebFilter("/admin/*")}), which rejects unauthenticated
 * requests and non-ADMIN roles before the servlet is invoked. Full RBAC coverage for
 * BUYER / SELLER / unauthenticated callers is provided in {@link TestAdminFilter}.</p>
 *
 * <p>Tests here focus on the servlet's own logic: input validation, DAO interactions,
 * flash messages, and redirect/forward behaviour (SCRUM-220).</p>
 */
public class TestAdminCategoriesServlet extends Mockito {

    private static class Wrapper extends AdminCategoriesServlet {
        Wrapper(CategoryDAO dao) { super(dao); }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException { super.doGet(req, resp); }

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException { super.doPost(req, resp); }
    }

    private CategoryDAO mockDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private HttpSession session;

    @BeforeEach
    void setUp() {
        mockDAO = mock(CategoryDAO.class);
        servlet = new Wrapper(mockDAO);
        req = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
        session = mock(HttpSession.class);
        when(req.getSession(false)).thenReturn(session);
        when(req.getSession()).thenReturn(session);
        when(req.getContextPath()).thenReturn("");
    }

    // =========================================================================
    // GET — list (SCRUM-220 linkage)
    // =========================================================================

    @Test
    @DisplayName("GET forwards to categories.jsp with category list (SCRUM-220)")
    void testGetListsCategories() throws Exception {
        List<Category> cats = Collections.emptyList();
        when(mockDAO.listAll()).thenReturn(cats);
        RequestDispatcher rd = mock(RequestDispatcher.class);
        when(req.getRequestDispatcher("/WEB-INF/views/admin/categories.jsp")).thenReturn(rd);

        servlet.doGet(req, resp);

        verify(mockDAO).listAll();
        verify(req).setAttribute("categories", cats);
        verify(req).setAttribute("adminActiveNav", "categories");
        verify(rd).forward(req, resp);
    }

    // =========================================================================
    // POST — input guards
    // =========================================================================

    @Test
    @DisplayName("Null session returns 400")
    void testNullSession() throws Exception {
        when(req.getSession(false)).thenReturn(null);
        when(req.getParameter("action")).thenReturn("CREATE");
        servlet.doPost(req, resp);
        verify(resp).sendError(HttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    @DisplayName("Missing action returns 400")
    void testMissingAction() throws Exception {
        when(req.getParameter("action")).thenReturn(null);
        servlet.doPost(req, resp);
        verify(resp).sendError(HttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    @DisplayName("Unknown action returns 400")
    void testUnknownAction() throws Exception {
        when(req.getParameter("action")).thenReturn("HACK");
        servlet.doPost(req, resp);
        verify(resp).sendError(HttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    @DisplayName("Non-numeric categoryId returns 400 (SCRUM-280 IDOR guard)")
    void testNonNumericCategoryId() throws Exception {
        when(req.getParameter("action")).thenReturn("EDIT");
        when(req.getParameter("categoryId")).thenReturn("abc");
        servlet.doPost(req, resp);
        verify(resp).sendError(HttpServletResponse.SC_BAD_REQUEST);
        verify(mockDAO, never()).nameExistsExcluding(any(), anyInt());
    }

    // =========================================================================
    // POST CREATE (SCRUM-220)
    // =========================================================================

    @Test
    @DisplayName("Create with valid name succeeds (SCRUM-217/220)")
    void testCreateSuccess() throws Exception {
        when(req.getParameter("action")).thenReturn("CREATE");
        when(req.getParameter("name")).thenReturn("Electronics");
        when(req.getParameter("description")).thenReturn("All electronic goods");
        when(req.getParameter("displayOrder")).thenReturn("10");
        when(mockDAO.nameExists("Electronics")).thenReturn(false);
        when(mockDAO.slugExists("electronics")).thenReturn(false);
        when(mockDAO.insert("Electronics", "All electronic goods", 10, "electronics")).thenReturn(1);

        servlet.doPost(req, resp);

        verify(mockDAO).insert("Electronics", "All electronic goods", 10, "electronics");
        verify(session).setAttribute(eq("adminFlash"), eq("Category created."));
        verify(resp).sendRedirect("/admin/categories");
    }

    @Test
    @DisplayName("Create with blank name returns error flash (SCRUM-218 validation)")
    void testCreateBlankName() throws Exception {
        when(req.getParameter("action")).thenReturn("CREATE");
        when(req.getParameter("name")).thenReturn("   ");
        when(req.getParameter("description")).thenReturn("");

        servlet.doPost(req, resp);

        verify(mockDAO, never()).insert(any(), any(), anyInt(), any());
        verify(session).setAttribute(eq("adminFlashError"), eq("Category name is required."));
        verify(resp).sendRedirect("/admin/categories");
    }

    @Test
    @DisplayName("Create with name exceeding 100 chars returns error flash (SCRUM-218)")
    void testCreateNameTooLong() throws Exception {
        String longName = "A".repeat(101);
        when(req.getParameter("action")).thenReturn("CREATE");
        when(req.getParameter("name")).thenReturn(longName);
        when(req.getParameter("description")).thenReturn(null);

        servlet.doPost(req, resp);

        verify(mockDAO, never()).insert(any(), any(), anyInt(), any());
        verify(session).setAttribute(eq("adminFlashError"),
                eq("Category name must be at most 100 characters."));
    }

    @Test
    @DisplayName("Create with duplicate name returns error flash (SCRUM-218 duplicate check)")
    void testCreateDuplicateName() throws Exception {
        when(req.getParameter("action")).thenReturn("CREATE");
        when(req.getParameter("name")).thenReturn("Electronics");
        when(req.getParameter("description")).thenReturn(null);
        when(mockDAO.nameExists("Electronics")).thenReturn(true);

        servlet.doPost(req, resp);

        verify(mockDAO, never()).insert(any(), any(), anyInt(), any());
        verify(session).setAttribute(eq("adminFlashError"),
                eq("A category with that name already exists."));
        verify(resp).sendRedirect("/admin/categories");
    }

    @Test
    @DisplayName("Create with description exceeding 500 chars returns error flash")
    void testCreateDescriptionTooLong() throws Exception {
        when(req.getParameter("action")).thenReturn("CREATE");
        when(req.getParameter("name")).thenReturn("Electronics");
        when(req.getParameter("description")).thenReturn("D".repeat(501));
        when(mockDAO.nameExists("Electronics")).thenReturn(false);

        servlet.doPost(req, resp);

        verify(mockDAO, never()).insert(any(), any(), anyInt(), any());
        verify(session).setAttribute(eq("adminFlashError"),
                eq("Category description must be at most 500 characters."));
    }

    // =========================================================================
    // POST EDIT (SCRUM-220)
    // =========================================================================

    @Test
    @DisplayName("Edit with valid data succeeds (SCRUM-220)")
    void testEditSuccess() throws Exception {
        Category existing = new Category(1, "Electronics", null, 10, "electronics",
                false, null, 5);
        when(mockDAO.findById(1)).thenReturn(existing);
        when(mockDAO.nameExistsExcluding("Electronics Updated", 1)).thenReturn(false);
        when(mockDAO.slugExistsExcluding("electronics-updated", 1)).thenReturn(false);
        when(mockDAO.update(1, "Electronics Updated", null, 20, "electronics-updated")).thenReturn(true);

        when(req.getParameter("action")).thenReturn("EDIT");
        when(req.getParameter("categoryId")).thenReturn("1");
        when(req.getParameter("name")).thenReturn("Electronics Updated");
        when(req.getParameter("description")).thenReturn(null);
        when(req.getParameter("displayOrder")).thenReturn("20");

        servlet.doPost(req, resp);

        verify(mockDAO).update(1, "Electronics Updated", null, 20, "electronics-updated");
        verify(session).setAttribute(eq("adminFlash"), eq("Category updated."));
        verify(resp).sendRedirect("/admin/categories");
    }

    @Test
    @DisplayName("Edit with duplicate name (another category) returns error flash (SCRUM-218)")
    void testEditDuplicateName() throws Exception {
        when(req.getParameter("action")).thenReturn("EDIT");
        when(req.getParameter("categoryId")).thenReturn("2");
        when(req.getParameter("name")).thenReturn("Electronics");
        when(req.getParameter("description")).thenReturn(null);
        when(req.getParameter("displayOrder")).thenReturn("0");
        when(mockDAO.nameExistsExcluding("Electronics", 2)).thenReturn(true);

        servlet.doPost(req, resp);

        verify(mockDAO, never()).update(anyInt(), any(), any(), anyInt(), any());
        verify(session).setAttribute(eq("adminFlashError"),
                eq("A category with that name already exists."));
    }

    @Test
    @DisplayName("Edit non-existent category returns error flash")
    void testEditNotFound() throws Exception {
        when(req.getParameter("action")).thenReturn("EDIT");
        when(req.getParameter("categoryId")).thenReturn("999");
        when(req.getParameter("name")).thenReturn("Sports");
        when(req.getParameter("description")).thenReturn(null);
        when(req.getParameter("displayOrder")).thenReturn("0");
        when(mockDAO.nameExistsExcluding("Sports", 999)).thenReturn(false);
        when(mockDAO.slugExistsExcluding("sports", 999)).thenReturn(false);
        when(mockDAO.findById(999)).thenReturn(null);

        servlet.doPost(req, resp);

        verify(mockDAO, never()).update(anyInt(), any(), any(), anyInt(), any());
        verify(session).setAttribute(eq("adminFlashError"), eq("Category not found."));
    }

    // =========================================================================
    // POST DELETE — restrict policy (SCRUM-217)
    // =========================================================================

    @Test
    @DisplayName("Delete category with no auctions succeeds (soft delete, SCRUM-217)")
    void testDeleteNoAuctions() throws Exception {
        Category target = new Category(3, "Fashion", null, 20, "fashion", false, null, 0);
        when(mockDAO.findById(3)).thenReturn(target);
        when(mockDAO.countAuctions(3)).thenReturn(0);
        when(mockDAO.softDelete(3)).thenReturn(true);

        when(req.getParameter("action")).thenReturn("DELETE");
        when(req.getParameter("categoryId")).thenReturn("3");

        servlet.doPost(req, resp);

        verify(mockDAO).softDelete(3);
        verify(session).setAttribute(eq("adminFlash"), eq("Category deactivated."));
        verify(resp).sendRedirect("/admin/categories");
    }

    @Test
    @DisplayName("Delete category that has auctions is RESTRICTED (SCRUM-217)")
    void testDeleteRestrictedByAuctions() throws Exception {
        Category target = new Category(1, "Electronics", null, 10, "electronics", false, null, 5);
        when(mockDAO.findById(1)).thenReturn(target);
        when(mockDAO.countAuctions(1)).thenReturn(5);

        when(req.getParameter("action")).thenReturn("DELETE");
        when(req.getParameter("categoryId")).thenReturn("1");

        servlet.doPost(req, resp);

        verify(mockDAO, never()).softDelete(anyInt());
        verify(session).setAttribute(eq("adminFlashError"),
                contains("5 linked auction(s)"));
        verify(resp).sendRedirect("/admin/categories");
    }

    @Test
    @DisplayName("Delete non-existent category returns error flash")
    void testDeleteNotFound() throws Exception {
        when(mockDAO.findById(99)).thenReturn(null);
        when(req.getParameter("action")).thenReturn("DELETE");
        when(req.getParameter("categoryId")).thenReturn("99");

        servlet.doPost(req, resp);

        verify(mockDAO, never()).softDelete(anyInt());
        verify(session).setAttribute(eq("adminFlashError"), eq("Category not found."));
    }

    // =========================================================================
    // POST RESTORE (SCRUM-220)
    // =========================================================================

    @Test
    @DisplayName("Restore a deactivated category succeeds (SCRUM-220)")
    void testRestoreSuccess() throws Exception {
        Category target = new Category(3, "Fashion", null, 20, "fashion", true, null, 0);
        when(mockDAO.findById(3)).thenReturn(target);
        when(mockDAO.restore(3)).thenReturn(true);

        when(req.getParameter("action")).thenReturn("RESTORE");
        when(req.getParameter("categoryId")).thenReturn("3");

        servlet.doPost(req, resp);

        verify(mockDAO).restore(3);
        verify(session).setAttribute(eq("adminFlash"), eq("Category restored."));
        verify(resp).sendRedirect("/admin/categories");
    }

    @Test
    @DisplayName("Restore non-existent category returns error flash")
    void testRestoreNotFound() throws Exception {
        when(mockDAO.findById(77)).thenReturn(null);
        when(req.getParameter("action")).thenReturn("RESTORE");
        when(req.getParameter("categoryId")).thenReturn("77");

        servlet.doPost(req, resp);

        verify(mockDAO, never()).restore(anyInt());
        verify(session).setAttribute(eq("adminFlashError"), eq("Category not found."));
    }

    // =========================================================================
    // Slug generation helper (SCRUM-218)
    // =========================================================================

    @Test
    @DisplayName("generateSlug converts name to URL-safe lowercase slug")
    void testGenerateSlug() {
        assertEquals("electronics", AdminCategoriesServlet.generateSlug("Electronics"));
        assertEquals("home-garden", AdminCategoriesServlet.generateSlug("Home & Garden"));
        assertEquals("sports-gear", AdminCategoriesServlet.generateSlug("  Sports Gear  "));
        assertEquals("art-collectibles", AdminCategoriesServlet.generateSlug("Art / Collectibles"));
    }
}
