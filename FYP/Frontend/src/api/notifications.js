import api from './config';

const form = (obj) => {
  const p = new URLSearchParams();
  Object.entries(obj).forEach(([k, v]) => { if (v != null) p.append(k, v); });
  return p;
};
const F = { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } };

export const getNotifications = (config) => api.get('/notifications', config);
export const markNotificationRead = (id) => api.post('/notifications', form({ action: 'read', id }), F);
export const markAllNotificationsRead = () => api.post('/notifications', form({ action: 'readAll' }), F);

export const getNotificationPreferences = () => api.get('/notifications/preferences');
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
