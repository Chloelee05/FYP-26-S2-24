-- Per-category picture shown on the home page category strip and in the admin table.
-- Nullable: a category with no picture falls back to the built-in icon matched to its
-- name, and then to a generic tag, so this is purely an override.
--
-- Stores the same kind of relative path as users.profile_image_url
-- ("/uploads/category/<uuid>.png"), served by UploadedFileServlet.
-- Apply once against auction_db. Safe to re-run.

ALTER TABLE categories ADD COLUMN IF NOT EXISTS image_url VARCHAR(255);
