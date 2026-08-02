-- Hybrid re-ranking weights and the per-category diversity cap. Additive, safe to re-run.
--
-- The four recommendation stages used to run in sequence, each filling whatever slots the
-- previous one left, so the final order was decided by stage boundaries rather than by any
-- score: an auction three signals agreed on weakly could sit below one that a single signal
-- happened to like. The stages are now candidate generators feeding one re-ranking pass:
--
--   score(i) = ( w_cf·cf(i) + w_ubcf·ubcf(i) + w_content·content(i)
--                + w_pop·pop(i) + w_rec·rec(i) ) / sum of the weights actually in play
--
-- with every component min-max normalised across the candidate set.
--
-- The seeded weights keep the arms in the order the stages used to run — collaborative
-- first, popularity last — for a candidate carrying one signal only, while letting a
-- candidate several signals agree on overtake one a single signal likes a lot. They are
-- starting points chosen for being explainable, not figures fitted to an evaluation set;
-- there is no offline test set in this project to fit them against.
--
-- Setting any single weight to 0 removes that signal from the blend, which is how an admin
-- demonstrates its effect live: drop rerank_w_pop to 0, save, reload the landing page, and
-- the popular listings visibly fall down the strip. Setting all five to 0 leaves nothing to
-- sort by, and the ranking falls back to the old stage sequence rather than to an arbitrary
-- order.
--
-- diversity_category_divisor caps each category at ceil(items_shown / divisor) during final
-- assembly. The content-based stage orders only by date_end, so a viewer who opened one
-- Electronics listing could see soon-ending Electronics take every remaining slot. Items
-- held back by the cap are appended once the capped pass runs out, so the strip is never
-- shorter than it would have been. A divisor of 1 raises the cap to the whole page and
-- switches the behaviour off.
--
-- Applying this migration against an older build changes nothing, because nothing reads
-- these keys yet. Once the matching code is deployed it does change the ordering — that is
-- the point of the change, and there is no previous scoring to reproduce.

INSERT INTO recommendation_settings (key, value) VALUES ('rerank_w_cf', '1.0')
  ON CONFLICT (key) DO NOTHING;
INSERT INTO recommendation_settings (key, value) VALUES ('rerank_w_ubcf', '0.9')
  ON CONFLICT (key) DO NOTHING;
INSERT INTO recommendation_settings (key, value) VALUES ('rerank_w_content', '0.7')
  ON CONFLICT (key) DO NOTHING;
INSERT INTO recommendation_settings (key, value) VALUES ('rerank_w_pop', '0.4')
  ON CONFLICT (key) DO NOTHING;
INSERT INTO recommendation_settings (key, value) VALUES ('rerank_w_rec', '0.2')
  ON CONFLICT (key) DO NOTHING;
INSERT INTO recommendation_settings (key, value) VALUES ('diversity_category_divisor', '3')
  ON CONFLICT (key) DO NOTHING;
