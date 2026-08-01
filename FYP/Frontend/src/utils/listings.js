/**
 * Shared vocabulary for a seller's own listings. My listings and the seller
 * dashboard both group and count the same rows, so the rules live here rather
 * than in each page, where they could drift apart.
 *
 * statusName mirrors the AuctionStatus enum: "ACTIVE", "FINISHED", "CANCELLED", "PENDING".
 */

/**
 * An auction whose clock ran out without a single bid never sold, so calling it
 * "finished" overstates what happened — it is grouped and labelled as cancelled.
 * This is presentation only; the stored status is still FINISHED.
 */
export const isUnsold = (a) =>
  a.statusName?.toUpperCase() === 'FINISHED' && !(a.bidCount > 0);

/** The status to show for a listing, which is not always the one on the record. */
export const listingStatusLabel = (a) => (isUnsold(a) ? 'CANCELLED' : a.statusName);

/** Splits a seller's listings into the three buckets both pages present. */
export function groupListings(auctions) {
  const s = (a) => a.statusName?.toUpperCase();
  return {
    active:    auctions.filter(a => s(a) === 'ACTIVE' || s(a) === 'PENDING'),
    finished:  auctions.filter(a => s(a) === 'FINISHED' && !isUnsold(a)),
    cancelled: auctions.filter(a => s(a) === 'CANCELLED' || isUnsold(a)),
  };
}
