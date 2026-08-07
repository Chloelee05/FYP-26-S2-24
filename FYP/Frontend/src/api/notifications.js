/**
 * In-app notifications and the per-user delivery preferences behind them. NotificationBell
 * reads the list on a poll; the notifications tab of account settings writes the toggles.
 *
 * All of these are scoped to the signed-in account server side, so no user id is ever
 * sent. Reads and writes share the /api/notifications path and are told apart by the
 * `action` field.
 */
import api from './config';

const form = (obj) => {
  const p = new URLSearchParams();
  Object.entries(obj).forEach(([k, v]) => { if (v != null) p.append(k, v); });
  return p;
};
const F = { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } };

/** GET /api/notifications. Returns { notifications, unreadCount }. Takes a poll AbortSignal. */
export const getNotifications = (config) => api.get('/notifications', config);
/** POST /api/notifications with action=read. Clears the unread flag on one item. */
export const markNotificationRead = (id) => api.post('/notifications', form({ action: 'read', id }), F);
/** POST /api/notifications with action=readAll. Clears every unread item for this account. */
export const markAllNotificationsRead = () => api.post('/notifications', form({ action: 'readAll' }), F);

/** GET /api/notifications/preferences. Both the in-app and the Telegram switches. */
export const getNotificationPreferences = () => api.get('/notifications/preferences');
// POST /api/notifications/preferences. Only the three in-app switches: outbid, ending
// soon and won. The Telegram switches go through saveTelegramPreferences below.
export const saveNotificationPreferences = ({ outbid, endingSoon, wonAuction }) =>
  api.post('/notifications/preferences', form({ outbid, endingSoon, wonAuction }), F);

/**
 * Saves the Telegram delivery switches. Sent to the same endpoint as the in-app
 * preferences, but as a separate call so a Telegram toggle never rewrites the in-app ones.
 */
export const saveTelegramPreferences = (t) =>
  api.post('/notifications/preferences', form({
    telegramEnabled:      t.enabled,
    telegramOutbid:       t.outbid,
    telegramWon:          t.won,
    telegramLost:         t.lost,
    telegramSellerResult: t.sellerResult,
    telegramSellerPrice:  t.sellerPrice,
    telegramOrderUpdates: t.orderUpdates,
  }), F);
