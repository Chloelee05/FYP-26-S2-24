/**
 * Member to admin support threads. Used by SupportChatWidget, the /support page and the
 * admin side of the same conversation. Order chat with the other party to a sale is a
 * different thing and lives in src/api/messages.js.
 *
 * Every call needs a session. Unlike the other API modules this one builds its own
 * headers through authConfig() and repeats the token in an X-Auth-Token header as well as
 * the usual Authorization one, so the token still arrives if a proxy strips Authorization.
 * The reads take an axios config because SupportChatWidget polls them.
 */
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

/** GET /api/support/threads. The caller's own threads, each with subject, status and unread flag. */
export const getSupportThreads = (config) => api.get('/support/threads', { ...authConfig(), ...config });
// POST /api/support/upload. The File goes as the raw body with its own MIME type, and the
// helper unwraps the response to the URL string that is then attached to a message.
export const uploadSupportImage = (file) =>
  api.post('/support/upload', file, authConfig({ 'Content-Type': file.type }))
    .then(r => r.data.imageUrl);

/** POST /api/support/threads. Opens a new thread and returns { threadId }. */
export const createSupportThread = (subject, body, attachmentUrl) =>
  api.post('/support/threads', form({ subject, body, attachmentUrl }).toString(), formHeaders());
/** GET /api/support/threads/{id}/messages. Full transcript for one thread. */
export const getSupportMessages = (threadId, config) =>
  api.get(`/support/threads/${threadId}/messages`, { ...authConfig(), ...config });
/** POST /api/support/threads/{id}/messages. attachmentUrl comes from uploadSupportImage. */
export const sendSupportMessage = (threadId, body, attachmentUrl) =>
  api.post(`/support/threads/${threadId}/messages`, form({ body, attachmentUrl }).toString(), formHeaders());
/** POST /api/support/threads/{id}/close. A closed thread is read-only for both sides. */
export const closeSupportThread = (threadId) =>
  api.post(`/support/threads/${threadId}/close`, form({}).toString(), formHeaders());
/** POST /api/support/threads/{id}/read. Clears the unread badge on the widget. */
export const markSupportThreadRead = (threadId) =>
  api.post(`/support/threads/${threadId}/read`, form({}).toString(), formHeaders());
