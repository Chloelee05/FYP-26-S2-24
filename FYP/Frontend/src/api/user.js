import api from './config';

const form = (obj) => {
  const p = new URLSearchParams();
  Object.entries(obj).forEach(([k, v]) => { if (v != null) p.append(k, v); });
  return p;
};
const F = { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } };

export const getProfile             = ()     => api.get('/account');
export const getTransactionHistory  = (filter) => api.get('/account/transactions', { params: { filter } });
export const getRatingSummary       = ()     => api.get('/account/rating');
export const getMyReviews           = ()     => api.get('/account/reviews');

export const updateProfile = (data) =>
  api.post('/account/update', form(data), F);

export const uploadProfilePhoto = (file) => {
  return api.post('/account/upload-photo', file, {
    headers: { 'Content-Type': file.type },
  });
};

export const deleteAccount = () =>
  api.post('/account/delete', form({ confirm: 'DELETE' }), F);

// Payment methods (PAN stored AES-GCM encrypted server-side)
export const getPaymentMethods = () => api.get('/account/payment-methods');
export const addPaymentMethod = (data) =>
  api.post('/account/payment-methods', form({ action: 'add', ...data }), F);
export const deletePaymentMethod = (id) =>
  api.post('/account/payment-methods', form({ action: 'delete', id }), F);
export const setDefaultPaymentMethod = (id) =>
  api.post('/account/payment-methods', form({ action: 'default', id }), F);
