import api from './config';

const F = { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } };

export const verifyLogin = (otpCode) =>
  api.post('/2fa/verify-login', new URLSearchParams({ otpCode }), F);

export const setup2FA = () =>
  api.post('/2fa/setup');

export const confirm2FA = (otpCode) =>
  api.post('/2fa/confirm', new URLSearchParams({ otpCode }), F);

export const disable2FA = (password) =>
  api.post('/2fa/disable', new URLSearchParams({ password }), F);
