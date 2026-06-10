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

// Users
export const getAdminUsers = () => api.get('/admin/users');
export const banUser   = (userid) => api.post('/admin/users', form({ action: 'suspend', userid }), F);
export const unbanUser = (userid) => api.post('/admin/users', form({ action: 'unban',   userid }), F);

// Listings
export const getAdminListings  = () => api.get('/admin/listings');
export const flagListing    = (auctionId) => api.post('/admin/listings', form({ action: 'FLAG',    auctionId }), F);
export const removeListing  = (auctionId) => api.post('/admin/listings', form({ action: 'REMOVE',  auctionId }), F);
export const restoreListing = (auctionId) => api.post('/admin/listings', form({ action: 'RESTORE', auctionId }), F);

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

// Reports
export const getAdminReports  = () => api.get('/admin/reports');
export const resolveReport    = (reportId, type) => api.post('/admin/reports', form({ reportId, type, action: 'resolve' }), F);
export const dismissReport    = (reportId, type) => api.post('/admin/reports', form({ reportId, type, action: 'dismiss' }), F);
