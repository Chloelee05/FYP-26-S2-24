import api from './config';

const form = (obj) => {
  const p = new URLSearchParams();
  Object.entries(obj).forEach(([k, v]) => { if (v != null) p.append(k, v); });
  return p;
};
const F = { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } };

export const getOrders = () => api.get('/orders');
export const declareWinner = (auctionId) => api.post('/orders/declare', form({ auctionId }), F);
export const payOrder = (orderId, paymentMethodId) => api.post('/orders/pay', form({ orderId, paymentMethodId }), F);
export const completeOrder = (orderId) => api.post('/orders/complete', form({ orderId }), F);
