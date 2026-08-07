/**
 * The signed-in member's own account: profile, transaction history, reviews received,
 * saved payment methods and account deletion. Used by the profile and settings pages and
 * by EnableSellingGate.
 *
 * Every call here needs a session and acts on that account, so no user id is ever sent.
 * The server reads the identity from the session, which means one member cannot read
 * another's details through these endpoints.
 */
import api from './config';

const form = (obj) => {
  const p = new URLSearchParams();
  Object.entries(obj).forEach(([k, v]) => { if (v != null) p.append(k, v); });
  return p;
};
const F = { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } };

/** GET /api/account. The caller's own profile fields. */
export const getProfile             = ()     => api.get('/account');
/** GET /api/account/transactions. `filter` narrows by period or by buying versus selling. */
export const getTransactionHistory  = (filter) => api.get('/account/transactions', { params: { filter } });
/** GET /api/account/reviews. Reviews other members have left about the caller. */
export const getMyReviews           = ()     => api.get('/account/reviews');

/** POST /api/account/update. Editable profile fields; only what is passed is changed. */
export const updateProfile = (data) =>
  api.post('/account/update', form(data), F);

/**
 * POST /api/account/enable-selling. Turns on the selling capability for the signed-in
 * buyer (one merged account). There is no separate seller signup, so this sets `canSell`
 * on the existing account and the session then satisfies ProtectedRoute's requireSeller.
 */
export const enableSelling = () =>
  api.post('/account/enable-selling', form({}), F);

// POST /api/account/upload-photo. The File is sent as the raw body with its own MIME type
// rather than as multipart form data, matching how listing images are uploaded.
export const uploadProfilePhoto = (file) => {
  return api.post('/account/upload-photo', file, {
    headers: { 'Content-Type': file.type },
  });
};

// POST /api/account/delete. The literal confirm=DELETE field is a deliberate second
// gate, so the request cannot be issued by accident.
export const deleteAccount = () =>
  api.post('/account/delete', form({ confirm: 'DELETE' }), F);

// Payment methods (PAN stored AES-GCM encrypted server-side). One endpoint for all four
// operations, told apart by the `action` field. Responses carry only the last 4 digits.
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
