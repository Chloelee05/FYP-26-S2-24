import api from './config';

const form = (obj) => {
  const p = new URLSearchParams();
  Object.entries(obj).forEach(([k, v]) => { if (v != null) p.append(k, v); });
  return p;
};
const F = { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } };

// Dashboard & analytics
export const getAdminDashboard = () => api.get('/admin/dashboard');
export const getAdminAnalytics = () => api.get('/admin/analytics');
export const downloadAdminReport = (type) =>
  api.get(`/admin/analytics/report?type=${type}`, { responseType: 'blob' });

// Database management
export const getDatabaseStatus = () => api.get('/admin/database/status');
export const downloadDatabaseBackup = () =>
  api.get('/admin/database/backup', { responseType: 'blob' });
export const restoreDatabaseBackup = (sqlText) =>
  api.post('/admin/database/restore', sqlText, {
    headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
  });

// Seller analytics email (admin-initiated)
export const emailSellerAnalytics = (sellerId) =>
  api.post('/admin/sellers/analytics-email', form({ sellerId }), F);
export const emailAllSellerAnalytics = () =>
  api.post('/admin/sellers/analytics-email', form({ all: 'true' }), F);

// Users
export const getAdminUsers = () => api.get('/admin/users');
export const banUser     = (userid) => api.post('/admin/users', form({ action: 'suspend', userid }), F);
export const unbanUser   = (userid) => api.post('/admin/users', form({ action: 'unban',   userid }), F);
export const approveUser = (userid) => api.post('/admin/users', form({ action: 'approve', userid }), F);
export const rejectUser  = (userid) => api.post('/admin/users', form({ action: 'reject',  userid }), F);

// Listings
export const getAdminListings  = () => api.get('/admin/listings');
export const flagListing    = (auctionId) => api.post('/admin/listings', form({ action: 'FLAG',    auctionId }), F);
export const removeListing  = (auctionId) => api.post('/admin/listings', form({ action: 'REMOVE',  auctionId }), F);
export const restoreListing = (auctionId) => api.post('/admin/listings', form({ action: 'RESTORE', auctionId }), F);
export const featureListing = (auctionId, days = 7) =>
  api.post('/admin/listings', form({ action: 'FEATURE', auctionId, days: String(days) }), F);
export const unfeatureListing = (auctionId) =>
  api.post('/admin/listings', form({ action: 'UNFEATURE', auctionId }), F);

// Categories
export const getAdminCategories = () => api.get('/admin/categories');
export const createCategory = (data) =>
  api.post('/admin/categories', form({ ...data, action: 'CREATE' }), F);
export const editCategory = (categoryId, data) =>
  api.post('/admin/categories', form({ ...data, categoryId, action: 'EDIT' }), F);
export const deleteCategory = (categoryId) =>
  api.post('/admin/categories', form({ categoryId, action: 'DELETE' }), F);
export const restoreCategory = (categoryId) =>
  api.post('/admin/categories', form({ categoryId, action: 'RESTORE' }), F);

// Landing page content (hero copy, section headings, CTA text).
// saveLandingContent takes a { contentKey: text } object — each key is sent as its own
// form field, and the server rejects any key that is not a seeded content row.
export const getAdminLandingContent = () => api.get('/admin/landing-content');
export const saveLandingContent = (values) =>
  api.post('/admin/landing-content', form(values), F);
export const resetLandingContentField = (key) =>
  api.post('/admin/landing-content', form({ action: 'RESET', key }), F);
export const resetLandingContentGroup = (group) =>
  api.post('/admin/landing-content', form({ action: 'RESET', group }), F);

// Reports
export const getAdminReports  = () => api.get('/admin/reports');
export const resolveReport    = (reportId, type) => api.post('/admin/reports', form({ reportId, type, action: 'resolve' }), F);
export const dismissReport    = (reportId, type) => api.post('/admin/reports', form({ reportId, type, action: 'dismiss' }), F);
export const replyToReport = (reportId, type, reply) => {
  const p = new URLSearchParams();
  p.append('reportId', String(reportId));
  p.append('type', type || 'account');
  p.append('action', 'reply');
  p.append('reply', reply);
  return api.post('/admin/reports', p.toString(), F);
};

// Reviews (moderation)
export const getAdminReviews = () => api.get('/admin/reviews');
export const adminDeleteReview = (reviewId) =>
  api.post('/admin/reviews', form({ action: 'delete', reviewId }), F);

// Recommendation system (performance metrics + tunable parameters)
export const getRecommendationConfig = () => api.get('/admin/recommendations');
export const saveRecommendationConfig = (itemsShown, similarityThreshold) =>
  api.post('/admin/recommendations', form({ itemsShown, similarityThreshold }), F);

// Recommendation provenance. This is the only surface that returns *which* user clicked
// or searched — the public landing page gets aggregates and masked names only, so this
// endpoint is ADMIN-gated server side. Without an auctionId it returns the leaderboard.
export const getRecommendationAttribution = (auctionId, limit = 25) =>
  api.get('/recommendations/attribution', { params: { auctionId, limit } });

// Orders / transactions
export const getAdminOrders = () => api.get('/admin/orders');
// Dispute resolution: admin overrides the seller on a pending refund request
export const adminResolveRefund = (orderId, approve) =>
  api.post('/admin/orders', form({ action: approve ? 'refund-approve' : 'refund-decline', orderId }), F);
