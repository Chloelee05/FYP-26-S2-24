package com.auction.servlet.api;

import com.auction.dao.CategoryDAO;
import com.auction.model.admin.Category;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Category picture upload, admin only.
 *
 * <p>POST /api/admin/category-image?categoryId=N — raw image body, saved to
 * {@code {uploads}/category/} with {@code categories.image_url} pointed at it.
 * DELETE /api/admin/category-image?categoryId=N — clears the picture and removes the file.</p>
 *
 * <p>An exact servlet mapping outranks the {@code /api/admin/*} prefix that
 * {@link AdminApiServlet} claims, so this path reaches this servlet.</p>
 *
 * <p>The picture is optional everywhere it is consumed: the home page falls back to a
 * built-in icon matched to the category name, so clearing one is never destructive.</p>
 */
@WebServlet("/api/admin/category-image")
public class CategoryImageApiServlet extends ApiBase {

    private static final Set<String> ALLOWED_TYPES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("image/jpeg", "image/png", "image/gif", "image/webp")));

    private static final String UPLOAD_SUBDIR = "category";
    private static final String UPLOAD_DIR = UploadedFileServlet.BASE_DIR + File.separator + UPLOAD_SUBDIR;
    private static final String URL_PREFIX = "/uploads/" + UPLOAD_SUBDIR + "/";

    private static final long MAX_UPLOAD_BYTES = 5 * 1024 * 1024L;

    private final CategoryDAO categoryDAO = new CategoryDAO();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireRole(req, resp, "ADMIN")) return;

        Category category = resolveCategory(req, resp);
        if (category == null) return;

        String contentType = req.getContentType();
        String mime = (contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase());
        if (!ALLOWED_TYPES.contains(mime)) {
            badRequest(resp, "Only JPEG, PNG, GIF, and WebP images are allowed.");
            return;
        }
        if (req.getContentLengthLong() > MAX_UPLOAD_BYTES) {
            badRequest(resp, "File too large (max 5 MB).");
            return;
        }

        File uploadDir = new File(UPLOAD_DIR);
        if (!uploadDir.exists()) uploadDir.mkdirs();

        String filename = UUID.randomUUID() + extensionFor(mime);
        File dest = new File(uploadDir, filename);
        try (InputStream in = req.getInputStream()) {
            Files.copy(in, Paths.get(dest.getAbsolutePath()), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            serverError(resp, "Failed to save the picture.");
            return;
        }

        String imageUrl = URL_PREFIX + filename;
        if (!categoryDAO.updateImageUrl(category.getId(), imageUrl)) {
            dest.delete();
            serverError(resp, "Failed to update the category picture.");
            return;
        }

        deleteUploadedFile(category.getImageUrl());
        ok(resp, Collections.singletonMap("imageUrl", imageUrl));
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireRole(req, resp, "ADMIN")) return;

        Category category = resolveCategory(req, resp);
        if (category == null) return;

        if (!categoryDAO.updateImageUrl(category.getId(), null)) {
            serverError(resp, "Failed to clear the category picture.");
            return;
        }
        deleteUploadedFile(category.getImageUrl());
        okMsg(resp, "Category picture removed.");
    }

    /** Parses {@code categoryId}, writing the error response and returning null when unusable. */
    private Category resolveCategory(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String raw = param(req, "categoryId");
        if (raw == null) {
            badRequest(resp, "categoryId is required.");
            return null;
        }
        int id;
        try {
            id = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            badRequest(resp, "Invalid category ID.");
            return null;
        }
        Category category = categoryDAO.findById(id);
        if (category == null) {
            error(resp, 404, "Category not found.");
        }
        return category;
    }

    /** Removes a previously uploaded file, ignoring anything we did not write ourselves. */
    private void deleteUploadedFile(String url) {
        if (url == null || !url.startsWith(URL_PREFIX)) return;
        try {
            new File(UPLOAD_DIR, url.substring(url.lastIndexOf('/') + 1)).delete();
        } catch (Exception ignored) {
            // A stale file left on disk is harmless; never fail the request over it.
        }
    }

    private static String extensionFor(String mime) {
        switch (mime) {
            case "image/jpeg": return ".jpg";
            case "image/gif":  return ".gif";
            case "image/webp": return ".webp";
            default:           return ".png";
        }
    }
}
