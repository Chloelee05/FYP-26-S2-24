import api from './config';

const form = (obj) => {
  const p = new URLSearchParams();
  Object.entries(obj).forEach(([k, v]) => { if (v != null) p.append(k, v); });
  return p;
};
const F = { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } };

// Direct buyer <-> seller conversations, keyed by order.
export const getConversations = () => api.get('/order-messages');
export const getOrderMessages = (orderId) => api.get(`/order-messages/${orderId}`);
export const sendOrderMessage = (orderId, body) =>
  api.post(`/order-messages/${orderId}`, form({ body }).toString(), F);
