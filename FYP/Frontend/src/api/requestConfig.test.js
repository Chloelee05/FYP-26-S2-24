// Guards the optional-config parameter added to these API functions so the polling and
// search paths can abort in-flight requests: calling them the old way must still build
// the exact same request, and passing a config must not clobber params or auth headers.
import { describe, it, expect, vi, beforeEach } from 'vitest';

const get = vi.fn(() => Promise.resolve({ data: {} }));
const post = vi.fn(() => Promise.resolve({ data: {} }));
vi.mock('./config.js', () => ({ default: { get, post } }));

const auction = await import('./auction.js');
const messages = await import('./messages.js');
const notifications = await import('./notifications.js');
const support = await import('./support.js');

beforeEach(() => { get.mockClear(); post.mockClear(); });

describe('unchanged wire format when called the old way', () => {
  it('getAuctionDetail(id)', () => {
    auction.getAuctionDetail(71);
    expect(get).toHaveBeenCalledWith('/auction/71', undefined);
  });

  it('getAuctionBids(id)', () => {
    auction.getAuctionBids(71);
    expect(get).toHaveBeenCalledWith('/auction/71/bids', { params: undefined });
  });

  it('searchAuctions(params)', () => {
    auction.searchAuctions({ page: 1, size: 12, sortBy: 'endingSoon' });
    expect(get).toHaveBeenCalledWith('/search', { params: { page: 1, size: 12, sortBy: 'endingSoon' } });
  });

  it('getConversations() / getOrderMessages(id)', () => {
    messages.getConversations();
    expect(get).toHaveBeenCalledWith('/order-messages', undefined);
    messages.getOrderMessages(5);
    expect(get).toHaveBeenCalledWith('/order-messages/5', undefined);
  });

  it('getNotifications()', () => {
    notifications.getNotifications();
    expect(get).toHaveBeenCalledWith('/notifications', undefined);
  });

  // These carry an explicit auth header; the config merge must not drop it.
  it('getSupportThreads() keeps its auth headers', () => {
    support.getSupportThreads();
    const [url, cfg] = get.mock.calls[0];
    expect(url).toBe('/support/threads');
    expect(cfg).toHaveProperty('headers');
  });
});

describe('config threads through without clobbering params or headers', () => {
  const signal = new AbortController().signal;

  it('getAuctionDetail(id, {signal})', () => {
    auction.getAuctionDetail(71, { signal });
    expect(get).toHaveBeenCalledWith('/auction/71', { signal });
  });

  it('getAuctionBids(id, undefined, {signal}) — the polling call', () => {
    auction.getAuctionBids(71, undefined, { signal });
    expect(get).toHaveBeenCalledWith('/auction/71/bids', { signal, params: undefined });
  });

  it('searchAuctions(params, {signal}) keeps both', () => {
    auction.searchAuctions({ page: 1, q: 'cam' }, { signal });
    expect(get).toHaveBeenCalledWith('/search', { signal, params: { page: 1, q: 'cam' } });
  });

  it('getSupportMessages(id, {signal}) keeps auth headers AND the signal', () => {
    support.getSupportMessages(3, { signal });
    const [url, cfg] = get.mock.calls[0];
    expect(url).toBe('/support/threads/3/messages');
    expect(cfg).toHaveProperty('headers');
    expect(cfg.signal).toBe(signal);
  });

  it('getSupportThreads({signal}) keeps auth headers AND the signal', () => {
    support.getSupportThreads({ signal });
    const [, cfg] = get.mock.calls[0];
    expect(cfg).toHaveProperty('headers');
    expect(cfg.signal).toBe(signal);
  });
});
