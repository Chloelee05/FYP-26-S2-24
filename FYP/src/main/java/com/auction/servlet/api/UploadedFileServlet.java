package com.auction.servlet.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Serves uploaded files from {@link #BASE_DIR}.
 * <p>On Render, set {@code AUCTION_UPLOAD_DIR} (e.g. {@code /data/auction-uploads})
 * to a persistent disk mount; locally falls back to {@code java.io.tmpdir}.</p>
 * GET /uploads/auction/{filename}  — auction listing images
 * GET /uploads/profile/{filename}  — user profile photos
 *
 * <p>Static file reads, so no authentication: the URLs are already embedded in public listing
 * and profile pages. The one security control is the canonical-path check in
 * {@link #doGet}, which stops a crafted {@code ../} path from reading files outside the
 * upload directory. This extends {@link HttpServlet} directly rather than {@code ApiBase}
 * because it streams bytes instead of JSON.</p>
 */
@WebServlet("/uploads/*")
public class UploadedFileServlet extends HttpServlet {

    public static final String BASE_DIR = resolveUploadBaseDir();

    /**
     * Picks the upload root once at class load. Prefers {@code AUCTION_UPLOAD_DIR} because on
     * Render the container filesystem is wiped on redeploy, so uploads must live on a mounted
     * disk; the temp directory fallback is only good enough for local development.
     */
    private static String resolveUploadBaseDir() {
        String fromEnv = System.getenv("AUCTION_UPLOAD_DIR");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        return System.getProperty("java.io.tmpdir") + File.separator + "auction-uploads";
    }

    /**
     * Serves GET /uploads/*. The path after {@code /uploads} names the file relative to
     * {@link #BASE_DIR}. Answers 404 for a missing file, 403 for a path that escapes the
     * upload root, otherwise streams the bytes with a guessed content type.
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.equals("/")) {
            resp.sendError(404);
            return;
        }

        File file = new File(BASE_DIR + File.separator + pathInfo.replace("/", File.separator));

        // Prevent path traversal. Comparing canonical paths resolves any "../" segments and
        // symlinks first, so a request like /uploads/../../etc/passwd is rejected here.
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
