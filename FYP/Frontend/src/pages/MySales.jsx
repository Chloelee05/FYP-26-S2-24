/*
 * Seller order list at "/sales". Behind ProtectedRoute with requireSeller, so an account
 * without the selling capability is shown the enable selling gate instead. It is the mirror
 * of MyPurchases: the same GET /api/orders response, filtered to rows where role is "seller".
 * The seller drives the order forward from here. advanceOrderShipping steps the delivery
 * state one stage at a time, and a refund request is approved or declined here rather than
 * by an admin. Rating the buyer becomes available once the order is complete.
 * Rows are grouped into lifecycle tabs from utils/orders, and the current view can be
 * exported to CSV for the seller's own records.
 */
import { useState, useEffect, useMemo } from 'react';
import { Link } from 'react-router-dom';
import {
  Tag, Truck, MessageCircle, Star, Check, Search, Download, ImageIcon, RotateCcw,
} from 'lucide-react';
import { getOrders, advanceOrderShipping, resolveOrderRefund } from '../api/orders';
import { formatCurrency } from '../utils/helpers';
import { publicPath } from '../utils/appBase';
import {
  SALE_TABS, inTab, orderRef, orderHeadline, shippingLabel, nextShippingAction,
  orderBadgeClass, DATE_FILTERS, withinDateFilter,
} from '../utils/orders';
import OrderMessageModal from '../components/OrderMessageModal';
import RateBuyerModal from '../components/RateBuyerModal';
import { apiErrorMessage } from '../utils/apiError';

/**
 * True when the order is waiting on something the seller has to do: a refund to answer, a
 * shipping step to advance, or a buyer left unrated. Tints the row so a busy list still
 * shows where the work is.
 */
function needsMyAction(o) {
  if ((o.refundStatus || '').toUpperCase() === 'REQUESTED') return true;
  if (o.status === 'PAID' && nextShippingAction(o)) return true;
  if (o.status === 'COMPLETED' && !o.hasRated) return true;
  return false;
}

/** Quotes a value for CSV — commas, quotes and newlines all survive the round trip. */
const csvCell = (value) => `"${String(value ?? '').replace(/"/g, '""')}"`;

// Builds the CSV in the browser and downloads it through a temporary blob URL, so no export
// endpoint is needed. Exports the currently filtered view, not every order.
function exportCsv(rows) {
  const header = ['Order ID', 'Listing', 'Buyer', 'Delivery', 'Earnings', 'Status', 'Ordered'];
  const body = rows.map(o => [
    orderRef(o.id),
    o.auctionTitle,
    o.counterparty,
    shippingLabel(o) ?? '—',
    o.amount,
    o.status,
    o.createdAt ? new Date(o.createdAt).toLocaleDateString() : '',
  ]);
  const csv = [header, ...body].map(r => r.map(csvCell).join(',')).join('\n');
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = `sale-records-${new Date().toISOString().slice(0, 10)}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}

export default function MySales() {
  const [orders, setOrders] = useState([]);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState('unpaid');
  const [query, setQuery] = useState('');
  const [dateFilter, setDateFilter] = useState('all');
  const [message, setMessage] = useState('');

  // Order a modal is open for, or null: messaging the buyer, and rating them.
  const [contactOrder, setContactOrder] = useState(null);
  const [rateOrder, setRateOrder] = useState(null);

  /**
   * @param selectTab on the first load, open the earliest tab that actually has
   *                  orders — landing on an empty "To start" reads as "no sales".
   */
  const loadOrders = (selectTab = false) =>
    getOrders()
      .then(r => {
        const mine = (r.data ?? []).filter(o => o.role === 'seller');
        setOrders(mine);
        if (selectTab) {
          const first = SALE_TABS.find(t => mine.some(o => inTab(o, t)));
          if (first) setTab(first.key);
        }
      })
      .catch(() => {})
      .finally(() => setLoading(false));

  useEffect(() => { loadOrders(true); }, []);

  const counts = useMemo(() => {
    const out = {};
    SALE_TABS.forEach(t => { out[t.key] = orders.filter(o => inTab(o, t)).length; });
    return out;
  }, [orders]);

  const visible = useMemo(() => {
    const active = SALE_TABS.find(t => t.key === tab);
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

  // Moves the delivery one step along. The server decides what the next state is, which is
  // why no target status is sent; nextShippingAction only supplies the button label.
  const handleAdvanceShipping = async (orderId) => {
    setMessage('');
    try { await advanceOrderShipping(orderId); loadOrders(); }
    catch (err) { setMessage(apiErrorMessage(err, 'Could not update shipping.')); }
  };

  // Answers a buyer's refund request. Approving cancels the order outright, so both outcomes
  // are confirmed first. A buyer who is unhappy with a decline can escalate through support.
  const handleResolveRefund = async (orderId, approve) => {
    if (!window.confirm(`Are you sure you want to ${approve ? 'approve' : 'decline'} this refund request?`)) return;
    setMessage('');
    try {
      await resolveOrderRefund(orderId, approve);
      setMessage(approve ? 'Refund approved and order cancelled.' : 'Refund request declined.');
      loadOrders();
    } catch (err) {
      setMessage(apiErrorMessage(err, 'Could not update the refund request.'));
    }
  };

  // Sum of the rows on screen, so it tracks the tab and filters. This is the gross order
  // amount before the platform commission, which is shown on the seller dashboard.
  const totalEarnings = visible.reduce((sum, o) => sum + (Number(o.amount) || 0), 0);

  return (
    <div className="max-w-6xl mx-auto px-4 py-8">
      <div className="flex flex-wrap items-center justify-between gap-3 mb-6">
        <div>
          <h1 className="page-title">My sales</h1>
          <p className="page-subtitle">Orders from the items you sold, and what each one needs next.</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Link to="/seller/listings" className="btn-secondary">My listings</Link>
          <button
            onClick={() => exportCsv(visible)}
            disabled={visible.length === 0}
            className="btn-secondary"
          >
            <Download size={14} /> Export sale records
          </button>
        </div>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 border-b border-ink-200 overflow-x-auto mb-5">
        {SALE_TABS.map(({ key, label }) => (
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
            placeholder="Search order ID, buyer or listing"
            aria-label="Search sales"
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
          {visible.length} {visible.length === 1 ? 'result' : 'results'}
          {visible.length > 0 && <> · {formatCurrency(totalEarnings)}</>}
        </span>
      </div>

      {message && <div className="alert-info mb-4"><span>{message}</span></div>}

      {contactOrder && <OrderMessageModal order={contactOrder} onClose={() => setContactOrder(null)} />}
      {rateOrder && (
        <RateBuyerModal
          order={rateOrder}
          onClose={() => setRateOrder(null)}
          onRated={() => { setMessage('Rating submitted.'); loadOrders(); }}
        />
      )}

      {loading ? (
        <div className="space-y-3">
          {Array.from({ length: 4 }, (_, i) => <div key={i} className="skeleton h-16 rounded-xl" />)}
        </div>
      ) : visible.length === 0 ? (
        <div className="card p-16 text-center">
          <span className="grid place-items-center w-14 h-14 rounded-2xl bg-ink-100 text-ink-400 mx-auto mb-4">
            <Tag size={24} />
          </span>
          <p className="font-semibold text-ink-800">Nothing here yet</p>
          <p className="text-sm text-ink-500 mt-1">
            Sales land in this tab once one of your listings reaches that stage.
          </p>
          <Link to="/seller/create" className="btn-primary mt-6">Create a listing</Link>
        </div>
      ) : (
        // table-clean strips the outer cells' side padding, so the table needs a
        // padded parent or the first and last columns collide with the card edge.
        <div className="card p-5">
          <div className="overflow-x-auto">
            <table className="table-clean">
              <thead>
                <tr>
                  {/* Thumbnail and title share one cell: it keeps them visually
                      paired and drops a column the row could not spare. */}
                  <th>Listing</th>
                  <th>Order ID</th>
                  <th>Buyer</th>
                  {/* No delivery column: the status cell already spells out the
                      shipping step, and it was an empty dash before payment. */}
                  <th className="text-right">Earnings</th>
                  <th>Status</th>
                  <th className="text-right">Action</th>
                </tr>
              </thead>
              <tbody>
                {visible.map(o => {
                  const refund = (o.refundStatus || '').toUpperCase();
                  const shipAction = o.status === 'PAID' ? nextShippingAction(o) : null;
                  return (
                    <tr key={o.id} className={needsMyAction(o) ? 'bg-accent-50/40' : undefined}>
                      <td>
                        <div className="flex items-center gap-3">
                          <Link
                            to={`/auction/${o.auctionId}`}
                            className="grid place-items-center w-12 h-12 rounded-lg bg-ink-50 text-ink-300 overflow-hidden shrink-0"
                          >
                            {o.thumbnailUrl ? (
                              <img src={publicPath(o.thumbnailUrl)} alt="" loading="lazy" className="w-full h-full object-contain p-1" />
                            ) : (
                              <ImageIcon size={18} />
                            )}
                          </Link>
                          <Link
                            to={`/auction/${o.auctionId}`}
                            className="font-medium text-ink-800 hover:text-primary-600 transition-colors line-clamp-2 max-w-[15rem]"
                          >
                            {o.auctionTitle}
                          </Link>
                        </div>
                      </td>
                      <td className="text-ink-500 font-mono text-xs whitespace-nowrap">{orderRef(o.id)}</td>
                      {/* Buyers have no public profile page, so the name stays plain text. */}
                      <td className="text-ink-800 whitespace-nowrap">{o.counterparty}</td>
                      <td className="text-right font-semibold tabular-nums whitespace-nowrap">
                        {formatCurrency(o.amount)}
                      </td>
                      <td>
                        <span className={`${orderBadgeClass(o.status)} whitespace-nowrap`}>
                          {orderHeadline(o, 'seller')}
                        </span>
                        {refund === 'REQUESTED' && o.refundReason && (
                          <p className="text-xs text-ink-500 mt-1 italic max-w-[12rem] line-clamp-2">“{o.refundReason}”</p>
                        )}
                        {o.status === 'PAID' && (o.shippingStatus || '').toUpperCase() === 'DELIVERED' && (
                          <p className="text-xs text-amber-600 mt-1 max-w-[10rem]">Waiting on buyer confirmation</p>
                        )}
                      </td>
                      <td>
                        <div className="flex items-center justify-end gap-1.5">
                          {/* Shipping is frozen while a refund is open: dispatching an item
                              the seller may be about to refund helps nobody. */}
                          {shipAction && refund !== 'REQUESTED' && (
                            <button onClick={() => handleAdvanceShipping(o.id)} className="btn-primary btn-sm whitespace-nowrap">
                              <Truck size={12} /> {shipAction}
                            </button>
                          )}
                          {refund === 'REQUESTED' && (
                            <>
                              <button onClick={() => handleResolveRefund(o.id, true)} className="btn-success btn-sm">
                                <RotateCcw size={12} /> Approve
                              </button>
                              <button
                                onClick={() => handleResolveRefund(o.id, false)}
                                className="btn-secondary btn-sm text-red-600 border-red-200 hover:bg-red-50 hover:border-red-300"
                              >
                                Decline
                              </button>
                            </>
                          )}
                          {o.status === 'COMPLETED' && (o.hasRated ? (
                            <span className="inline-flex items-center gap-1 text-xs font-medium text-ink-400 whitespace-nowrap">
                              <Check size={13} /> Rated
                            </span>
                          ) : (
                            <button onClick={() => setRateOrder(o)} className="btn-primary btn-sm whitespace-nowrap">
                              <Star size={12} /> Rate buyer
                            </button>
                          ))}
                          {o.status !== 'PENDING_PAYMENT' && o.status !== 'CANCELLED' && (
                            <button
                              onClick={() => setContactOrder(o)}
                              aria-label={`Message ${o.counterparty}`}
                              title={`Message ${o.counterparty}`}
                              className="p-2 rounded-lg text-ink-400 hover:text-primary-600 hover:bg-primary-50 transition-colors shrink-0"
                            >
                              <MessageCircle size={16} />
                            </button>
                          )}
                          {/* Keeps the column from collapsing on rows with no action. */}
                          {o.status === 'PENDING_PAYMENT' && (
                            <span className="text-xs text-ink-300">Awaiting buyer</span>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
