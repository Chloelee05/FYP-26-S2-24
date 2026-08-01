import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import CountdownTimer from './CountdownTimer';

const NOW = new Date('2026-08-01T12:00:00Z').getTime();
const HOUR = 3_600_000;
const DAY = 86_400_000;

const at = (offsetMs) => new Date(NOW + offsetMs).toISOString();

describe('CountdownTimer', () => {
  beforeEach(() => vi.useFakeTimers({ now: NOW }));
  afterEach(() => vi.useRealTimers());

  it('rolls whole days up rather than showing a large hour count', () => {
    render(<CountdownTimer endTime={at(3 * DAY + 4 * HOUR + 5 * 60_000)} />);
    expect(screen.getByText('3d 4h 5m')).toBeInTheDocument();
  });

  it('shows seconds once under a day', () => {
    render(<CountdownTimer endTime={at(2 * HOUR + 30 * 60_000 + 15_000)} />);
    expect(screen.getByText('2h 30m 15s')).toBeInTheDocument();
  });

  it('ticks without a remount', async () => {
    render(<CountdownTimer endTime={at(3000)} />);
    expect(screen.getByText('0h 0m 3s')).toBeInTheDocument();
    await act(async () => { vi.advanceTimersByTime(2000); });
    expect(screen.getByText('0h 0m 1s')).toBeInTheDocument();
  });

  it('reads Ended at and past the end time', async () => {
    render(<CountdownTimer endTime={at(1000)} />);
    await act(async () => { vi.advanceTimersByTime(1000); });
    expect(screen.getByText('Ended')).toBeInTheDocument();
  });

  it('colours by urgency: normal, then amber inside a day, then red in the last hour', () => {
    const tone = (offset) => {
      const { container, unmount } = render(<CountdownTimer endTime={at(offset)} />);
      const cls = container.firstChild.className;
      unmount();
      return cls;
    };

    expect(tone(3 * DAY)).toContain('text-ink-600');
    expect(tone(5 * HOUR)).toContain('text-accent-600');
    expect(tone(30 * 60_000)).toContain('text-red-600');
    expect(tone(-1000)).toContain('text-ink-400');
  });

  it('shares one interval across many timers rather than starting one each', async () => {
    const spy = vi.spyOn(globalThis, 'setInterval');
    render(
      <>
        <CountdownTimer endTime={at(HOUR)} />
        <CountdownTimer endTime={at(2 * HOUR)} />
        <CountdownTimer endTime={at(3 * HOUR)} />
      </>,
    );
    expect(spy).toHaveBeenCalledTimes(1);
    spy.mockRestore();
  });
});
