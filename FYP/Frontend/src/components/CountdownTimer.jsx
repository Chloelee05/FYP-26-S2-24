import { useState, useEffect } from 'react';
import { Clock } from 'lucide-react';

/** Under this many ms remaining the timer turns amber, then red in the final hour. */
const SOON_MS = 24 * 60 * 60 * 1000;
const URGENT_MS = 60 * 60 * 1000;

export default function CountdownTimer({ endTime, size = 16, className = '' }) {
  const [state, setState] = useState({ label: '', diff: null });

  useEffect(() => {
    const calc = () => {
      const diff = new Date(endTime) - new Date();
      if (diff <= 0) { setState({ label: 'Ended', diff: 0 }); return; }
      const d = Math.floor(diff / 86400000);
      const h = Math.floor((diff % 86400000) / 3600000);
      const m = Math.floor((diff % 3600000) / 60000);
      const s = Math.floor((diff % 60000) / 1000);
      setState({ label: d > 0 ? `${d}d ${h}h ${m}m` : `${h}h ${m}m ${s}s`, diff });
    };
    calc();
    const id = setInterval(calc, 1000);
    return () => clearInterval(id);
  }, [endTime]);

  const { label, diff } = state;
  const tone =
    diff === 0 ? 'text-ink-400'
    : diff != null && diff < URGENT_MS ? 'text-red-600'
    : diff != null && diff < SOON_MS ? 'text-accent-600'
    : 'text-ink-600';

  return (
    <span className={`inline-flex items-center gap-1.5 font-semibold tabular-nums ${tone} ${className}`}>
      <Clock size={size} className={diff != null && diff > 0 && diff < URGENT_MS ? 'animate-pulse-dot' : ''} />
      <span>{label}</span>
    </span>
  );
}
