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

// Seller dashboard summary
export const getSellerDashboard = () => api.get('/seller/dashboard');

// Seller's own auction list
export const getSellerAuctions = (params) => api.get('/seller/auctions', { params });

// Get auction data for edit form
export const getAuctionForEdit = (auctionId) => api.get(`/seller/${auctionId}/edit`);

// Upload a single auction listing image, returns { imageUrl }
export const uploadAuctionImage = (file) => {
  return api.post('/auction/upload-image', file, {
    headers: { 'Content-Type': file.type },
  });
};

// Create auction (imageUrls is optional array of pre-uploaded URL strings)
export const createAuction = (data) => api.post('/seller/create', form(data), F);

// Cancel / relist auction
export const cancelAuction = (auctionId, reason) =>
  api.post('/seller/cancel', form({ auctionId, reason }), F);

export const relistAuction = (auctionId) =>
  api.post('/seller/relist', form({ auctionId }), F);

// Edit auction (deleteImageIds and newImageUrls are optional arrays)
export const editAuction = (data) => api.post('/seller/edit', form(data), F);

// Reply to a buyer question
export const replyToQuestion = (questionId, text) =>
  api.post('/question/reply', form({ questionId, text }), F);

// Rate the winning buyer of a finished auction
export const rateBuyer = (auctionId, score, comment) =>
  api.post('/seller/rate-buyer', form({ auctionId, score, comment }), F);

// Seller performance analytics
export const getSellerAnalytics = () => api.get('/seller/analytics');
export const emailSellerAnalytics = () => api.post('/seller/analytics', form({}), F);
