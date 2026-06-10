package com.auction.servlet.api;

import com.auction.dao.CategoryDAO;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * GET /api/categories — returns all active categories (public endpoint).
 */
@WebServlet("/api/categories")
public class CategoryApiServlet extends ApiBase {

    private final CategoryDAO categoryDAO = new CategoryDAO();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        ok(resp, categoryDAO.listActive());
    }
}
