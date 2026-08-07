/**
 * Order chat between the two parties to a sale. Used by the Messages page and by
 * OrderMessageModal. This is separate from src/api/support.js, which talks to the admin.
 *
 * Every call needs a session, and the server only lets the buyer or the seller named on
 * that order read or post to its thread. Both reads accept an axios config, because the
 * callers poll through usePolling and pass its AbortSignal so a request in flight is
 * dropped when the panel closes.
 */
import api from './config';

const form = (obj) => {
  const p = new URLSearchParams();
  Object.entries(obj).forEach(([k, v]) => { if (v != null) p.append(k, v); });
  return p;
};
const F = { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } };

// Direct buyer <-> seller conversations, keyed by order.
/** GET /api/order-messages. One entry per order the caller is a party to, newest first. */
export const getConversations = (config) => api.get('/order-messages', config);
/** GET /api/order-messages/{orderId}. Full message list for one order. */
export const getOrderMessages = (orderId, config) => api.get(`/order-messages/${orderId}`, config);
/** POST /api/order-messages/{orderId}. Sends one message; the server stamps the sender. */
export const sendOrderMessage = (orderId, body) =>
  api.post(`/order-messages/${orderId}`, form({ body }).toString(), F);
