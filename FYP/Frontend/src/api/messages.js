import api from './config';

const form = (obj) => {
  const p = new URLSearchParams();
  Object.entries(obj).forEach(([k, v]) => { if (v != null) p.append(k, v); });
  return p;
};
const F = { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } };

// Direct buyer <-> seller conversations, keyed by order.
export const getConversations = (config) => api.get('/order-messages', config);
export const getOrderMessages = (orderId, config) => api.get(`/order-messages/${orderId}`, config);
export const sendOrderMessage = (orderId, body) =>
  api.post(`/order-messages/${orderId}`, form({ body }).toString(), F);
