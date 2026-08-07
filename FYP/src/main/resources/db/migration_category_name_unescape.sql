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
--
-- categories.name carries a UNIQUE constraint, so decoding can collide with a category that
-- already holds the plain-text name ("Men&#39;&#39;s Fashion" decoding onto an existing
-- "Men's Fashion"). Every UPDATE below is therefore guarded the same way the slug rebuild is:
-- a row whose decoded value is already taken keeps its current value and the whole
-- transaction still commits. Skipped rows are reported at the end so they are not a silent
-- failure -- an unguarded name UPDATE previously aborted this migration outright, which left
-- the schema migration behind it in migrate_all.sql unapplied.

BEGIN;

-- Decode names. The guard has two halves, because a collision can come from either direction:
--   1. NOT EXISTS -- some other row already holds the decoded name as its current name.
--   2. min(id)    -- two encoded rows decode to the *same* name. The subquery reads the
--                    pre-statement snapshot, so without this both would pass check 1 and
--                    collide with each other. Only the lowest id claims the name.
WITH decoded AS (
    SELECT id,
           name,
           REPLACE(
             REPLACE(
               REPLACE(
                 REPLACE(
                   REPLACE(REPLACE(name, '&#39;&#39;', ''''), '&#39;', ''''),
                 '&quot;', '"'),
               '&lt;', '<'),
             '&gt;', '>'),
           '&amp;', '&') AS candidate
    FROM categories
),
eligible AS (
    SELECT d.id, d.candidate
    FROM decoded d
    WHERE d.candidate IS DISTINCT FROM d.name
      AND NOT EXISTS (SELECT 1 FROM categories o WHERE o.name = d.candidate AND o.id <> d.id)
      AND d.id = (SELECT MIN(d2.id) FROM decoded d2 WHERE d2.candidate = d.candidate)
)
UPDATE categories c
SET name = e.candidate
FROM eligible e
WHERE c.id = e.id;

-- Descriptions need no collision guard: description is not unique and not indexed, so two
-- categories are free to end up with the same text. Kept as a plain UPDATE for that reason.
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
-- Same two-part guard as the name update, so the unique slug constraint cannot fail this
-- migration; a row whose ideal slug is taken simply keeps its current slug.
WITH want AS (
    SELECT id,
           slug,
           COALESCE(NULLIF(
               trim(BOTH '-' FROM regexp_replace(
                   regexp_replace(
                       regexp_replace(lower(name), '[^a-z0-9 -]', '', 'g'),
                   '\s+', '-', 'g'),
               '-+', '-', 'g')), ''), 'category') AS candidate
    FROM categories
),
eligible AS (
    SELECT w.id, w.candidate
    FROM want w
    WHERE w.candidate IS DISTINCT FROM w.slug
      AND NOT EXISTS (SELECT 1 FROM categories o WHERE o.slug = w.candidate AND o.id <> w.id)
      AND w.id = (SELECT MIN(w2.id) FROM want w2 WHERE w2.candidate = w.candidate)
)
UPDATE categories c
SET slug = e.candidate
FROM eligible e
WHERE c.id = e.id;

-- auction_details.category stores the category *name*, so keep listings pointing at the
-- repaired names instead of the encoded ones. No guard needed: this column is free text with
-- no unique constraint, and a listing whose category was skipped above simply lands on the
-- category that already held the decoded name.
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

-- Anything still encoded here was skipped by a guard above, which means a duplicate needs a
-- human decision (merge the two categories, or rename one). Reported rather than raised as an
-- error so the rest of the repair still commits and migrate_all.sql keeps going.
DO $$
DECLARE
    r RECORD;
    skipped INT := 0;
BEGIN
    FOR r IN
        SELECT id, name FROM categories
        WHERE name LIKE '%&#39;%'
           OR name LIKE '%&quot;%'
           OR name LIKE '%&lt;%'
           OR name LIKE '%&gt;%'
           OR name LIKE '%&amp;%'
        ORDER BY id
    LOOP
        RAISE NOTICE 'category id % left encoded as "%": its decoded name is already taken by another category', r.id, r.name;
        skipped := skipped + 1;
    END LOOP;
    IF skipped > 0 THEN
        RAISE WARNING '% category name(s) could not be decoded because of a duplicate name. Resolve the duplicates listed above, then re-run this migration.', skipped;
    END IF;
END $$;

COMMIT;
