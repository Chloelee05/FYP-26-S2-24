import { useState, useEffect, useMemo } from 'react';
import { Link } from 'react-router-dom';
import {
  ShoppingBag, Package, Truck, CreditCard, MessageCircle, RotateCcw, Star,
  Check, Search, ChevronRight, ImageIcon,
} from 'lucide-react';
import { getOrders, payOrder, completeOrder } from '../api/orders';
import { getPaymentMethods } from '../api/user';
import { formatCurrency } from '../utils/helpers';
import { publicPath } from '../utils/appBase';
import {
  PURCHASE_TABS, inTab, orderBucket, orderRef, orderHeadline, shippingLabel,
  DATE_FILTERS, withinDateFilter,
} from '../utils/orders';
import Modal from '../components/Modal';
import OrderTrackingModal from '../components/OrderTrackingModal';
import OrderMessageModal from '../components/OrderMessageModal';
import OrderRefundModal from '../components/OrderRefundModal';
import { apiErrorMessage } from '../utils/apiError';

/** True when the order is waiting on something the buyer has to do. */
function needsMyAction(o) {
  const shipping = (o.shippingStatus || '').toUpperCase();
  const refund = (o.refundStatus || '').toUpperCase();
  if (o.status === 'PENDING_PAYMENT') return true;
  if (o.status === 'PAID' && shipping === 'DELIVERED' && refund !== 'REQUESTED' && refund !== 'APPROVED') return true;
  if (o.status === 'COMPLETED' && !o.hasRated) return true;
  return false;
}

export default function MyPurchases() {
  const [orders, setOrders] = useState([]);
  const [cards, setCards] = useState([]);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState('unpaid');
  const [query, setQuery] = useState('');
  const [dateFilter, setDateFilter] = useState('all');
  const [message, setMessage] = useState('');

  const [payingOrder, setPayingOrder] = useState(null);
  const [payMethodId, setPayMethodId] = useState(null);
  const [trackOrder, setTrackOrder] = useState(null);
  const [contactOrder, setContactOrder] = useState(null);
  const [refundOrder, setRefundOrder] = useState(null);

  /**
   * @param selectTab on the first load, open the earliest tab that actually has
   *                  orders — landing on an empty "To Pay" reads as "no orders".
   */
  const loadOrders = (selectTab = false) =>
    getOrders()
      .then(r => {
        const mine = (r.data ?? []).filter(o => o.role === 'buyer');
        setOrders(mine);
        if (selectTab) {
          const first = PURCHASE_TABS.find(t => mine.some(o => inTab(o, t)));
          if (first) setTab(first.key);
        }
      })
      .catch(() => {})
      .finally(() => setLoading(false));

  useEffect(() => {
    loadOrders(true);
    getPaymentMethods().then(r => setCards(r.data ?? [])).catch(() => {});
  }, []);

  const counts = useMemo(() => {
    const out = {};
    PURCHASE_TABS.forEach(t => { out[t.key] = orders.filter(o => inTab(o, t)).length; });
    return out;
  }, [orders]);

  const visible = useMemo(() => {
    const active = PURCHASE_TABS.find(t => t.key === tab);
    const q = query.trim().toLowerCase();
    return orders.filter(o => {
      if (!active || !inTab(o, active)) return false;
      if (!withinDateFilter(o, dateFilter)) return false;
      if (!q) return true;
      return (
        (o.auctionTitle ?? '').toLowerCase().includes(q)
        || (o.counterparty ?? '').toLowerCase().includes(q)
        || orderRef(o.id).toLowerCase().includes(q)
      );
    });
  }, [orders, tab, query, dateFilter]);

  const openPayChooser = (order) => {
    setMessage('');
    if (cards.length === 0) {
      setMessage('Add a payment method in Account settings before paying for an order.');
      return;
    }
    setPayMethodId((cards.find(c => c.default) ?? cards[0])?.id ?? null);
    setPayingOrder(order);
  };

  const handlePayOrder = async () => {
    if (!payingOrder) return;
    setMessage('');
    try {
      await payOrder(payingOrder.id, payMethodId);
      setMessage('Payment successful.');
      loadOrders();
    } catch (err) {
      setMessage(apiErrorMessage(err, 'Payment failed. Add a payment method first.'));
    } finally {
      setPayingOrder(null);
    }
  };

  const handleConfirmReceipt = async (orderId) => {
    if (!window.confirm('Confirm you received the item in good condition? This cannot be undone.')) return;
    setMessage('');
    try {
      await completeOrder(orderId);
      setMessage('Receipt confirmed. You can now rate the seller.');
      loadOrders();
    } catch (err) {
      setMessage(apiErrorMessage(err, 'Could not confirm receipt.'));
    }
  };

  return (
    <div className="max-w-5xl mx-auto px-4 py-8">
      <div className="flex flex-wrap items-center justify-between gap-3 mb-6">
        <div>
          <h1 className="page-title">My purchases</h1>
          <p className="page-subtitle">Everything you have won, from payment through to delivery.</p>
        </div>
        <Link to="/bidding-history" className="btn-secondary">Bidding history</Link>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 border-b border-ink-200 overflow-x-auto mb-5">
        {PURCHASE_TABS.map(({ key, label }) => (
          <button
            key={key}
            onClick={() => setTab(key)}
            aria-current={tab === key ? 'page' : undefined}
            className={`whitespace-nowrap px-4 py-2.5 text-sm font-semibold border-b-2 -mb-px transition-colors ${
              tab === key
                ? 'text-primary-600 border-primary-600'
                : 'text-ink-500 border-transparent hover:text-ink-800'
            }`}
          >
            {label}
            {counts[key] > 0 && (
              <span className={`ml-1.5 text-xs tabular-nums ${tab === key ? 'text-primary-500' : 'text-ink-400'}`}>
                {counts[key]}
              </span>
            )}
          </button>
        ))}
      </div>

      <div className="flex flex-wrap items-center gap-3 mb-5">
        <div className="relative flex-1 min-w-[14rem] max-w-sm">
          <Search size={15} className="absolute left-3.5 top-1/2 -translate-y-1/2 text-ink-400 pointer-events-none" />
          <input
            value={query}
            onChange={e => setQuery(e.target.value)}
            placeholder="Search order ID, seller or item"
            aria-label="Search purchases"
            className="input-field pl-10 rounded-full py-2 text-sm"
          />
        </div>
        <label className="text-sm">
          <span className="sr-only">Filter by date</span>
          <select
            value={dateFilter}
            onChange={e => setDateFilter(e.target.value)}
            className="select-field py-2 text-sm rounded-full"
          >
            {DATE_FILTERS.map(d => <option key={d.key} value={d.key}>{d.label}</option>)}
          </select>
        </label>
        <span className="text-sm text-ink-400 ml-auto tabular-nums">
          {visible.length} {visible.length === 1 ? 'order' : 'orders'}
        </span>
      </div>

      {message && <div className="alert-info mb-4"><span>{message}</span></div>}

      {payingOrder && (
        <Modal
          title="Choose payment method"
          subtitle={`Paying ${formatCurrency(payingOrder.amount)} for “${payingOrder.auctionTitle}”`}
          icon={CreditCard}
          size="md"
          dismissOnBackdrop={false}
          onClose={() => setPayingOrder(null)}
        >
          <div className="p-6">
            <div className="space-y-2 mb-5 max-h-64 overflow-y-auto">
              {cards.map(c => (
                <label
                  key={c.id}
                  className={`flex items-center gap-3 border rounded-xl px-4 py-3 cursor-pointer transition-colors ${
                    payMethodId === c.id ? 'border-primary-400 bg-primary-50' : 'border-ink-200 hover:bg-ink-50'
                  }`}
                >
                  <input
                    type="radio"
                    name="payMethod"
                    checked={payMethodId === c.id}
                    onChange={() => setPayMethodId(c.id)}
                  />
                  <CreditCard size={18} className="text-ink-400" />
                  <div className="flex-1">
                    <p className="text-sm font-medium text-ink-800">
                      {c.displayLabel}
                      {c.default && <span className="badge-info ml-2">Default</span>}
                    </p>
                    {c.methodType === 'CARD' && (
                      <p className="text-xs text-ink-400">
                        {c.cardHolder} · Exp {String(c.expMonth).padStart(2, '0')}/{c.expYear}
                      </p>
                    )}
                  </div>
                </label>
              ))}
            </div>
            <div className="flex gap-3">
              <button onClick={handlePayOrder} disabled={!payMethodId} className="btn-primary flex-1">
                Pay {formatCurrency(payingOrder.amount)}
              </button>
              <button onClick={() => setPayingOrder(null)} className="btn-secondary flex-1">Cancel</button>
            </div>
          </div>
        </Modal>
      )}
      {trackOrder && <OrderTrackingModal order={trackOrder} onClose={() => setTrackOrder(null)} />}
      {contactOrder && <OrderMessageModal order={contactOrder} onClose={() => setContactOrder(null)} />}
      {refundOrder && (
        <OrderRefundModal
          order={refundOrder}
          onClose={() => setRefundOrder(null)}
          onSubmitted={() => { setMessage('Refund request submitted.'); loadOrders(); }}
        />
      )}

      {loading ? (
        <div className="space-y-4">
          {Array.from({ length: 3 }, (_, i) => <div key={i} className="skeleton h-44 rounded-2xl" />)}
        </div>
      ) : visible.length === 0 ? (
        <div className="card p-16 text-center">
          <span className="grid place-items-center w-14 h-14 rounded-2xl bg-ink-100 text-ink-400 mx-auto mb-4">
            <ShoppingBag size={24} />
          </span>
          <p className="font-semibold text-ink-800">Nothing here yet</p>
          <p className="text-sm text-ink-500 mt-1">
            Orders land in this tab as soon as one of your winning bids reaches that stage.
          </p>
          <Link to="/search" className="btn-primary mt-6">Browse auctions</Link>
        </div>
      ) : (
        <div className="space-y-4">
          {visible.map(o => {
            const shipping = (o.shippingStatus || '').toUpperCase();
            const refund = (o.refundStatus || '').toUpperCase();
            // Once a return is under way the delivery timeline is no longer the
            // story, so tracking comes off both the header and the button row.
            const isReturn = orderBucket(o) === 'returns' || orderBucket(o) === 'cancelled';
            const canTrack = o.status !== 'PENDING_PAYMENT' && !isReturn;
            return (
              <div
                key={o.id}
                className={`card overflow-hidden ${needsMyAction(o) ? 'ring-1 ring-accent-200' : ''}`}
              >
                {/* Status header */}
                <button
                  type="button"
                  onClick={() => canTrack && setTrackOrder(o)}
                  disabled={!canTrack}
                  className="w-full flex items-center gap-2 px-5 py-3.5 border-b border-ink-100 text-left enabled:hover:bg-ink-50 transition-colors disabled:cursor-default"
                >
                  <span className={`grid place-items-center w-7 h-7 rounded-lg shrink-0 ${
                    o.status === 'COMPLETED' ? 'bg-emerald-50 text-emerald-600'
                      : o.status === 'CANCELLED' ? 'bg-red-50 text-red-500'
                      : refund === 'REQUESTED' ? 'bg-accent-50 text-accent-600'
                      : 'bg-primary-50 text-primary-600'
                  }`}>
                    {o.status === 'COMPLETED' ? <Check size={15} />
                      : refund === 'REQUESTED' || refund === 'APPROVED' ? <RotateCcw size={15} />
                      : o.status === 'PENDING_PAYMENT' ? <CreditCard size={15} />
                      : <Truck size={15} />}
                  </span>
                  <span className="font-semibold text-sm text-ink-900">{orderHeadline(o, 'buyer')}</span>
                  {canTrack && <ChevronRight size={15} className="text-ink-400" />}
                </button>

                <div className="px-5 py-4">
                  {/* The public profile route is keyed by seller id, not username. */}
                  <Link
                    to={`/seller/${o.sellerId}`}
                    className="inline-flex items-center gap-2 text-sm font-medium text-ink-700 hover:text-primary-600 transition-colors"
                  >
                    <span className="grid place-items-center w-5 h-5 rounded-full bg-ink-900 text-white text-[10px] font-bold">
                      {o.counterparty?.[0]?.toUpperCase() ?? 'S'}
                    </span>
                    {o.counterparty}
                  </Link>

                  <div className="flex items-center gap-4 mt-3">
                    <Link
                      to={`/auction/${o.auctionId}`}
                      className="w-20 h-20 rounded-xl bg-ink-50 grid place-items-center text-ink-300 shrink-0 overflow-hidden"
                    >
                      {o.thumbnailUrl ? (
                        <img src={publicPath(o.thumbnailUrl)} alt="" loading="lazy" className="w-full h-full object-contain p-1.5" />
                      ) : (
                        <ImageIcon size={22} />
                      )}
                    </Link>
                    <div className="min-w-0">
                      <Link
                        to={`/auction/${o.auctionId}`}
                        className="font-semibold text-sm text-ink-900 hover:text-primary-600 transition-colors line-clamp-2"
                      >
                        {o.auctionTitle}
                      </Link>
                      {refund === 'REQUESTED' && o.refundReason && (
                        <p className="text-xs text-ink-500 mt-1 italic line-clamp-2">“{o.refundReason}”</p>
                      )}
                      {refund === 'REJECTED' && (
                        <p className="text-xs text-ink-500 mt-1">Refund declined by the seller.</p>
                      )}
                    </div>
                  </div>
                </div>

                <div className="flex flex-wrap items-center justify-between gap-3 px-5 py-3.5 border-t border-ink-100 bg-ink-50/60">
                  <p className="text-xs text-ink-500 flex flex-wrap items-center gap-x-2 gap-y-1">
                    <span>1 item</span>
                    {shippingLabel(o) && <><span className="text-ink-300">|</span><span>{shippingLabel(o)}</span></>}
                    <span className="text-ink-300">|</span>
                    <span>Order ID {orderRef(o.id)}</span>
                  </p>
                  <p className="text-sm text-ink-500">
                    Total: <strong className="text-ink-900 tabular-nums">{formatCurrency(o.amount)}</strong>
                  </p>
                </div>

                <div className="flex flex-wrap gap-2 px-5 py-3.5 border-t border-ink-100">
                  {o.status === 'PENDING_PAYMENT' && (
                    <button onClick={() => openPayChooser(o)} className="btn-primary btn-sm">Pay now</button>
                  )}
                  {canTrack && (
                    <button onClick={() => setTrackOrder(o)} className="btn-secondary btn-sm">
                      <Truck size={12} /> Track order
                    </button>
                  )}
                  {o.status === 'PAID' && shipping === 'DELIVERED' && refund !== 'REQUESTED' && refund !== 'APPROVED' && (
                    <button onClick={() => handleConfirmReceipt(o.id)} className="btn-success btn-sm">
                      <Check size={12} /> Confirm receipt
                    </button>
                  )}
                  {o.status !== 'PENDING_PAYMENT' && o.status !== 'CANCELLED' && (
                    <button onClick={() => setContactOrder(o)} className="btn-secondary btn-sm">
                      <MessageCircle size={12} /> Contact seller
                    </button>
                  )}
                  {o.status === 'PAID' && !o.refundStatus && (
                    <button
                      onClick={() => setRefundOrder(o)}
                      className="btn-secondary btn-sm text-accent-700 border-accent-200 hover:bg-accent-50 hover:border-accent-300"
                    >
                      <RotateCcw size={12} /> Request refund
                    </button>
                  )}
                  {o.status === 'COMPLETED' && (o.hasRated ? (
                    <span className="inline-flex items-center gap-1 px-1 py-1.5 text-xs font-medium text-ink-400">
                      <Check size={13} /> Rated
                    </span>
                  ) : (
                    <Link to={`/rate-seller/${o.auctionId}`} className="btn-primary btn-sm">
                      <Star size={12} /> Rate seller
                    </Link>
                  ))}
                  {o.status === 'PENDING_PAYMENT' && cards.length === 0 && (
                    <Link to="/profile/settings?tab=payment" className="btn-secondary btn-sm">
                      <CreditCard size={12} /> Add payment method
                    </Link>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}

      {!loading && orders.length === 0 && (
        <p className="text-center text-sm text-ink-400 mt-6 flex items-center justify-center gap-1.5">
          <Package size={14} /> Win an auction and the order shows up here.
        </p>
      )}
    </div>
  );
}
