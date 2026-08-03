/**
 * Vocabulary shared by the two order views — My purchases (buyer side) and
 * My sales (seller side). Both read the same /api/orders payload, so the
 * bucketing and labelling live here rather than being duplicated per page.
 */

const SHIPPING_LABEL = {
  PREPARING:  'Seller preparing',
  SHIPPED:    'Shipped',
  IN_TRANSIT: 'Out for delivery',
  DELIVERED:  'Delivered',
};

export function shippingLabel(order) {
  const step = (order.shippingStatus || '').toUpperCase();
  return SHIPPING_LABEL[step] ?? null;
}

/** The next shipping step a seller can push an order to, or null when delivered. */
export function nextShippingAction(order) {
  const step = (order.shippingStatus || 'PREPARING').toUpperCase();
  if (step === 'PREPARING')  return 'Mark shipped';
  if (step === 'SHIPPED')    return 'Mark in transit';
  if (step === 'IN_TRANSIT') return 'Mark delivered';
  return null;
}

/**
 * Every order lands in exactly one bucket so it appears under a single tab.
 * A live refund outranks the payment state: that is what the parties are
 * waiting on. A declined refund falls through to the normal flow, because the
 * order carries on afterwards.
 *
 * A paid order splits on whether the seller has handed it over yet, which is
 * what separates "waiting on the seller" from "on its way to me".
 */
export function orderBucket(order) {
  const refund = (order.refundStatus || '').toUpperCase();
  if (refund === 'REQUESTED' || refund === 'APPROVED') return 'returns';
  if (order.status === 'CANCELLED')       return 'cancelled';
  if (order.status === 'COMPLETED')       return 'completed';
  if (order.status === 'PENDING_PAYMENT') return 'unpaid';
  const step = (order.shippingStatus || 'PREPARING').toUpperCase();
  return step === 'PREPARING' ? 'toShip' : 'toReceive';
}

/**
 * Buyers track the handover in two steps, so they can tell "the seller still
 * has it" from "it is on the way".
 *
 * There is no separate Cancelled tab: the only thing that cancels a buyer's
 * order is an approved refund, which already belongs under Returns. Returns
 * claims the cancelled bucket too so such an order can never go missing.
 */
export const PURCHASE_TABS = [
  { key: 'unpaid',    label: 'To Pay',     buckets: ['unpaid'] },
  { key: 'toShip',    label: 'To ship',    buckets: ['toShip'] },
  { key: 'toReceive', label: 'To receive', buckets: ['toReceive'] },
  { key: 'completed', label: 'Completed',  buckets: ['completed'] },
  { key: 'returns',   label: 'Returns',    buckets: ['returns', 'cancelled'] },
];

/**
 * Sellers drive both halves of the handover themselves, so those stay one
 * "In progress" queue named from the seller's point of view. Returns absorbs
 * the cancelled bucket here for the same reason it does on the buyer side.
 */
export const SALE_TABS = [
  { key: 'unpaid',    label: 'To start',    buckets: ['unpaid'] },
  { key: 'progress',  label: 'In progress', buckets: ['toShip', 'toReceive'] },
  { key: 'completed', label: 'Completed',   buckets: ['completed'] },
  { key: 'returns',   label: 'Returns',     buckets: ['returns', 'cancelled'] },
];

/** Whether an order belongs under the given tab definition. */
export const inTab = (order, tab) => tab.buckets.includes(orderBucket(order));

/** Stable, readable reference for an order (the DB id is a bare number). */
export const orderRef = (id) => `O${String(id ?? '').padStart(6, '0')}`;

/** One line describing where the order stands, written for the given role. */
export function orderHeadline(order, role) {
  const refund = (order.refundStatus || '').toUpperCase();
  if (refund === 'REQUESTED') {
    return role === 'seller' ? 'Refund requested by buyer' : 'Refund requested';
  }
  if (refund === 'APPROVED') return 'Refund approved';
  if (order.status === 'CANCELLED' && order.cancelReason === 'PAYMENT_TIMEOUT') {
    return role === 'seller' ? 'Cancelled — buyer missed payment deadline' : 'Cancelled — payment deadline missed';
  }
  if (order.status === 'CANCELLED')       return 'Order cancelled';
  if (order.status === 'COMPLETED')       return 'Order completed';
  if (order.status === 'PENDING_PAYMENT') {
    return role === 'seller' ? 'Waiting for payment' : 'Awaiting your payment';
  }
  return shippingLabel(order) ?? 'Order in progress';
}

/** Badge style matching the wording the backend uses. */
export function orderBadgeClass(status) {
  if (status === 'COMPLETED') return 'badge-success';
  if (status === 'PAID')      return 'badge-info';
  if (status === 'CANCELLED') return 'badge-danger';
  return 'badge-warning';
}

const DATE_RANGES = {
  all: null,
  '7':  7,
  '30': 30,
  '90': 90,
};

export const DATE_FILTERS = [
  { key: 'all', label: 'Any date' },
  { key: '7',   label: 'Last 7 days' },
  { key: '30',  label: 'Last 30 days' },
  { key: '90',  label: 'Last 90 days' },
];

export function withinDateFilter(order, key) {
  const days = DATE_RANGES[key];
  if (!days || !order.createdAt) return true;
  return Date.now() - new Date(order.createdAt).getTime() <= days * 86_400_000;
}
