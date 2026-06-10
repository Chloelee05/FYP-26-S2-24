import api from './config';

const form = (obj) => {
  const p = new URLSearchParams();
  Object.entries(obj).forEach(([k, v]) => { if (v != null) p.append(k, v); });
  return p;
};
const F = { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } };

// Search / browse
export const searchAuctions = (params) => api.get('/search', { params });
export const getCategories   = ()       => api.get('/categories');
export const getTags         = ()       => api.get('/auction/tags');

// Personalised recommendations (collaborative filtering; trending fallback)
export const getRecommendations = (limit = 8) => api.get('/recommendations', { params: { limit } });

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

export const setAutoBid = (auctionId, maxAmount, note) =>
  api.post('/auto-bid', form({ auctionId, action: 'SET', maxAmount, note }), F);

export const cancelAutoBid = (auctionId) =>
  api.post('/auto-bid', form({ auctionId, action: 'CANCEL' }), F);

// Watchlist
export const getWatchlist = () => api.get('/watchlist');
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

export const reportListing = (auctionId, description) =>
  api.post('/report', form({ auctionId, description }), F);

export const reportUser = ({ reportedId, reason }) =>
  api.post('/report/user', form({ reportedId, reason }), F);

// Seller public profile
export const getSellerProfile = (sellerId) => api.get(`/seller/${sellerId}`);
