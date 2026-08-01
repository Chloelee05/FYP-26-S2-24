import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render } from '@testing-library/react';
import { useCallback } from 'react';
import usePolling from './usePolling';

function Poller({ load, intervalMs = 1000, enabled = true }) {
  // Wrapped so the hook sees one stable identity per `load` prop, the way real
  // callers pass a useCallback'd loader.
  const stableLoad = useCallback((opts) => load(opts), [load]);
  usePolling(stableLoad, intervalMs, enabled);
  return null;
}

/** Flush pending promise callbacks that the fake timers do not drive. */
const flush = () => act(async () => { await Promise.resolve(); });

/**
 * Advance one interval at a time, letting promises settle in between. Jumping several
 * intervals in a single call would land them all in the same synchronous batch, where
 * the hook's no-overlap guard correctly skips them — real ticks are seconds apart.
 */
async function advanceTicks(count, intervalMs) {
  for (let i = 0; i < count; i++) {
    await act(async () => { vi.advanceTimersByTime(intervalMs); });
    await flush();
  }
}

const setHidden = (hidden) => {
  Object.defineProperty(document, 'hidden', { configurable: true, value: hidden });
  document.dispatchEvent(new Event('visibilitychange'));
};

describe('usePolling', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    vi.useRealTimers();
    Object.defineProperty(document, 'hidden', { configurable: true, value: false });
  });

  it('loads once immediately and again on each interval', async () => {
    const load = vi.fn().mockResolvedValue();
    render(<Poller load={load} intervalMs={1000} />);
    await flush();
    expect(load).toHaveBeenCalledTimes(1);

    await advanceTicks(2, 1000);
    expect(load).toHaveBeenCalledTimes(3);
  });

  it('does not poll at all when disabled', async () => {
    const load = vi.fn().mockResolvedValue();
    render(<Poller load={load} enabled={false} />);
    await act(async () => { vi.advanceTimersByTime(5000); });
    expect(load).not.toHaveBeenCalled();
  });

  // The point of the hook: a backgrounded tab must stop hitting the server.
  it('skips ticks while the tab is hidden', async () => {
    const load = vi.fn().mockResolvedValue();
    render(<Poller load={load} intervalMs={1000} />);
    await flush();
    expect(load).toHaveBeenCalledTimes(1);

    setHidden(true);
    await act(async () => { vi.advanceTimersByTime(5000); });
    expect(load).toHaveBeenCalledTimes(1);
  });

  it('reloads straight away when the tab becomes visible again', async () => {
    const load = vi.fn().mockResolvedValue();
    render(<Poller load={load} intervalMs={10_000} />);
    await flush();
    setHidden(true);
    await act(async () => { vi.advanceTimersByTime(30_000); });
    expect(load).toHaveBeenCalledTimes(1);

    // Back in the foreground: refresh now rather than waiting out the interval.
    await act(async () => { setHidden(false); });
    await flush();
    expect(load).toHaveBeenCalledTimes(2);
  });

  it('skips a tick rather than queueing behind a slow response', async () => {
    let release;
    const load = vi.fn(() => new Promise(resolve => { release = resolve; }));
    render(<Poller load={load} intervalMs={1000} />);
    await flush();
    expect(load).toHaveBeenCalledTimes(1);

    // Three ticks pass while the first request is still open.
    await act(async () => { vi.advanceTimersByTime(3000); });
    expect(load).toHaveBeenCalledTimes(1);

    await act(async () => { release(); });
    await act(async () => { vi.advanceTimersByTime(1000); });
    expect(load).toHaveBeenCalledTimes(2);
  });

  it('stops polling and aborts the in-flight request on unmount', async () => {
    const load = vi.fn().mockResolvedValue();
    const { unmount } = render(<Poller load={load} intervalMs={1000} />);
    await flush();
    const signal = load.mock.calls[0][0].signal;
    expect(signal.aborted).toBe(false);

    unmount();
    expect(signal.aborted).toBe(true);
    await act(async () => { vi.advanceTimersByTime(5000); });
    expect(load).toHaveBeenCalledTimes(1);
  });

  it('keeps polling after a rejected load', async () => {
    const load = vi.fn().mockRejectedValue(new Error('offline'));
    render(<Poller load={load} intervalMs={1000} />);
    await flush();
    await advanceTicks(2, 1000);
    expect(load).toHaveBeenCalledTimes(3);
  });
});
