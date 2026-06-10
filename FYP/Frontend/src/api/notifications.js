import api from './config';

const form = (obj) => {
  const p = new URLSearchParams();
  Object.entries(obj).forEach(([k, v]) => { if (v != null) p.append(k, v); });
  return p;
};
const F = { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } };

export const getNotifications = () => api.get('/notifications');
export const markNotificationRead = (id) => api.post('/notifications', form({ action: 'read', id }), F);
export const markAllNotificationsRead = () => api.post('/notifications', form({ action: 'readAll' }), F);
