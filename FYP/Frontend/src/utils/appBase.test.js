import { describe, it, expect } from 'vitest';
import { appBase, publicPath } from './appBase';

// Vitest serves BASE_URL as '/', matching Vite dev. The production case ('/online-auction/')
// is covered by asserting the prefixing rules relative to whatever appBase() returns.
describe('appBase', () => {
  it('never ends in a slash, so callers can concatenate directly', () => {
    expect(appBase().endsWith('/')).toBe(false);
  });
});

describe('publicPath', () => {
  it('leaves absolute URLs alone', () => {
    expect(publicPath('https://cdn.example.com/a.png')).toBe('https://cdn.example.com/a.png');
    expect(publicPath('HTTP://example.com/a.png')).toBe('HTTP://example.com/a.png');
  });

  it('prefixes a root-relative path with the app base', () => {
    expect(publicPath('/uploads/a.png')).toBe(`${appBase()}/uploads/a.png`);
  });

  it('adds the separator for a bare path', () => {
    expect(publicPath('uploads/a.png')).toBe(`${appBase()}/uploads/a.png`);
  });

  it('passes empty values straight through rather than returning a bare base', () => {
    expect(publicPath('')).toBe('');
    expect(publicPath(null)).toBeNull();
    expect(publicPath(undefined)).toBeUndefined();
  });
});
