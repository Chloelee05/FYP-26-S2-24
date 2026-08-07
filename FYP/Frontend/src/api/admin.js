/**
 * Every call the admin console makes. Used only by the pages under src/pages/admin.
 *
 * All of these assume a signed-in account whose role is ADMIN. The check is server side:
 * each /api/admin/* servlet rejects a non-admin session with 403, so hiding the admin
 * routes in ProtectedRoute is convenience rather than the actual control.
 *
 * Most write endpoints are one POST per resource with an `action` field naming the
 * operation, which is why banUser and approveUser both post to /api/admin/users. The two
 * download helpers ask axios for a blob because the response is a file rather than JSON.
 */
import api from './config';

// Servlets read url-encoded fields, so object payloads become URLSearchParams.
const form = (obj) => {
  const p = new URLSearchParams();
  Object.entries(obj).forEach(([k, v]) => { if (v != null) p.append(k, v); });
  return p;
};
const F = { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } };

// Dashboard & analytics
/** GET /api/admin/dashboard. Headline counts for the admin landing page. */
export const getAdminDashboard = () => api.get('/admin/dashboard');
/** GET /api/admin/analytics. Time series behind the charts. */
export const getAdminAnalytics = () => api.get('/admin/analytics');
/** GET /api/admin/analytics/report. Returns a downloadable file, hence responseType blob. */
export const downloadAdminReport = (type) =>
  api.get(`/admin/analytics/report?type=${type}`, { responseType: 'blob' });

// Database management
/** GET /api/admin/database/status. Connection state, table counts and last backup time. */
export const getDatabaseStatus = () => api.get('/admin/database/status');
/** GET /api/admin/database/backup. Streams an SQL dump back as a blob. */
export const downloadDatabaseBackup = () =>
  api.get('/admin/database/backup', { responseType: 'blob' });
// POST /api/admin/database/restore. The body is the raw SQL text rather than form fields,
// so this call sets text/plain instead of the url-encoded type the rest of the file uses.
export const restoreDatabaseBackup = (sqlText) =>
  api.post('/admin/database/restore', sqlText, {
    headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
  });

// Seller analytics (admin-initiated). getSellerAnalyticsReport reads the report without
// sending anything, so requirement (d) is demonstrable on a server with no SMTP.
export const getSellerAnalyticsReport = (sellerId) =>
  api.get('/admin/sellers/analytics', { params: { sellerId } });
export const emailSellerAnalytics = (sellerId) =>
  api.post('/admin/sellers/analytics-email', form({ sellerId }), F);
export const emailAllSellerAnalytics = () =>
  api.post('/admin/sellers/analytics-email', form({ all: 'true' }), F);

// Admin management audit trail
export const getAdminAuditLog = (limit = 100) =>
  api.get('/admin/audit-log', { params: { limit } });

// Users. GET lists every account; the writes all POST to the same endpoint and differ
// only in the `action` field, matching how AdminUserServlet dispatches them.
export const getAdminUsers = () => api.get('/admin/users');
export const banUser     = (userid) => api.post('/admin/users', form({ action: 'suspend', userid }), F);
export const unbanUser   = (userid) => api.post('/admin/users', form({ action: 'unban',   userid }), F);
export const approveUser = (userid) => api.post('/admin/users', form({ action: 'approve', userid }), F);
export const rejectUser  = (userid) => api.post('/admin/users', form({ action: 'reject',  userid }), F);
// Soft delete to the Deleted status. Refused server-side while the account has a live
// listing or an unsettled order.
export const deactivateUser = (userid, reason) =>
  api.post('/admin/users', form({ action: 'deactivate', userid, reason }), F);

// Listings. Same one-endpoint-many-actions shape: FLAG marks a listing for review,
// REMOVE takes it out of the public browse, RESTORE puts it back, and FEATURE promotes it
// for a number of days.
export const getAdminListings  = () => api.get('/admin/listings');
export const flagListing    = (auctionId) => api.post('/admin/listings', form({ action: 'FLAG',    auctionId }), F);
export const removeListing  = (auctionId) => api.post('/admin/listings', form({ action: 'REMOVE',  auctionId }), F);
export const restoreListing = (auctionId) => api.post('/admin/listings', form({ action: 'RESTORE', auctionId }), F);
export const featureListing = (auctionId, days = 7) =>
  api.post('/admin/listings', form({ action: 'FEATURE', auctionId, days: String(days) }), F);
export const unfeatureListing = (auctionId) =>
  api.post('/admin/listings', form({ action: 'UNFEATURE', auctionId }), F);
export const getListingContent = (auctionId) =>
  api.get('/admin/listings/content', { params: { auctionId } });
// Content correction only — price and quantity stay with the seller, since a bid is an
// offer against a published price.
export const editListingContent = (auctionId, { title, description, category, listingKind, reason }) =>
  api.post('/admin/listings',
    form({ action: 'EDIT', auctionId, title, description, category, listingKind, reason }), F);
export const setListingKind = (auctionId, listingKind, reason) =>
  api.post('/admin/listings', form({ action: 'SET_KIND', auctionId, listingKind, reason }), F);

// Categories. Deleting is a soft delete, which is why there is a matching RESTORE:
// listings already filed under a category keep pointing at the row.
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

// Reports. `type` distinguishes a listing report from an account report, since the two
// live in separate tables but share one moderation queue. replyToReport builds its params
// by hand rather than through form() so an empty reply still reaches the server as a field.
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
export const saveRecommendationConfig = (settings) =>
  api.post('/admin/recommendations', form(settings), F);

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
// Reconciles a drifted order state. The amount is not editable: it is the settled sale
// value and feeds platform revenue.
export const correctOrderStatus = (orderId, status, reason) =>
  api.post('/admin/orders', form({ action: 'correct-status', orderId, status, reason }), F);
