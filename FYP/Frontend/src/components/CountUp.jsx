import { useEffect, useState } from 'react';

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
  const animatable = value != null && Number.isFinite(target);
  const [shown, setShown] = useState(() => (animatable && !prefersReducedMotion() ? 0 : target));

  useEffect(() => {
    if (!animatable) return;
    // A zero span lands on the final value in the first frame, which is how
    // reduced-motion visitors skip the count without a separate code path.
    const span = prefersReducedMotion() ? 0 : duration;
    let frame = 0;
    const start = performance.now();
    const step = (now) => {
      const t = span > 0 ? Math.min((now - start) / span, 1) : 1;
      setShown(Math.round(target * (1 - (1 - t) ** 3)));
      if (t < 1) frame = requestAnimationFrame(step);
    };
    frame = requestAnimationFrame(step);
    return () => cancelAnimationFrame(frame);
  }, [target, animatable, duration]);

  return animatable ? shown.toLocaleString() : null;
}
