import { describe, it, expect } from 'vitest';
import {
  timeRemaining, timeRemainingWithDays, getInitials, getRoleDisplay,
  normalizeCategories, decodeHtmlEntities, formatCurrency,
} from './helpers';

// Both countdown helpers take `now` as an argument, so they can be pinned without
// touching timers.
const NOW = new Date('2026-08-01T12:00:00Z').getTime();
const inMs = (ms) => new Date(NOW + ms).toISOString();
const HOUR = 3_600_000;
const DAY = 86_400_000;

describe('timeRemaining', () => {
  it('counts down in hours, minutes and seconds', () => {
    expect(timeRemaining(inMs(2 * HOUR + 3 * 60_000 + 4000), NOW)).toBe('2h 3m 4s');
  });

  it('keeps counting in hours past a day rather than rolling up', () => {
    expect(timeRemaining(inMs(3 * DAY), NOW)).toBe('72h 0m 0s');
  });

  it('reads Ended at and after the end time', () => {
    expect(timeRemaining(inMs(0), NOW)).toBe('Ended');
    expect(timeRemaining(inMs(-1000), NOW)).toBe('Ended');
  });
});

describe('timeRemainingWithDays', () => {
  it('rolls whole days up and drops seconds at that range', () => {
    expect(timeRemainingWithDays(inMs(3 * DAY + 4 * HOUR + 5 * 60_000), NOW)).toBe('3d 4h 5m');
  });

  it('falls back to hours, minutes and seconds inside a day', () => {
    expect(timeRemainingWithDays(inMs(2 * HOUR + 3 * 60_000 + 4000), NOW)).toBe('2h 3m 4s');
  });

  it('reads Ended once the time has passed', () => {
    expect(timeRemainingWithDays(inMs(-1), NOW)).toBe('Ended');
  });
});

describe('getInitials', () => {
  it('takes the first letter of the first two words', () => {
    expect(getInitials('Ada Lovelace')).toBe('AL');
    expect(getInitials('Ada Byron King Lovelace')).toBe('AB');
  });

  it('handles a single word and an empty name', () => {
    expect(getInitials('Ada')).toBe('A');
    expect(getInitials('')).toBe('');
    expect(getInitials()).toBe('');
  });
});

describe('getRoleDisplay', () => {
  // Buying and selling are one account, so both legacy roles read as "Member".
  it('shows BUYER and SELLER as a single Member label', () => {
    expect(getRoleDisplay('BUYER').label).toBe('Member');
    expect(getRoleDisplay('SELLER').label).toBe('Member');
  });

  it('keeps ADMIN separate', () => {
    expect(getRoleDisplay('ADMIN').label).toBe('Admin');
  });

  it('falls back for an unknown or missing role', () => {
    expect(getRoleDisplay('WAT').label).toBe('WAT');
    expect(getRoleDisplay(undefined).label).toBe('User');
    expect(getRoleDisplay(undefined).className).toEqual(expect.any(String));
  });
});

describe('normalizeCategories', () => {
  it('drops entries with no name and rejects non-arrays', () => {
    expect(normalizeCategories([{ name: 'Art' }, {}, null, { name: '' }])).toEqual([{ name: 'Art' }]);
    expect(normalizeCategories(null)).toEqual([]);
    expect(normalizeCategories({ name: 'Art' })).toEqual([]);
  });
});

describe('decodeHtmlEntities', () => {
  it('undoes the server-side sanitiser escaping', () => {
    expect(decodeHtmlEntities('5 &gt; 3 &amp; 2 &lt; 4')).toBe('5 > 3 & 2 < 4');
    expect(decodeHtmlEntities('&quot;quoted&quot;')).toBe('"quoted"');
  });

  it('returns an empty string for empty input', () => {
    expect(decodeHtmlEntities('')).toBe('');
    expect(decodeHtmlEntities(null)).toBe('');
  });
});

describe('formatCurrency', () => {
  it('formats as USD', () => {
    expect(formatCurrency(1234.5)).toBe('$1,234.50');
    expect(formatCurrency(0)).toBe('$0.00');
  });
});
