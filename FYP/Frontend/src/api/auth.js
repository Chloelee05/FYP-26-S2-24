/**
 * Sign in, sign out, registration and password recovery.
 * AuthContext calls these; pages should go through useAuth() rather than importing
 * login/logout directly, so the session state stays in one place.
 *
 * Everything except getSession is a POST of url-encoded fields, because the matching
 * servlets read them with request.getParameter(). Only changePassword and getSession
 * need an existing session; the rest are reachable while signed out.
 */
import api from './config';

// Servlets read url-encoded fields, so object payloads are flattened into URLSearchParams.
// Null and undefined entries are dropped so an optional field is absent rather than the
// literal string "null".
const form = (obj) => {
  const p = new URLSearchParams();
  Object.entries(obj).forEach(([k, v]) => { if (v != null) p.append(k, v); });
  return p;
};
const F = { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } };

/**
 * POST /api/auth/login. On success the servlet opens a session and returns the user;
 * if the account has 2FA on it returns a pending state instead, and the caller then
 * goes through twoFactor.verifyLogin.
 */
export const login = (email, password) =>
  api.post('/auth/login', form({ email, password }), F);

/** POST /api/auth/logout. Invalidates the server session for this browser. */
export const logout = () => api.post('/auth/logout', null, F);

/** POST /api/auth/register. `data` carries username, email, password and profile fields. */
export const register = (data) => api.post('/auth/register', form(data), F);

/** POST /api/auth/forgot-password. Sent as `identifier`, which accepts an email or username. */
export const forgotPassword = (email) =>
  api.post('/auth/forgot-password', form({ identifier: email }), F);

/** POST /api/auth/reset-password with the emailed token plus the new password. */
export const resetPassword = (data) =>
  api.post('/auth/reset-password', form(data), F);

/** POST /api/auth/change-password for a signed-in user: current password plus new one. */
export const changePassword = (data) =>
  api.post('/auth/change-password', form(data), F);

/**
 * GET /api/session. Returns the signed-in user, including the `canSell` capability and
 * role that ProtectedRoute checks, or an unauthenticated response for a guest.
 */
export const getSession = () => api.get('/session');
