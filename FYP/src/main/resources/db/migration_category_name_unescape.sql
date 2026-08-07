-- Repair category names/descriptions that were written through SecurityUtil.sanitize.
-- That helper doubled every single quote and then HTML entity-encoded the result before
-- persisting it, while both views (JSP <c:out> and React) escape again on output, so the
-- entities were stored and shown literally: "Home &amp; Garden", "Men&#39;&#39;s Fashion".
--
-- The servlets now store plain text (SecurityUtil.sanitizeText); this backfills the rows
-- written before that change. Safe to re-run: decoded text contains no entities to decode.
--
-- Entity names are decoded before "&amp;" so a literal "&lt;" typed by an admin (stored as
-- "&amp;lt;") comes back as "&lt;" rather than being turned into a real angle bracket.

BEGIN;

UPDATE categories
SET name = REPLACE(
             REPLACE(
               REPLACE(
                 REPLACE(
                   REPLACE(REPLACE(name, '&#39;&#39;', ''''), '&#39;', ''''),
                 '&quot;', '"'),
               '&lt;', '<'),
             '&gt;', '>'),
           '&amp;', '&')
WHERE name LIKE '%&#39;%'
   OR name LIKE '%&quot;%'
   OR name LIKE '%&lt;%'
   OR name LIKE '%&gt;%'
   OR name LIKE '%&amp;%';

UPDATE categories
SET description = REPLACE(
                    REPLACE(
                      REPLACE(
                        REPLACE(
                          REPLACE(REPLACE(description, '&#39;&#39;', ''''), '&#39;', ''''),
                        '&quot;', '"'),
                      '&lt;', '<'),
                    '&gt;', '>'),
                  '&amp;', '&')
WHERE description LIKE '%&#39;%'
   OR description LIKE '%&quot;%'
   OR description LIKE '%&lt;%'
   OR description LIKE '%&gt;%'
   OR description LIKE '%&amp;%';

-- Slugs were derived from the encoded name, so "Men's Fashion" was filed under
-- "men3939s-fashion". Rebuild them from the repaired name using the same rules as
-- AdminApiServlet.resolveSlug: drop everything outside [a-z0-9 -], collapse whitespace
-- to a dash, collapse repeated dashes, and fall back to "category" when nothing is left.
-- The NOT EXISTS guard skips any row whose ideal slug is already taken, so the unique
-- constraint cannot fail this migration; such a row simply keeps its current slug.
UPDATE categories c
SET slug = want.candidate
FROM (
    SELECT id,
           COALESCE(NULLIF(
               trim(BOTH '-' FROM regexp_replace(
                   regexp_replace(
                       regexp_replace(lower(name), '[^a-z0-9 -]', '', 'g'),
                   '\s+', '-', 'g'),
               '-+', '-', 'g')), ''), 'category') AS candidate
    FROM categories
) AS want
WHERE c.id = want.id
  AND c.slug IS DISTINCT FROM want.candidate
  AND NOT EXISTS (SELECT 1 FROM categories o WHERE o.slug = want.candidate AND o.id <> c.id);

-- auction_details.category stores the category *name*, so keep listings pointing at the
-- repaired names instead of the encoded ones.
UPDATE auction_details
SET category = REPLACE(
                 REPLACE(
                   REPLACE(
                     REPLACE(
                       REPLACE(REPLACE(category, '&#39;&#39;', ''''), '&#39;', ''''),
                     '&quot;', '"'),
                   '&lt;', '<'),
                 '&gt;', '>'),
               '&amp;', '&')
WHERE category LIKE '%&#39;%'
   OR category LIKE '%&quot;%'
   OR category LIKE '%&lt;%'
   OR category LIKE '%&gt;%'
   OR category LIKE '%&amp;%';

COMMIT;
