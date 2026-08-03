/**
 * Shared vocabulary for the product/service discriminator on a listing
 * (`auction_details.listing_kind`, PRODUCT or SERVICE).
 *
 * The minimum requirements name services alongside products, so the same two values now
 * appear on the seller's create and edit forms, in the seller's own listing table, on the
 * public auction page and in admin listing management. They live here so those five surfaces
 * cannot disagree about what a service is called or how a legacy row with no kind is read.
 */

/** The values the backend accepts, in the order the forms offer them. */
export const LISTING_KINDS = ['PRODUCT', 'SERVICE'];

/**
 * Reads any stored, submitted or missing value as one of the two.
 *
 * Anything that is not a service is a product — including null, which is what a row created
 * before the column existed and an older API response both look like.
 */
export const normalizeListingKind = (kind) =>
  String(kind ?? '').trim().toUpperCase() === 'SERVICE' ? 'SERVICE' : 'PRODUCT';

export const isService = (kind) => normalizeListingKind(kind) === 'SERVICE';

/** Sentence-case label for display. */
export const listingKindLabel = (kind) => (isService(kind) ? 'Service' : 'Product');

/** Badge colours, so a service reads the same wherever it is shown. */
export const LISTING_KIND_STYLE = {
  PRODUCT: 'bg-sky-50 text-sky-700 ring-sky-200',
  SERVICE: 'bg-violet-50 text-violet-700 ring-violet-200',
};

/**
 * What a buyer needs to be told when the listing is not a physical object. Shown on the
 * public auction page, where the decision to bid is actually made.
 */
export const SERVICE_BUYER_NOTE =
  'This is a service, not a physical item — nothing will be shipped. '
  + 'Arrange the details with the seller after the auction closes.';
