/**
 * Time-based one-time-password two-factor authentication. Used by the 2FA challenge on
 * the login flow and by the security tab of account settings.
 *
 * verifyLogin runs against the half-authenticated session that auth.login leaves behind
 * when the account has 2FA on, so it is the one call here that happens before the user is
 * properly signed in. The other three need a full session. The codes come from an
 * authenticator app and are checked server side against the stored secret.
 */
import api from './config';

const F = { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } };

/** POST /api/2fa/verify-login. Completes a login that stopped at the 2FA challenge. */
export const verifyLogin = (otpCode) =>
  api.post('/2fa/verify-login', new URLSearchParams({ otpCode }), F);

/** POST /api/2fa/setup. Generates a secret and returns the QR payload to scan. */
export const setup2FA = () =>
  api.post('/2fa/setup');

/** POST /api/2fa/confirm. Proves the app was set up correctly, which is what switches 2FA on. */
export const confirm2FA = (otpCode) =>
  api.post('/2fa/confirm', new URLSearchParams({ otpCode }), F);

/** POST /api/2fa/disable. Password rather than a code, so a lost authenticator is recoverable. */
export const disable2FA = (password) =>
  api.post('/2fa/disable', new URLSearchParams({ password }), F);
