import { useEffect } from 'react';

/**
 * Runs `load` once, then repeatedly, for as long as the caller is mounted.
 *
 * The app has no SSE (Cloudflare buffers it on Render), so chat threads, bids and
 * notifications all stay fresh by polling. Three things this handles that a bare
 * setInterval does not:
 *
 *   - Hidden tabs don't poll. A backgrounded tab would otherwise keep hitting Tomcat
 *     at full rate forever. Returning to the tab reloads immediately rather than
 *     waiting out the rest of the interval, so the view is never visibly stale.
 *   - Ticks never overlap. If a response is slower than the interval, the next tick
 *     is skipped instead of queueing a second request behind it.
 *   - Responses that land after unmount are dropped, so `load` can setState freely.
 *     Because every setState then happens after an await rather than in the effect
 *     body, this also keeps callers clear of react-hooks/set-state-in-effect.
 *
 * `load` is passed an { signal } so it can abort in-flight work, and may return a
 * promise, which is awaited to enforce the no-overlap rule.
 *
 * @param load       async loader. MUST be wrapped in useCallback — polling restarts
 *                   (with an immediate reload) whenever its identity changes, which
 *                   is what you want when it closes over a selected id.
 * @param intervalMs delay between ticks
 * @param enabled    set false to suspend polling, e.g. for a closed panel
 */
export default function usePolling(load, intervalMs, enabled = true) {
  useEffect(() => {
    if (!enabled) return;

    let cancelled = false;
    let inFlight = false;
    const controller = new AbortController();

    const run = async () => {
      if (cancelled || inFlight) return;
      inFlight = true;
      try {
        await load({ signal: controller.signal });
      } catch {
        // Loaders own their error reporting; a rejection must not kill the loop.
      } finally {
        inFlight = false;
      }
    };

    const onTick = () => {
      if (!document.hidden) run();
    };

    run();
    const timer = setInterval(onTick, intervalMs);
    document.addEventListener('visibilitychange', onTick);

    return () => {
      cancelled = true;
      clearInterval(timer);
      document.removeEventListener('visibilitychange', onTick);
      controller.abort();
    };
  }, [load, intervalMs, enabled]);
}
