import api from './config';

const form = (obj) => {
  const p = new URLSearchParams();
  Object.entries(obj).forEach(([k, v]) => { if (v != null) p.append(k, v); });
  return p;
};
const F = { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } };

// Public platform stats (landing page): live counts, fee schedule, testimonials
export const getPlatformStats = () => api.get('/stats');

// Public landing page copy, admin-editable: a flat { contentKey: text } map.
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
export const searchAuctions = (params) => {
  const keyword = (params?.q ?? '').trim();
  if (keyword.length >= MIN_KEYWORD_LENGTH && keyword !== lastRecordedKeyword && (params?.page ?? 1) === 1) {
    lastRecordedKeyword = keyword;
    api.post('/recommendations/search-keyword', form({ q: keyword }), F).catch(() => {});
  }
  return api.get('/search', { params });
};
export const getCategories   = ()       => api.get('/categories');
export const getTags         = ()       => api.get('/auction/tags');

// Personalised recommendations (collaborative filtering; trending fallback).
// Without a limit the server uses the admin-configured "items shown" setting.
export const getRecommendations = (limit) => api.get('/recommendations', { params: limit ? { limit } : {} });

// Trending auctions — never personalised, unlike /recommendations which switches to
// per-user picks once you are signed in.
export const getTrendingAuctions = (limit) =>
  api.get('/recommendations/trending', { params: limit ? { limit } : {} });

export const getFeaturedListings = (limit = 8) => api.get('/featured', { params: { limit } });

// "Buyers who bid on this also bid on…" (auction detail page)
export const getSimilarAuctions = (auctionId, limit = 4) =>
  api.get('/recommendations/similar', { params: { auctionId, limit } });

// Hide a recommendation ("not interested")
export const dismissRecommendation = (auctionId) =>
  api.post('/recommendations/dismiss', form({ auctionId }), F);

// Recommendation analytics events (impressions batched as CSV; single click).
// `keyword` attributes the event to the search term that surfaced the card, so the
// "why this?" panel can show which keywords are actually driving each recommendation.
export const recordRecommendationImpressions = (auctionIds) =>
  api.post('/recommendations/events', form({ type: 'impression', auctionIds: auctionIds.join(',') }), F);
export const recordRecommendationClick = (auctionId, keyword) =>
  api.post('/recommendations/events', form({ type: 'click', auctionId, keyword }), F);

// Auction detail
export const getAuctionDetail = (id) => api.get(`/auction/${id}`);
export const getAuctionBids   = (id, params) => api.get(`/auction/${id}/bids`, { params });
export const getAuctionQuestions = (id) => api.get(`/auction/${id}/questions`);

// Bidding
export const placeBid = (auctionId, bidAmount) =>
  api.post('/bid', form({ auctionId, bidAmount }), F);

// Dutch auction: accept the current descending clock price (server computes the amount)
export const acceptDutchPrice = (auctionId) =>
  api.post('/bid', form({ auctionId }), F);

// Buy It Now (standard ascending auctions with a BIN price)
export const buyItNow = (auctionId) =>
  api.post('/bid', form({ auctionId, action: 'BUY_NOW' }), F);

export const setAutoBid = (auctionId, maxAmount, note, bidIncrement) =>
  api.post('/auto-bid', form({ auctionId, action: 'SET', maxAmount, note, bidIncrement }), F);

export const cancelAutoBid = (auctionId) =>
  api.post('/auto-bid', form({ auctionId, action: 'CANCEL' }), F);

// Watchlist
export const getWatchlist = () => api.get('/watchlist');

/** Whether one auction is on the caller's watchlist — avoids fetching the whole list. */
export const checkWatching = (auctionId) =>
  api.get('/watchlist', { params: { auctionId } });
export const addToWatchlist = (auctionId) =>
  api.post('/watchlist', form({ auctionId, action: 'add' }), F);
export const removeFromWatchlist = (auctionId) =>
  api.post('/watchlist', form({ auctionId, action: 'remove' }), F);

// Bidding history (buyer)
export const getBiddingHistory = (params) => api.get('/bidding-history', { params });

// Q&A
export const askQuestion = (auctionId, text) =>
  api.post('/question/ask', form({ auctionId, text }), F);

// Rating / reporting
export const rateSeller = (auctionId, score, comment) =>
  api.post('/rate', form({ auctionId, score, comment }), F);

export const checkSellerRated = (auctionId) =>
  api.get('/rate/check', { params: { auctionId } });

// Reviews written by the current user (edit/delete within 24h of posting)
export const getMyWrittenReviews = () => api.get('/rate/mine');
export const updateMyReview = (reviewId, score, comment) =>
  api.post('/rate/update', form({ reviewId, score, comment }), F);
export const deleteMyReview = (reviewId) =>
  api.post('/rate/delete', form({ reviewId }), F);

export const reportListing = (auctionId, description) =>
  api.post('/report', form({ auctionId, description }), F);

export const reportUser = ({ reportedId, reason }) =>
  api.post('/report/user', form({ reportedId, reason }), F);

// Reports the current user has submitted (with status + admin reply)
export const getMyReports = () => api.get('/report/mine');

// Seller public profile
export const getSellerProfile = (sellerId) => api.get(`/seller/${sellerId}`);
