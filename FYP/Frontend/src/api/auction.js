/**
 * Everything a buyer or a guest does with a listing: browse and search, read a detail
 * page, bid, watch, ask questions, rate and report. Used by Home, Search, AuctionDetail,
 * Watchlist, BiddingHistory and AuctionCard.
 *
 * The reads near the top of the file are public and work for a guest. Anything that
 * writes (bidding, watchlist, questions, ratings, reports) needs a session, which the
 * shared axios instance carries as a JSESSIONID cookie plus a bearer token, and the
 * servlet rejects the call with 401 if there is none.
 *
 * The three auction types share these endpoints. POST /api/bid does the work for all of
 * them and reads the auction type from the database: an ascending (PRICE_UP) listing
 * sends a bidAmount, a Dutch listing sends none because the server computes the current
 * clock price itself, and Buy It Now sends action=BUY_NOW. A blind listing takes a
 * bidAmount like an ascending one, but the server never reveals the competing amounts
 * until the auction closes.
 */
import api from './config';

// Servlets read url-encoded fields, so object payloads become URLSearchParams.
const form = (obj) => {
  const p = new URLSearchParams();
  Object.entries(obj).forEach(([k, v]) => { if (v != null) p.append(k, v); });
  return p;
};
const F = { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } };

// GET /api/stats. Public platform stats (landing page): live counts, fee schedule, testimonials
export const getPlatformStats = () => api.get('/stats');

// GET /api/landing-content. Public landing page copy, admin-editable: a flat
// { contentKey: text } map read from the landing_content table rather than hardcoded,
// so an admin can reword the hero and the card call to action without a redeploy.
// Fails soft server-side — an empty object means "use the built-in defaults".
export const getLandingContent = () => api.get('/landing-content');

// Search / browse
//
// Searching also records the keyword, which is what lets a recommendation later explain
// itself with "matches your search for …". It is posted from here rather than from the
// search endpoint so /api/search stays a pure read, and it is deduplicated so paging
// and unrelated filter changes do not re-log the same term. Terms shorter than
// MIN_KEYWORD_LENGTH are never sent: a single letter matches nearly every listing, so
// crediting a card to it would be a false explanation. Mirrors RecommendationDAO.
const MIN_KEYWORD_LENGTH = 2;
let lastRecordedKeyword = '';
export const searchAuctions = (params, config) => {
  const keyword = (params?.q ?? '').trim();
  if (keyword.length >= MIN_KEYWORD_LENGTH && keyword !== lastRecordedKeyword && (params?.page ?? 1) === 1) {
    lastRecordedKeyword = keyword;
    api.post('/recommendations/search-keyword', form({ q: keyword }), F).catch(() => {});
  }
  return api.get('/search', { ...config, params });
};
/** GET /api/categories. Active categories for the browse sidebar and the create form. */
export const getCategories   = ()       => api.get('/categories');
/** GET /api/auction/tags. Tag vocabulary used by the search filters. */
export const getTags         = ()       => api.get('/auction/tags');

// Personalised recommendations (collaborative filtering; trending fallback).
// Without a limit the server uses the admin-configured "items shown" setting.
export const getRecommendations = (limit) => api.get('/recommendations', { params: limit ? { limit } : {} });

// Trending auctions — never personalised, unlike /recommendations which switches to
// per-user picks once you are signed in.
export const getTrendingAuctions = (limit) =>
  api.get('/recommendations/trending', { params: limit ? { limit } : {} });

/** GET /api/featured. Listings a seller or an admin has paid to promote. */
export const getFeaturedListings = (limit = 8) => api.get('/featured', { params: { limit } });

// "Buyers who bid on this also bid on…" (auction detail page)
export const getSimilarAuctions = (auctionId, limit = 4) =>
  api.get('/recommendations/similar', { params: { auctionId, limit } });

// Hide a recommendation ("not interested")
export const dismissRecommendation = (auctionId) =>
  api.post('/recommendations/dismiss', form({ auctionId }), F);

// Recommendation analytics events (impressions batched as CSV; single click).
//
// `keyword` attributes the event to the search term that surfaced the card, so the
// "why this?" panel can show which keywords are actually driving each recommendation.
// `reasonCode` names the pipeline arm that produced the card (PEER_BIDS, SIMILAR_TASTE,
// SAME_CATEGORY, SEARCH_KEYWORD, TRENDING) or TRENDING_CONTROL for the landing page's
// separate popularity strip, so click-through can be reported per arm rather than pooled.
const IMPRESSION_SESSION_KEY = 'auctionhub.recImpressions';

/**
 * Drops impressions already recorded for the same card and arm in this browser session.
 *
 * Every home page load re-renders the whole strip and there is no viewport check, so
 * without this a refresh inflates the denominator behind every CTR figure. Session scope
 * under-counts a visitor who genuinely returns to the page repeatedly, which is the
 * deliberate trade: an honest floor beats a denominator anyone can pump with F5.
 */
function unseenThisSession(items) {
  try {
    const seen = new Set(JSON.parse(sessionStorage.getItem(IMPRESSION_SESSION_KEY) ?? '[]'));
    const fresh = items.filter(i => !seen.has(`${i.auctionId}:${i.reasonCode ?? ''}`));
    fresh.forEach(i => seen.add(`${i.auctionId}:${i.reasonCode ?? ''}`));
    sessionStorage.setItem(IMPRESSION_SESSION_KEY, JSON.stringify([...seen]));
    return fresh;
  } catch {
    // Storage blocked (private browsing, quota). Recording twice beats recording nothing.
    return items;
  }
}

/** `items` is [{ auctionId, reasonCode }]; one request per arm present in the batch. */
export const recordRecommendationImpressions = (items) => {
  const byReason = new Map();
  for (const item of unseenThisSession(items)) {
    const arm = item.reasonCode ?? '';
    if (!byReason.has(arm)) byReason.set(arm, []);
    byReason.get(arm).push(item.auctionId);
  }
  return Promise.all([...byReason].map(([reasonCode, auctionIds]) =>
    api.post('/recommendations/events', form({
      type: 'impression', auctionIds: auctionIds.join(','), reasonCode: reasonCode || null,
    }), F)));
};

export const recordRecommendationClick = (auctionId, keyword, reasonCode) =>
  api.post('/recommendations/events', form({ type: 'click', auctionId, keyword, reasonCode }), F);

// Auction detail. Both take an optional axios config so the 4s price poll on the
// detail page can abort in-flight requests when it tears down.
//
// GET /api/auction/{id} returns the listing plus its type. For a Dutch listing the
// currentPrice in the payload is the clock price at the moment the server answered,
// which is why the poll exists and why the card and the detail page agree.
export const getAuctionDetail = (id, config) => api.get(`/auction/${id}`, config);
/** GET /api/auction/{id}/bids. On a blind listing the server withholds rival amounts until close. */
export const getAuctionBids   = (id, params, config) => api.get(`/auction/${id}/bids`, { ...config, params });
/** GET /api/auction/{id}/questions. Public buyer questions with any seller replies. */
export const getAuctionQuestions = (id) => api.get(`/auction/${id}/questions`);

// Bidding
/**
 * POST /api/bid with auctionId and bidAmount. Used by ascending (PRICE_UP) and blind
 * listings. The server validates the amount against the increment and the reserve, so a
 * bid rejected there comes back as an error rather than being filtered in the browser.
 * Requires a session.
 */
export const placeBid = (auctionId, bidAmount) =>
  api.post('/bid', form({ auctionId, bidAmount }), F);

// Dutch auction: accept the current descending clock price (server computes the amount).
// POST /api/bid with only an auctionId. Sending no amount is what tells the servlet to
// price the acceptance itself, so a stale price on screen cannot be bought at.
export const acceptDutchPrice = (auctionId) =>
  api.post('/bid', form({ auctionId }), F);

// Buy It Now (standard ascending auctions with a BIN price).
// POST /api/bid with action=BUY_NOW; the server closes the listing at the BIN price.
export const buyItNow = (auctionId) =>
  api.post('/bid', form({ auctionId, action: 'BUY_NOW' }), F);

/**
 * POST /api/auto-bid with action=SET. The server bids on the user's behalf up to
 * maxAmount. Ascending listings only: a Dutch listing has no rival to outbid, and a
 * blind listing has nothing visible to react to, so the detail page hides the control
 * for both.
 */
export const setAutoBid = (auctionId, maxAmount, note, bidIncrement) =>
  api.post('/auto-bid', form({ auctionId, action: 'SET', maxAmount, note, bidIncrement }), F);

/** POST /api/auto-bid with action=CANCEL. Stops the proxy bidding, keeps bids already placed. */
export const cancelAutoBid = (auctionId) =>
  api.post('/auto-bid', form({ auctionId, action: 'CANCEL' }), F);

// Watchlist. All four need a session; the two writes go to the same endpoint and are
// told apart by the `action` field.
export const getWatchlist = () => api.get('/watchlist');

/** Whether one auction is on the caller's watchlist — avoids fetching the whole list. */
export const checkWatching = (auctionId) =>
  api.get('/watchlist', { params: { auctionId } });
export const addToWatchlist = (auctionId) =>
  api.post('/watchlist', form({ auctionId, action: 'add' }), F);
export const removeFromWatchlist = (auctionId) =>
  api.post('/watchlist', form({ auctionId, action: 'remove' }), F);

// Bidding history (buyer). GET /api/bidding-history, scoped server-side to the signed-in
// account. `params` carries the paging and status filters the page offers.
export const getBiddingHistory = (params) => api.get('/bidding-history', { params });

// Q&A. POST /api/question/ask posts a public question on a listing; the seller answers
// through seller.replyToQuestion.
export const askQuestion = (auctionId, text) =>
  api.post('/question/ask', form({ auctionId, text }), F);

// Rating / reporting.
// POST /api/rate leaves a 1 to 5 star review on the seller of an auction the caller won.
export const rateSeller = (auctionId, score, comment) =>
  api.post('/rate', form({ auctionId, score, comment }), F);

/** GET /api/rate/check. Whether this account already rated the seller, so the button hides. */
export const checkSellerRated = (auctionId) =>
  api.get('/rate/check', { params: { auctionId } });

// Reviews written by the current user (edit/delete within 24h of posting)
export const getMyWrittenReviews = () => api.get('/rate/mine');
export const updateMyReview = (reviewId, score, comment) =>
  api.post('/rate/update', form({ reviewId, score, comment }), F);
export const deleteMyReview = (reviewId) =>
  api.post('/rate/delete', form({ reviewId }), F);

/** POST /api/report. Flags a listing for admin moderation (see ReportModal). */
export const reportListing = (auctionId, description) =>
  api.post('/report', form({ auctionId, description }), F);

/** POST /api/report/user. Flags an account rather than a listing. */
export const reportUser = ({ reportedId, reason }) =>
  api.post('/report/user', form({ reportedId, reason }), F);

// GET /api/report/mine. Reports the current user has submitted (with status + admin reply)
export const getMyReports = () => api.get('/report/mine');

// GET /api/seller/{id}. Public seller profile: rating, review sample and listing counts.
// Readable by a guest, unlike everything else under /api/seller.
export const getSellerProfile = (sellerId) => api.get(`/seller/${sellerId}`);
