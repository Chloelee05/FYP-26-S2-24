package com.auction.servlet.api;

import com.auction.dao.CategoryDAO;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * GET /api/categories — returns all active categories (public endpoint).
 *
 * <p>Public read path, so it sits outside AuthFilter and guests can call it. The React SPA
 * uses the result to populate the category filter on browse and the category dropdown on
 * the sell form, which is why inactive categories are excluded: they must not become
 * selectable for new listings. Reads the {@code categories} table through {@link CategoryDAO}.</p>
 */
@WebServlet("/api/categories")
public class CategoryApiServlet extends ApiBase {

    private CategoryDAO categoryDAO;

    public CategoryApiServlet() {
        this.categoryDAO = new CategoryDAO();
    }

    /** Test hook: lets a unit test swap in a stubbed DAO, since the servlet builds its own by default. */
    public void setCategoryDAO(CategoryDAO categoryDAO) { this.categoryDAO = categoryDAO; }

    /** Serves GET /api/categories. Takes no parameters and returns the active category list as a JSON array. */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        ok(resp, categoryDAO.listActive());
    }
}
