import api from './config';

const form = (obj) => {
  const p = new URLSearchParams();
  Object.entries(obj).forEach(([k, v]) => { if (v != null) p.append(k, v); });
  return p;
};
const F = { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } };

export const getOrders = () => api.get('/orders');
export const declareWinner = (auctionId, early = false) => {
  const p = new URLSearchParams();
  p.append('auctionId', String(auctionId));
  if (early) p.append('early', 'true');
  // Query string fallback so early close works even if the body is not parsed
  const url = early ? '/orders/declare?early=true' : '/orders/declare';
  return api.post(url, p.toString(), F);
};
export const payOrder = (orderId, paymentMethodId) => api.post('/orders/pay', form({ orderId, paymentMethodId }), F);
export const completeOrder = (orderId) => api.post('/orders/complete', form({ orderId }), F);
export const advanceOrderShipping = (orderId) => api.post('/orders/shipping', form({ orderId }), F);
export const requestOrderRefund = (orderId, reason) =>
  api.post('/orders/refund', form({ orderId, reason }), F);
export const resolveOrderRefund = (orderId, approve) =>
  api.post('/orders/refund-resolve', form({ orderId, action: approve ? 'approve' : 'reject' }), F);
