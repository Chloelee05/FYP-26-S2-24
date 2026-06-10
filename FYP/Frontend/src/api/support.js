import api from './config';

const form = (obj) => {
  const p = new URLSearchParams();
  Object.entries(obj).forEach(([k, v]) => { if (v != null) p.append(k, v); });
  return p;
};

/** Ensure bearer token is sent (global interceptor + explicit header for form POSTs). */
function authConfig(extraHeaders = {}) {
  const token = sessionStorage.getItem('authToken');
  const headers = { ...extraHeaders };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
    headers['X-Auth-Token'] = token;
  }
  return { headers };
}

const formHeaders = () => authConfig({ 'Content-Type': 'application/x-www-form-urlencoded' });

export const getSupportThreads = () => api.get('/support/threads', authConfig());
export const uploadSupportImage = (file) =>
  api.post('/support/upload', file, authConfig({ 'Content-Type': file.type }))
    .then(r => r.data.imageUrl);

export const createSupportThread = (subject, body, attachmentUrl) =>
  api.post('/support/threads', form({ subject, body, attachmentUrl }).toString(), formHeaders());
export const getSupportMessages = (threadId) => api.get(`/support/threads/${threadId}/messages`, authConfig());
export const sendSupportMessage = (threadId, body, attachmentUrl) =>
  api.post(`/support/threads/${threadId}/messages`, form({ body, attachmentUrl }).toString(), formHeaders());
export const closeSupportThread = (threadId) =>
  api.post(`/support/threads/${threadId}/close`, form({}).toString(), formHeaders());
