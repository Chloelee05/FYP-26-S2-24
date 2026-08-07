/**
 * Linking a member's Telegram account so the bot can send auction alerts. Used by
 * TelegramConnectModal, the Navbar account menu and the notifications tab of settings.
 *
 * Linking is opt in. Nothing is stored until the member goes through the dialog and
 * agrees, which is the PDPA position the project took: the chat ID is personal data, so
 * it is collected on consent and can be withdrawn with unlinkTelegram.
 *
 * All three need a session and act on the signed-in account only.
 */
import api from './config';

/**
 * GET /api/telegram/status. Returns { available, linked, telegramUsername, copy }.
 * `available` is false when the server has no bot token configured, `copy` is the
 * admin-editable consent text. TelegramConnectModal polls this while it waits, because
 * the bot reports the link to the server and not to the browser tab.
 */
export const getTelegramStatus = (config) => api.get('/telegram/status', config);

/**
 * POST /api/telegram/link/start. Mints a fresh deep-link token and 6-digit code; both
 * expire together. Returns { deepLink, code, botUsername, expiresInSeconds }, and the
 * code is single use.
 */
export const startTelegramLink = () => api.post('/telegram/link/start');

/** POST /api/telegram/unlink. Drops the stored chat ID and stops the alerts. */
export const unlinkTelegram = () => api.post('/telegram/unlink');
