import { useEffect } from 'react';
import { useLocation, useNavigationType } from 'react-router-dom';

/**
 * A single-page app keeps the window's scroll offset across route changes, so
 * opening a category or a listing from halfway down a page would drop you into
 * the middle of the new one. Reset to the top whenever the route changes.
 *
 * Two deliberate exceptions:
 * - POP (browser back/forward) keeps its offset, so returning to a long result
 *   list puts you back where you left it rather than at the top.
 * - A URL with a hash is asking for a specific anchor; leave it alone.
 *
 * The query string counts as a route change: switching category on the search
 * page only moves ?category=, and that swaps the whole result grid.
 */
export default function ScrollToTop() {
  const { pathname, search, hash } = useLocation();
  const navigationType = useNavigationType();

  useEffect(() => {
    if (navigationType === 'POP' || hash) return;
    window.scrollTo(0, 0);
  }, [pathname, search, hash, navigationType]);

  return null;
}
