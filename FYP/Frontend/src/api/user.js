import api from './config';

const form = (obj) => {
  const p = new URLSearchParams();
  Object.entries(obj).forEach(([k, v]) => { if (v != null) p.append(k, v); });
  return p;
};
const F = { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } };

export const getProfile             = ()     => api.get('/account');
export const getTransactionHistory  = (filter) => api.get('/account/transactions', { params: { filter } });
export const getMyReviews           = ()     => api.get('/account/reviews');

export const updateProfile = (data) =>
  api.post('/account/update', form(data), F);

/** Turns on the selling capability for the signed-in buyer (one merged account). */
export const enableSelling = () =>
  api.post('/account/enable-selling', form({}), F);

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

/**
 * Edits a saved method in place. The server decides which fields are allowed from the
 * stored method_type, so `data` carries only the editable ones: cardholder name and expiry
 * for a card, the email for PayPal, the account-holder and bank name for a bank account.
 * Card and bank numbers are not editable — only their ciphertext and last 4 digits are
 * stored, so replacing a number is an add followed by a delete.
 */
export const updatePaymentMethod = (id, data) =>
  api.post('/account/payment-methods', form({ action: 'update', id, ...data }), F);

export const deletePaymentMethod = (id) =>
  api.post('/account/payment-methods', form({ action: 'delete', id }), F);
export const setDefaultPaymentMethod = (id) =>
  api.post('/account/payment-methods', form({ action: 'default', id }), F);
