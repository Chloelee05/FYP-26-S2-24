import { Star } from 'lucide-react';

/**
 * Read-only when `onChange` is omitted, interactive otherwise.
 * Colours are static class names so Tailwind keeps them in the build.
 */
export default function StarRating({ value = 0, max = 5, onChange, size = 20 }) {
  const interactive = typeof onChange === 'function';

  return (
    <div className={`inline-flex items-center ${interactive ? 'gap-1' : 'gap-0.5'}`} role={interactive ? 'radiogroup' : 'img'} aria-label={`${value} out of ${max} stars`}>
      {Array.from({ length: max }, (_, i) => {
        const filled = i < value;
        return (
          <button
            key={i}
            type="button"
            disabled={!interactive}
            onClick={() => interactive && onChange(i + 1)}
            aria-label={`${i + 1} star${i ? 's' : ''}`}
            className={`transition-all duration-150 ${
              interactive
                ? 'cursor-pointer hover:scale-110 active:scale-95 rounded'
                : 'cursor-default disabled:opacity-100'
            } ${filled ? 'text-amber-400' : 'text-ink-300'}`}
          >
            <Star size={size} strokeWidth={1.75} className={filled ? 'fill-amber-400' : 'fill-transparent'} />
          </button>
        );
      })}
    </div>
  );
}
