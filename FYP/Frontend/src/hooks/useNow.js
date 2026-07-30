import { useEffect, useState } from 'react';

/**
 * A ticking clock shared by every component that needs one.
 *
 * A results page can hold dozens of auction cards, so they subscribe to a single
 * module-level interval instead of each starting its own. The interval only runs
 * while something is subscribed.
 */
const subscribers = new Set();
let timerId = null;

function tick() {
  const now = Date.now();
  subscribers.forEach(notify => notify(now));
}

/**
 * Returns the current epoch milliseconds, re-rendering the caller on every tick.
 *
 * @param intervalMs how often to update (default one second)
 */
export default function useNow(intervalMs = 1000) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    subscribers.add(setNow);
    if (timerId == null) timerId = setInterval(tick, intervalMs);

    return () => {
      subscribers.delete(setNow);
      if (subscribers.size === 0 && timerId != null) {
        clearInterval(timerId);
        timerId = null;
      }
    };
  }, [intervalMs]);

  return now;
}
