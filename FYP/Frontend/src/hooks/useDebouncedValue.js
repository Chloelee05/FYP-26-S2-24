// Used by the Navbar search box and by the free-text filters on the search, seller
// listing and admin pages.
import { useEffect, useState } from 'react';

/**
 * Returns `value` after it has stopped changing for `delayMs`.
 *
 * Used to keep free-text filters from firing one request per keystroke. Selects and
 * radios should stay undebounced — a single click is already the user's final answer.
 */
export default function useDebouncedValue(value, delayMs = 350) {
  const [settled, setSettled] = useState(value);

  useEffect(() => {
    const t = setTimeout(() => setSettled(value), delayMs);
    return () => clearTimeout(t);
  }, [value, delayMs]);

  return settled;
}
