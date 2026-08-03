import { describe, it, expect } from 'vitest';
import {
  isUnsold, isCancelled, listingStatusLabel, listingPrice, groupListings,
} from './listings';

const listing = (over = {}) => ({
  auctionId: 1,
  statusName: 'FINISHED',
  bidCount: 0,
  startingPrice: 2560,
  currentBid: 0,
  ...over,
});

describe('unsold is not cancelled', () => {
  // The audit's finding: a listing nobody bid on and a listing the seller withdrew were
  // shown in the same tab under the same word, so requirement (d) could not be verified.
  it('an auction that ended with no bids is unsold', () => {
    expect(isUnsold(listing())).toBe(true);
    expect(isCancelled(listing())).toBe(false);
  });

  it('an auction the seller withdrew is cancelled, never unsold', () => {
    const withdrawn = listing({ statusName: 'CANCELLED' });
    expect(isCancelled(withdrawn)).toBe(true);
    expect(isUnsold(withdrawn)).toBe(false);
  });

  it('a withdrawn auction that had bids is still only cancelled', () => {
    const withdrawn = listing({ statusName: 'CANCELLED', bidCount: 4 });
    expect(isCancelled(withdrawn)).toBe(true);
    expect(isUnsold(withdrawn)).toBe(false);
  });

  it('an auction that ended with bids is neither – it sold', () => {
    const sold = listing({ bidCount: 3, currentBid: 3000 });
    expect(isUnsold(sold)).toBe(false);
    expect(isCancelled(sold)).toBe(false);
  });

  it('a live auction with no bids yet is not unsold', () => {
    expect(isUnsold(listing({ statusName: 'ACTIVE' }))).toBe(false);
  });

  it('reads the status case-insensitively', () => {
    expect(isUnsold(listing({ statusName: 'Finished' }))).toBe(true);
    expect(isCancelled(listing({ statusName: 'Cancelled' }))).toBe(true);
  });

  it('survives a listing with no status at all', () => {
    expect(isUnsold({})).toBe(false);
    expect(isCancelled({})).toBe(false);
  });
});

describe('listingStatusLabel', () => {
  it('labels an unsold auction UNSOLD rather than CANCELLED', () => {
    expect(listingStatusLabel(listing())).toBe('UNSOLD');
  });

  it('leaves every other status as the record says', () => {
    expect(listingStatusLabel(listing({ statusName: 'CANCELLED' }))).toBe('CANCELLED');
    expect(listingStatusLabel(listing({ statusName: 'ACTIVE' }))).toBe('ACTIVE');
    expect(listingStatusLabel(listing({ statusName: 'FINISHED', bidCount: 2 }))).toBe('FINISHED');
  });
});

describe('listingPrice', () => {
  // Auction 6 in production: starting_price 2560, no bids, displayed as $0.00.
  it('shows the starting price when nobody has bid', () => {
    expect(listingPrice(listing({ currentBid: 0, startingPrice: 2560 }))).toBe(2560);
  });

  it('shows the top bid once there is one', () => {
    expect(listingPrice(listing({ bidCount: 1, currentBid: 3000, startingPrice: 2560 }))).toBe(3000);
  });

  it('keeps the cents of a bid', () => {
    expect(listingPrice(listing({ currentBid: 33.77 }))).toBe(33.77);
  });

  it('is zero only when there is genuinely no price to show', () => {
    expect(listingPrice({})).toBe(0);
  });

  it('copes with the strings an API can return', () => {
    expect(listingPrice({ currentBid: '0', startingPrice: '2560.00' })).toBe(2560);
  });
});

describe('groupListings', () => {
  const rows = [
    listing({ auctionId: 1, statusName: 'ACTIVE' }),
    listing({ auctionId: 2, statusName: 'PENDING' }),
    listing({ auctionId: 3, statusName: 'FINISHED', bidCount: 2 }),
    listing({ auctionId: 4, statusName: 'FINISHED', bidCount: 0 }),
    listing({ auctionId: 5, statusName: 'CANCELLED' }),
  ];

  it('separates the four outcomes', () => {
    const g = groupListings(rows);
    expect(g.active.map(a => a.auctionId)).toEqual([1, 2]);
    expect(g.finished.map(a => a.auctionId)).toEqual([3]);
    expect(g.unsold.map(a => a.auctionId)).toEqual([4]);
    expect(g.cancelled.map(a => a.auctionId)).toEqual([5]);
  });

  it('never counts one listing in two buckets', () => {
    const g = groupListings(rows);
    const total = g.active.length + g.finished.length + g.unsold.length + g.cancelled.length;
    expect(total).toBe(rows.length);
  });

  it('handles an empty catalogue', () => {
    const g = groupListings([]);
    expect(g).toEqual({ active: [], finished: [], unsold: [], cancelled: [] });
  });
});
