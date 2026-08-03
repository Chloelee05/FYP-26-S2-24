import { describe, it, expect } from 'vitest';
import {
  orderBucket, inTab, orderRef, orderHeadline, orderBadgeClass,
  shippingLabel, nextShippingAction, withinDateFilter,
  PURCHASE_TABS, SALE_TABS,
} from './orders';

const order = (over = {}) => ({ id: 1, status: 'PAID', shippingStatus: 'PREPARING', ...over });

describe('orderBucket', () => {
  it('sends an unpaid order to the unpaid bucket', () => {
    expect(orderBucket(order({ status: 'PENDING_PAYMENT' }))).toBe('unpaid');
  });

  it('splits a paid order on whether the seller has handed it over', () => {
    expect(orderBucket(order({ shippingStatus: 'PREPARING' }))).toBe('toShip');
    expect(orderBucket(order({ shippingStatus: 'SHIPPED' }))).toBe('toReceive');
    expect(orderBucket(order({ shippingStatus: 'IN_TRANSIT' }))).toBe('toReceive');
  });

  it('defaults a paid order with no shipping status to toShip', () => {
    expect(orderBucket({ id: 1, status: 'PAID' })).toBe('toShip');
  });

  it('reads shipping status case-insensitively', () => {
    expect(orderBucket(order({ shippingStatus: 'shipped' }))).toBe('toReceive');
  });

  it('lets a live refund outrank the payment state', () => {
    expect(orderBucket(order({ status: 'PAID', refundStatus: 'REQUESTED' }))).toBe('returns');
    expect(orderBucket(order({ status: 'COMPLETED', refundStatus: 'APPROVED' }))).toBe('returns');
  });

  // The regression that matters: a declined refund must not strand the order in Returns.
  it('falls through to the normal flow once a refund is declined', () => {
    expect(orderBucket(order({ refundStatus: 'DECLINED', shippingStatus: 'SHIPPED' }))).toBe('toReceive');
    expect(orderBucket(order({ refundStatus: 'DECLINED', status: 'COMPLETED' }))).toBe('completed');
  });

  it('buckets terminal states', () => {
    expect(orderBucket(order({ status: 'CANCELLED' }))).toBe('cancelled');
    expect(orderBucket(order({ status: 'COMPLETED' }))).toBe('completed');
  });
});

describe('tab definitions', () => {
  it('puts every order in exactly one purchase tab', () => {
    const samples = [
      order({ status: 'PENDING_PAYMENT' }),
      order({ shippingStatus: 'PREPARING' }),
      order({ shippingStatus: 'DELIVERED' }),
      order({ status: 'COMPLETED' }),
      order({ status: 'CANCELLED' }),
      order({ refundStatus: 'REQUESTED' }),
    ];
    for (const o of samples) {
      expect(PURCHASE_TABS.filter(t => inTab(o, t))).toHaveLength(1);
    }
  });

  it('puts every order in exactly one sale tab', () => {
    const samples = [
      order({ status: 'PENDING_PAYMENT' }),
      order({ shippingStatus: 'PREPARING' }),
      order({ shippingStatus: 'IN_TRANSIT' }),
      order({ status: 'COMPLETED' }),
      order({ status: 'CANCELLED' }),
      order({ refundStatus: 'APPROVED' }),
    ];
    for (const o of samples) {
      expect(SALE_TABS.filter(t => inTab(o, t))).toHaveLength(1);
    }
  });

  it('merges both handover steps into the seller "In progress" queue', () => {
    const progress = SALE_TABS.find(t => t.key === 'progress');
    expect(inTab(order({ shippingStatus: 'PREPARING' }), progress)).toBe(true);
    expect(inTab(order({ shippingStatus: 'SHIPPED' }), progress)).toBe(true);
  });
});

describe('orderRef', () => {
  it('zero-pads to six digits', () => {
    expect(orderRef(42)).toBe('O000042');
    expect(orderRef(123456)).toBe('O123456');
  });

  it('does not truncate an id longer than the pad width', () => {
    expect(orderRef(12345678)).toBe('O12345678');
  });

  it('survives a missing id', () => {
    expect(orderRef(null)).toBe('O000000');
    expect(orderRef(undefined)).toBe('O000000');
  });
});

describe('orderHeadline', () => {
  it('names the party who asked for the refund', () => {
    const o = order({ refundStatus: 'REQUESTED' });
    expect(orderHeadline(o, 'seller')).toBe('Refund requested by buyer');
    expect(orderHeadline(o, 'buyer')).toBe('Refund requested');
  });

  it('writes the payment wait from each side', () => {
    const o = order({ status: 'PENDING_PAYMENT' });
    expect(orderHeadline(o, 'seller')).toBe('Waiting for payment');
    expect(orderHeadline(o, 'buyer')).toBe('Awaiting your payment');
  });

  it('falls back to the shipping label mid-flow', () => {
    expect(orderHeadline(order({ shippingStatus: 'IN_TRANSIT' }), 'buyer')).toBe('Out for delivery');
  });

  it('falls back to a generic line when shipping status is unrecognised', () => {
    expect(orderHeadline(order({ shippingStatus: 'WAT' }), 'buyer')).toBe('Order in progress');
  });

  it('distinguishes an auto-cancelled unpaid order from any other cancellation', () => {
    const o = order({ status: 'CANCELLED', cancelReason: 'PAYMENT_TIMEOUT' });
    expect(orderHeadline(o, 'buyer')).toBe('Cancelled — payment deadline missed');
    expect(orderHeadline(o, 'seller')).toBe('Cancelled — buyer missed payment deadline');
    // A refund-approved cancellation (no cancelReason) still reads as a plain cancellation.
    expect(orderHeadline(order({ status: 'CANCELLED' }), 'buyer')).toBe('Order cancelled');
  });
});

describe('shipping helpers', () => {
  it('labels known steps and returns null for the rest', () => {
    expect(shippingLabel(order({ shippingStatus: 'DELIVERED' }))).toBe('Delivered');
    expect(shippingLabel(order({ shippingStatus: '' }))).toBeNull();
  });

  it('walks the seller through each handover step, then stops', () => {
    expect(nextShippingAction(order({ shippingStatus: 'PREPARING' }))).toBe('Mark shipped');
    expect(nextShippingAction(order({ shippingStatus: 'SHIPPED' }))).toBe('Mark in transit');
    expect(nextShippingAction(order({ shippingStatus: 'IN_TRANSIT' }))).toBe('Mark delivered');
    expect(nextShippingAction(order({ shippingStatus: 'DELIVERED' }))).toBeNull();
  });
});

describe('orderBadgeClass', () => {
  it('maps the backend wording to a badge style', () => {
    expect(orderBadgeClass('COMPLETED')).toBe('badge-success');
    expect(orderBadgeClass('PAID')).toBe('badge-info');
    expect(orderBadgeClass('CANCELLED')).toBe('badge-danger');
    expect(orderBadgeClass('PENDING_PAYMENT')).toBe('badge-warning');
  });
});

describe('withinDateFilter', () => {
  const daysAgo = (n) => new Date(Date.now() - n * 86_400_000).toISOString();

  it('keeps everything under "Any date"', () => {
    expect(withinDateFilter({ createdAt: daysAgo(500) }, 'all')).toBe(true);
  });

  it('includes orders inside the window and excludes older ones', () => {
    expect(withinDateFilter({ createdAt: daysAgo(3) }, '7')).toBe(true);
    expect(withinDateFilter({ createdAt: daysAgo(20) }, '7')).toBe(false);
    expect(withinDateFilter({ createdAt: daysAgo(20) }, '30')).toBe(true);
  });

  // Hiding an order because the backend omitted a timestamp would be the wrong default.
  it('keeps an order with no createdAt rather than hiding it', () => {
    expect(withinDateFilter({}, '7')).toBe(true);
  });
});
