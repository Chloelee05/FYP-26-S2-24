/**
 * Shared vocabulary for a seller's own listings. My listings and the seller
 * dashboard both group and count the same rows, so the rules live here rather
 * than in each page, where they could drift apart.
 *
 * statusName mirrors the AuctionStatus enum: "ACTIVE", "FINISHED", "CANCELLED", "PENDING".
 *
 * The buckets here match SellerAuctionDAO.ListingBucket exactly, because My listings now
 * asks the server for one bucket at a time so that it can paginate. These helpers stay for
 * the pages that hold a whole array in memory.
 *
 * This file is about a seller's view of their own listings. The buyer-facing labels for an
 * order live in utils/orders.js, and the product versus service wording in utils/listingKind.js.
 */

/**
 * An auction whose clock ran out without a single bid never sold. It is its own outcome:
 * not "finished" (nothing happened), and emphatically not "cancelled" (nobody decided
 * anything). It used to be labelled CANCELLED, which put it in the same tab under the same
 * word as a listing the seller had deliberately withdrawn — leaving no way to tell, from
 * the seller UI, which of the two had happened to any given listing.
 */
export const isUnsold = (a) =>
  a.statusName?.toUpperCase() === 'FINISHED' && !(a.bidCount > 0);

/** True only for a listing the seller (or an admin) actually withdrew. */
export const isCancelled = (a) => a.statusName?.toUpperCase() === 'CANCELLED';

/** The status to show for a listing, which is not always the one on the record. */
export const listingStatusLabel = (a) => (isUnsold(a) ? 'UNSOLD' : a.statusName);

/**
 * The price to show for a listing: the top bid once there is one, otherwise the price the
 * seller opened at. `currentBid` arrives as COALESCE(MAX(bid_amount), 0), so a listing with
 * no bids used to render as $0.00 — which reads as "worthless" rather than "not yet bid on",
 * and is not a figure that appears anywhere in the listing.
 */
export const listingPrice = (a) => {
  const bid = Number(a.currentBid ?? 0);
  return bid > 0 ? bid : Number(a.startingPrice ?? 0);
};

/** Splits a seller's listings into the four buckets the pages present. */
export function groupListings(auctions) {
  const s = (a) => a.statusName?.toUpperCase();
  return {
    active:    auctions.filter(a => s(a) === 'ACTIVE' || s(a) === 'PENDING'),
    finished:  auctions.filter(a => s(a) === 'FINISHED' && !isUnsold(a)),
    unsold:    auctions.filter(a => isUnsold(a)),
    cancelled: auctions.filter(a => isCancelled(a)),
  };
}
