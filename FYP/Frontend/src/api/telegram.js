import api from './config';

export const getTelegramStatus = (config) => api.get('/telegram/status', config);

/** Mints a fresh deep-link token and 6-digit code; both expire together. */
export const startTelegramLink = () => api.post('/telegram/link/start');

export const unlinkTelegram = () => api.post('/telegram/unlink');
