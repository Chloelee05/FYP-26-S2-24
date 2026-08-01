import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import useDebouncedValue from './useDebouncedValue';

function Probe({ value, delayMs }) {
  return <span data-testid="out">{useDebouncedValue(value, delayMs)}</span>;
}

const out = () => screen.getByTestId('out').textContent;

describe('useDebouncedValue', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('returns the initial value straight away', () => {
    render(<Probe value="ada" delayMs={300} />);
    expect(out()).toBe('ada');
  });

  it('holds the old value until the delay has passed', async () => {
    const { rerender } = render(<Probe value="a" delayMs={300} />);
    rerender(<Probe value="ab" delayMs={300} />);
    expect(out()).toBe('a');

    await act(async () => { vi.advanceTimersByTime(299); });
    expect(out()).toBe('a');

    await act(async () => { vi.advanceTimersByTime(1); });
    expect(out()).toBe('ab');
  });

  // The behaviour Search depends on: typing "1500" must settle once, not four times.
  it('only settles on the last value of a burst of changes', async () => {
    const { rerender } = render(<Probe value="1" delayMs={300} />);
    for (const v of ['15', '150', '1500']) {
      rerender(<Probe value={v} delayMs={300} />);
      await act(async () => { vi.advanceTimersByTime(100); });
      expect(out()).toBe('1');
    }

    await act(async () => { vi.advanceTimersByTime(300); });
    expect(out()).toBe('1500');
  });
});
