/**
 * What a member does with their own listings: create, edit, cancel, relist, promote,
 * answer questions and read their sales analytics. Used by the seller pages.
 *
 * All of these need a session whose account has the `canSell` capability. Selling is a
 * capability on the ordinary member account rather than a separate role, so a buyer who
 * has never sold gets EnableSellingGate first and these calls only fire afterwards. The
 * server also checks that the auction being changed belongs to the caller.
 *
 * This file's form() differs from the one in the other API modules: it appends an array
 * as repeated fields with the same name, which is how imageUrls and deleteImageIds reach
 * request.getParameterValues() on the servlet side.
 */
import api from './config';

const form = (obj) => {
  const p = new URLSearchParams();
  Object.entries(obj).forEach(([k, v]) => {
    if (v == null) return;
    if (Array.isArray(v)) v.forEach((item) => p.append(k, item));
    else p.append(k, v);
  });
  return p;
};
const F = { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } };

// Seller's own auction list. Every argument is optional; the server paginates, filters by
// bucket (ACTIVE / FINISHED / UNSOLD / CANCELLED), searches by title and sorts, and returns
// { auctions, total, page, size, totalPages, bucket, counts } — see SellerApiServlet.
// params: { bucket, q, sort, page, size } — all optional. config carries an AbortSignal so a
// superseded page or search can be cancelled instead of landing after a newer one.
export const getSellerAuctions = (params, config) =>
  api.get('/seller/auctions', { params, ...config });

// GET /api/seller/{auctionId}/edit. Get auction data for edit form. Refused unless the
// listing belongs to the caller.
export const getAuctionForEdit = (auctionId) => api.get(`/seller/${auctionId}/edit`);

// POST /api/auction/upload-image. Upload a single auction listing image, returns { imageUrl }.
// The File object is sent as the raw request body with its own MIME type, not as multipart
// form data, so the servlet can stream it straight to disk. Images are uploaded first and
// their returned URLs are then submitted with the listing.
export const uploadAuctionImage = (file) => {
  return api.post('/auction/upload-image', file, {
    headers: { 'Content-Type': file.type },
  });
};

// POST /api/seller/create. `data` carries the title, description, category, listingKind,
// auction type (PRICE_UP, DUTCH_AUCTION or BLIND), pricing and end date.
// imageUrls is an optional array of pre-uploaded URL strings.
export const createAuction = (data) => api.post('/seller/create', form(data), F);

// POST /api/seller/cancel. Withdraws a live listing. The reason is stored and sent to
// anyone who had already bid, which is why it is not optional in the UI.
export const cancelAuction = (auctionId, reason) =>
  api.post('/seller/cancel', form({ auctionId, reason }), F);

// POST /api/seller/relist. Opens a fresh listing from an unsold one, keeping its details.
export const relistAuction = (auctionId) =>
  api.post('/seller/relist', form({ auctionId }), F);

// POST /api/seller/feature. Feature own listing for `days` (flat fee, simulated billing).
// An admin can also feature a listing through admin.featureListing, without the fee.
export const featureOwnAuction = (auctionId, days) =>
  api.post('/seller/feature', form({ auctionId, days }), F);

// POST /api/seller/edit (deleteImageIds and newImageUrls are optional arrays).
// What may be changed narrows once bidding has started, since a bid is an offer against
// the published terms; the server is what enforces that.
export const editAuction = (data) => api.post('/seller/edit', form(data), F);

// POST /api/question/reply. Reply to a buyer question. Both the question and the reply
// are public on the listing page.
export const replyToQuestion = (questionId, text) =>
  api.post('/question/reply', form({ questionId, text }), F);

// POST /api/seller/rate-buyer. Rate the winning buyer of a finished auction. The mirror
// of auction.rateSeller, so both sides of a completed sale can rate each other.
export const rateBuyer = (auctionId, score, comment) =>
  api.post('/seller/rate-buyer', form({ auctionId, score, comment }), F);

// Seller performance analytics for the signed-in seller. GET reads the figures; POST to
// the same path emails them, which is the seller-initiated twin of the admin's
// admin.emailSellerAnalytics.
export const getSellerAnalytics = () => api.get('/seller/analytics');
export const emailSellerAnalytics = () => api.post('/seller/analytics', form({}), F);

// Remove one unit from a listing. Pass a reason when this is the last unit: removing it
// cancels the listing, and the reason is stored and sent to the bidders.
export const removeAuctionUnit = (auctionId, reason) =>
  api.post('/seller/reduce-quantity', form({ auctionId, reason }), F);
