/**
 * Animates a number up from zero, used for the live platform figures in the landing page
 * hero (listings, members, bids), which come from /api/stats rather than being hardcoded.
 *
 * Props: `value` is the target number, `duration` the length of the count in
 * milliseconds. It renders bare text, so the caller owns all the styling.
 *
 * The animation is driven by requestAnimationFrame and respects prefers-reduced-motion:
 * for a visitor who has asked for less movement the span collapses to zero and the first
 * frame is already the final value.
 */
import { useEffect, useState } from 'react';

// Checked through optional chaining because matchMedia is missing under jsdom in tests.
const prefersReducedMotion = () =>
  typeof window !== 'undefined' &&
  Boolean(window.matchMedia?.('(prefers-reduced-motion: reduce)').matches);

/**
 * Short count-up for the hero's live database metrics. Renders the same
 * `toLocaleString()` output as static text, so the final frame is identical
 * to no animation at all — and non-numeric or missing values render nothing.
 */
export default function CountUp({ value, duration = 900 }) {
  const target = Number(value);
  // A stat that has not loaded yet, or one that is text rather than a number, is not
  // something to count towards, so the component renders nothing in that case.
  const animatable = value != null && Number.isFinite(target);
  // The number currently on screen. Starts at zero for an animated count and at the
  // target otherwise, so nothing ever jumps backwards.
  const [shown, setShown] = useState(() => (animatable && !prefersReducedMotion() ? 0 : target));

  useEffect(() => {
    if (!animatable) return;
    // A zero span lands on the final value in the first frame, which is how
    // reduced-motion visitors skip the count without a separate code path.
    const span = prefersReducedMotion() ? 0 : duration;
    let frame = 0;
    const start = performance.now();
    const step = (now) => {
      // t runs 0 to 1 over the span; the cubic ease-out makes the count sprint then
      // settle rather than crawl at a constant rate.
      const t = span > 0 ? Math.min((now - start) / span, 1) : 1;
      setShown(Math.round(target * (1 - (1 - t) ** 3)));
      if (t < 1) frame = requestAnimationFrame(step);
    };
    frame = requestAnimationFrame(step);
    return () => cancelAnimationFrame(frame);
  }, [target, animatable, duration]);

  return animatable ? shown.toLocaleString() : null;
}
