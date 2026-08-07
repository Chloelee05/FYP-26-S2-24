/**
 * Live "time left" label with a clock icon, used on the auction detail page and in the
 * seller's listing tables. AuctionCard draws its own chip instead, because that one has
 * to sit over the photo.
 *
 * Props: `endTime` is the auction end date, `size` the icon size, `className` any extra
 * classes. The colour carries the urgency: normal, amber inside 24 hours, and red with a
 * pulsing icon in the final hour.
 */
import { Clock } from 'lucide-react';
import useNow from '../hooks/useNow';
import { timeRemainingWithDays } from '../utils/helpers';

/** Under this many ms remaining the timer turns amber, then red in the final hour. */
const SOON_MS = 24 * 60 * 60 * 1000;
const URGENT_MS = 60 * 60 * 1000;

export default function CountdownTimer({ endTime, size = 16, className = '' }) {
  // One shared interval across every countdown on the page, rather than one each.
  const now = useNow();
  const diff = new Date(endTime) - now;
  const ended = diff <= 0;
  const urgent = !ended && diff < URGENT_MS;

  const tone =
    ended ? 'text-ink-400'
    : urgent ? 'text-red-600'
    : diff < SOON_MS ? 'text-accent-600'
    : 'text-ink-600';

  return (
    <span className={`inline-flex items-center gap-1.5 font-semibold tabular-nums ${tone} ${className}`}>
      <Clock size={size} className={urgent ? 'animate-pulse-dot' : ''} />
      <span>{timeRemainingWithDays(endTime, now)}</span>
    </span>
  );
}
