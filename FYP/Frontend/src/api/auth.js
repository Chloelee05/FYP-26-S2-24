import api from './config';

const form = (obj) => {
  const p = new URLSearchParams();
  Object.entries(obj).forEach(([k, v]) => { if (v != null) p.append(k, v); });
  return p;
};
const F = { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } };

export const login = (email, password) =>
  api.post('/auth/login', form({ email, password }), F);

export const logout = () => api.post('/auth/logout', null, F);

export const register = (data) => api.post('/auth/register', form(data), F);

export const forgotPassword = (email) =>
  api.post('/auth/forgot-password', form({ identifier: email }), F);

export const resetPassword = (data) =>
  api.post('/auth/reset-password', form(data), F);

export const changePassword = (data) =>
  api.post('/auth/change-password', form(data), F);

export const getSession = () => api.get('/session');

// Third-party (Google) sign-in — SCRUM-17
export const getOAuthConfig = () => api.get('/oauth/config');
export const getLinkedAccounts = () => api.get('/oauth/linked');
export const linkOAuthAccount = (provider, credential) =>
  api.post('/oauth/link', form({ provider, credential }), F);
export const unlinkOAuthAccount = (provider) =>
  api.post('/oauth/unlink', form({ provider }), F);
export const oauthLogin = (provider, credential) =>
  api.post('/oauth/login', form({ provider, credential }), F);
