/**
 * The order lifecycle after an auction closes: declare a winner, pay, ship, confirm
 * receipt and handle refunds. Used by My purchases (buyer side) and My sales (seller
 * side), which both read the same payload and split it into tabs via src/utils/orders.js.
 *
 * Every call needs a session. The server works out from the order whether the caller is
 * the buyer or the seller and refuses an action that belongs to the other party, so a
 * buyer cannot advance shipping and a seller cannot pay.
 */
import api from './config';

const form = (obj) => {
  const p = new URLSearchParams();
  Object.entries(obj).forEach(([k, v]) => { if (v != null) p.append(k, v); });
  return p;
};
const F = { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } };

/** GET /api/orders. Every order the caller is a party to, on either side of the deal. */
export const getOrders = () => api.get('/orders');
/**
 * POST /api/orders/declare. Turns a closed auction into an order for the winning bidder.
 * `early` lets the seller close an ascending listing before its end date and settle at
 * the current top bid.
 */
export const declareWinner = (auctionId, early = false) => {
  const p = new URLSearchParams();
  p.append('auctionId', String(auctionId));
  if (early) p.append('early', 'true');
  // Query string fallback so early close works even if the body is not parsed
  const url = early ? '/orders/declare?early=true' : '/orders/declare';
  return api.post(url, p.toString(), F);
};
/** POST /api/orders/pay. Buyer settles with a saved payment method; billing is simulated. */
export const payOrder = (orderId, paymentMethodId) => api.post('/orders/pay', form({ orderId, paymentMethodId }), F);
/** POST /api/orders/complete. Buyer confirms receipt, which is what releases the sale. */
export const completeOrder = (orderId) => api.post('/orders/complete', form({ orderId }), F);
// POST /api/orders/shipping. Seller pushes the order one step along
// PREPARING, SHIPPED, IN_TRANSIT, DELIVERED. The server picks the next step itself,
// so there is no status to pass and no way to skip one.
export const advanceOrderShipping = (orderId) => api.post('/orders/shipping', form({ orderId }), F);
/** POST /api/orders/refund. Buyer opens a refund request; the seller decides on it first. */
export const requestOrderRefund = (orderId, reason) =>
  api.post('/orders/refund', form({ orderId, reason }), F);
// POST /api/orders/refund-resolve. The seller's decision. A buyer who is turned down can
// escalate to an admin, who overrides it through admin.adminResolveRefund.
export const resolveOrderRefund = (orderId, approve) =>
  api.post('/orders/refund-resolve', form({ orderId, action: approve ? 'approve' : 'reject' }), F);
