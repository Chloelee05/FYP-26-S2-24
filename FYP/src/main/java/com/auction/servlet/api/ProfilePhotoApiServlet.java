package com.auction.servlet.api;

import com.auction.dao.UserDAO;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/**
 * POST /api/account/upload-photo — multipart file upload for profile photo.
 * Saves to {webapp}/uploads/profile/ and updates profile_image_url in DB.
 */
@WebServlet("/api/account/upload-photo")
public class ProfilePhotoApiServlet extends ApiBase {

    private static final Set<String> ALLOWED_TYPES = Collections.unmodifiableSet(
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "image/jpeg", "image/png", "image/gif", "image/webp")));

    private static final String UPLOAD_SUBDIR = "profile";
    private static final String UPLOAD_DIR = UploadedFileServlet.BASE_DIR + File.separator + UPLOAD_SUBDIR;

    private final UserDAO userDAO = new UserDAO();

    private static final long MAX_UPLOAD_BYTES = 5 * 1024 * 1024L;

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        int userId = sessionUserId(req);

        String contentType = req.getContentType();
        if (contentType == null) contentType = "";
        String mime = contentType.split(";")[0].trim().toLowerCase();
        if (!ALLOWED_TYPES.contains(mime)) {
            badRequest(resp, "Only JPEG, PNG, GIF, and WebP images are allowed.");
            return;
        }

        long len = req.getContentLengthLong();
        if (len > MAX_UPLOAD_BYTES) {
            badRequest(resp, "File too large (max 5 MB).");
            return;
        }

        String ext = extensionFor(mime);
        String filename = UUID.randomUUID() + ext;

        File uploadDir = new File(UPLOAD_DIR);
        if (!uploadDir.exists()) uploadDir.mkdirs();

        File dest = new File(uploadDir, filename);
        try (InputStream in = req.getInputStream()) {
            Files.copy(in, Paths.get(dest.getAbsolutePath()), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            serverError(resp, "Failed to save uploaded file.");
            return;
        }

        String imageUrl = "/uploads/" + UPLOAD_SUBDIR + "/" + filename;

        // Delete previous profile photo if it was also an uploaded file
        try {
            com.auction.model.User current = userDAO.getUserById(userId);
            if (current != null) {
                String old = current.getProfileImageUrl();
                if (old != null && old.startsWith("/uploads/" + UPLOAD_SUBDIR + "/")) {
                    new File(UPLOAD_DIR, old.substring(old.lastIndexOf('/') + 1)).delete();
                }
            }
        } catch (Exception ignored) {}

        boolean updated = userDAO.updateProfileImageUrl(userId, imageUrl);
        if (!updated) {
            serverError(resp, "Failed to update profile image.");
            return;
        }

        ok(resp, Collections.singletonMap("profileImageUrl", imageUrl));
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
