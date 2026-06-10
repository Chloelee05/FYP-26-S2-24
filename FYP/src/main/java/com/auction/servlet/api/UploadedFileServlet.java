package com.auction.servlet.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Serves uploaded files stored in the system temp directory.
 * GET /uploads/auction/{filename}  — auction listing images
 * GET /uploads/profile/{filename}  — user profile photos
 */
@WebServlet("/uploads/*")
public class UploadedFileServlet extends HttpServlet {

    public static final String BASE_DIR =
            System.getProperty("java.io.tmpdir") + File.separator + "auction-uploads";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.equals("/")) {
            resp.sendError(404);
            return;
        }

        File file = new File(BASE_DIR + File.separator + pathInfo.replace("/", File.separator));

        // Prevent path traversal
        if (!file.getCanonicalPath().startsWith(new File(BASE_DIR).getCanonicalPath())) {
            resp.sendError(403);
            return;
        }

        if (!file.exists() || !file.isFile()) {
            resp.sendError(404);
            return;
        }

        String mimeType = getServletContext().getMimeType(file.getName());
        if (mimeType == null) mimeType = "application/octet-stream";

        resp.setContentType(mimeType);
        resp.setContentLengthLong(file.length());
        Files.copy(file.toPath(), resp.getOutputStream());
    }
}
