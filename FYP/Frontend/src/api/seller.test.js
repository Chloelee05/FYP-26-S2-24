// The seller endpoints are the contract behind requirement Seller (b)–(d): what the listing
// page asks for (so it can page through every listing rather than the first ten), and what the
// cancel / remove-item / edit calls carry (a reason, a quantity, a cost price).
import { describe, it, expect, vi, beforeEach } from 'vitest';

const get = vi.fn(() => Promise.resolve({ data: {} }));
const post = vi.fn(() => Promise.resolve({ data: {} }));
vi.mock('./config.js', () => ({ default: { get, post } }));

const seller = await import('./seller.js');

beforeEach(() => { get.mockClear(); post.mockClear(); });

/** The submitted form of the most recent POST, as a plain object. */
const submitted = () => Object.fromEntries(post.mock.calls[0][1].entries());
const submittedKeys = () => [...post.mock.calls[0][1].keys()];

describe('getSellerAuctions', () => {
  it('asks for one bucket, page and sort at a time', () => {
    seller.getSellerAuctions({ bucket: 'UNSOLD', q: 'gucci', sort: 'priceHigh', page: 2, size: 10 });

    expect(get.mock.calls[0][0]).toBe('/seller/auctions');
    expect(get.mock.calls[0][1].params).toEqual({
      bucket: 'UNSOLD', q: 'gucci', sort: 'priceHigh', page: 2, size: 10,
    });
  });

  it('carries an abort signal so a superseded page cannot land last', () => {
    const controller = new AbortController();
    seller.getSellerAuctions({ page: 1 }, { signal: controller.signal });

    expect(get.mock.calls[0][1].signal).toBe(controller.signal);
  });

  it('still works with no arguments at all', () => {
    seller.getSellerAuctions();
    expect(get.mock.calls[0][1].params).toBeUndefined();
  });
});

describe('cancelAuction', () => {
  it('sends the reason, so cancel_reason is no longer always NULL', () => {
    seller.cancelAuction(7, 'No bids received');

    expect(post.mock.calls[0][0]).toBe('/seller/cancel');
    expect(submitted()).toEqual({ auctionId: '7', reason: 'No bids received' });
  });

  it('omits a missing reason rather than sending the word "undefined"', () => {
    seller.cancelAuction(7);
    expect(submittedKeys()).toEqual(['auctionId']);
  });
});

describe('removeAuctionUnit', () => {
  it('sends a reason when the last unit is going, since that ends the listing', () => {
    seller.removeAuctionUnit(7, 'Item was damaged');

    expect(post.mock.calls[0][0]).toBe('/seller/reduce-quantity');
    expect(submitted()).toEqual({ auctionId: '7', reason: 'Item was damaged' });
  });

  it('sends no reason for a plain decrement', () => {
    seller.removeAuctionUnit(7);
    expect(submittedKeys()).toEqual(['auctionId']);
  });
});

describe('editAuction', () => {
  it('carries the quantity and the private cost price', () => {
    seller.editAuction({
      auctionId: 7,
      title: 'A thing',
      description: 'Some description',
      quantity: '5',
      costPrice: '12.34',
    });

    expect(post.mock.calls[0][0]).toBe('/seller/edit');
    expect(submitted()).toEqual({
      auctionId: '7',
      title: 'A thing',
      description: 'Some description',
      quantity: '5',
      costPrice: '12.34',
    });
  });

  it('leaves an untouched cost price out entirely, so a recorded one is not wiped', () => {
    seller.editAuction({ auctionId: 7, title: 't', description: 'd', costPrice: undefined });
    expect(submittedKeys()).not.toContain('costPrice');
  });

  it('sends a cleared description as an empty parameter for the server to reject', () => {
    seller.editAuction({ auctionId: 7, title: 't', description: '' });
    expect(submitted().description).toBe('');
  });

  it('sends a zero cost price, which is how a seller clears one', () => {
    seller.editAuction({ auctionId: 7, title: 't', description: 'd', costPrice: '0' });
    expect(submitted().costPrice).toBe('0');
  });
});
