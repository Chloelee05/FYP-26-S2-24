/*
 * Orders and transactions at "/admin/orders". ADMIN only.
 * Reads GET /api/admin/orders, which returns every order on the platform rather than the one
 * sided view a buyer or seller gets. Two actions are available: resolve a refund request, and
 * correct an order state that has drifted from what really happened.
 * A refund is normally handled by the seller from My sales. The admin resolution here is the
 * escalation path for a dispute the two sides did not settle. Approving cancels the order,
 * declining leaves it running.
 * A state correction always requires a reason and is written to the audit log with the value
 * it replaced. The amount is never editable, because it is the settled sale value that feeds
 * platform commission and the seller's earnings.
 */
import { useState, useEffect, useMemo } from 'react';
import { Link } from 'react-router-dom';
import { AlertTriangle } from 'lucide-react';
import { getAdminOrders, adminResolveRefund, correctOrderStatus } from '../../api/admin';
import { formatCurrency, decodeHtmlEntities } from '../../utils/helpers';
import { apiErrorMessage } from '../../utils/apiError';
import Modal from '../../components/Modal';

const STATUS_STYLE = {
  PENDING_PAYMENT: 'bg-amber-50 text-amber-700 ring-amber-200',
  PAID: 'bg-primary-50 text-primary-700 ring-primary-200',
  COMPLETED: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
  CANCELLED: 'bg-ink-100 text-ink-600 ring-ink-200',
};

const REFUND_STYLE = {
  REQUESTED: 'bg-orange-100 text-orange-700',
  APPROVED: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
  REJECTED: 'bg-ink-100 text-ink-600 ring-ink-200',
};

const STATUS_FILTERS = ['ALL', 'PENDING_PAYMENT', 'PAID', 'COMPLETED', 'CANCELLED'];
const ORDER_STATUSES = ['PENDING_PAYMENT', 'PAID', 'COMPLETED', 'CANCELLED'];

export default function AdminOrders() {
  const [orders, setOrders] = useState([]);
  const [loading, setLoading] = useState(true);
  const [msg, setMsg] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [query, setQuery] = useState('');
  const [busyId, setBusyId] = useState(null);
  const [correcting, setCorrecting] = useState(null);

  const load = () => {
    getAdminOrders()
      .then(r => setOrders(r.data ?? []))
      .catch(() => {})
      .finally(() => setLoading(false));
  };
  useEffect(load, []);

  // Refunds still sitting at REQUESTED are the ones nobody has answered, so they are pulled
  // out into their own panel above the table instead of being hunted for in it.
  const disputes = useMemo(
    () => orders.filter(o => o.refundStatus === 'REQUESTED'),
    [orders]
  );

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return orders.filter(o => {
      if (statusFilter !== 'ALL' && o.status !== statusFilter) return false;
      if (!q) return true;
      return (
        String(o.id).includes(q) ||
        (o.auctionTitle ?? '').toLowerCase().includes(q) ||
        (o.counterparty ?? '').toLowerCase().includes(q)
      );
    });
  }, [orders, statusFilter, query]);

  // Only paid and completed orders count towards volume. A pending payment is not money that
  // has moved, and a cancelled order has been reversed.
  const totalVolume = useMemo(
    () => orders
      .filter(o => o.status === 'PAID' || o.status === 'COMPLETED')
      .reduce((sum, o) => sum + Number(o.amount ?? 0), 0),
    [orders]
  );

  // Settles a dispute in the buyer's or the seller's favour. Confirmed first because it moves
  // money and cannot be undone from this page, and the whole list is reloaded afterwards
  // since approving also changes the order status and the volume figures above.
  const handleResolve = async (orderId, approve) => {
    const verb = approve ? 'approve' : 'decline';
    if (!window.confirm(`${approve ? 'Approve' : 'Decline'} this refund request? ` +
      (approve ? 'The order will be cancelled.' : 'The order will stay active.'))) return;
    setBusyId(orderId);
    setMsg('');
    try {
      const r = await adminResolveRefund(orderId, approve);
      setMsg(r.data?.message ?? `Refund ${verb}d.`);
      load();
    } catch (err) {
      setMsg(apiErrorMessage(err, `Could not ${verb} the refund.`));
    } finally {
      setBusyId(null);
    }
  };

  // The reason is checked here before anything is sent, since an audit entry with no
  // explanation is worth very little. The server requires it as well.
  const saveCorrection = async () => {
    if (!correcting.reason.trim()) {
      setMsg('A reason is required when correcting an order state.');
      return;
    }
    setBusyId(correcting.id);
    setMsg('');
    try {
      const r = await correctOrderStatus(correcting.id, correcting.status, correcting.reason);
      setMsg(r.data?.message ?? 'Order state corrected.');
      setCorrecting(null);
      load();
    } catch (err) {
      setMsg(apiErrorMessage(err, 'Could not correct the order state.'));
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="p-8">
      <h1 className="page-title">Orders & Transactions</h1>
      <p className="page-subtitle mb-6">
        Monitor all financial activity, resolve disputes, and correct a drifted order state
      </p>

      {msg && <div className="text-sm text-primary-600 mb-4">{msg}</div>}

      {/* Summary */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
        {[
          { label: 'Total Transactions', value: orders.length },
          { label: 'Transaction Volume', value: formatCurrency(totalVolume) },
          { label: 'Open Disputes', value: disputes.length },
          { label: 'Cancelled / Refunded', value: orders.filter(o => o.status === 'CANCELLED').length },
        ].map(s => (
          <div key={s.label} className="card p-4">
            <p className="text-xs text-ink-500 font-medium mb-1">{s.label}</p>
            <p className="page-title">{s.value}</p>
          </div>
        ))}
      </div>

      {/* Disputes / refund requests */}
      {disputes.length > 0 && (
        <div className="card p-5 mb-6 border-l-4 border-orange-400">
          <h2 className="font-bold text-ink-900 mb-1 flex items-center gap-2">
            <AlertTriangle size={16} className="text-orange-500" />
            Refund Requests Awaiting Resolution
          </h2>
          <p className="text-sm text-ink-400 mb-4">
            Approving cancels the order and refunds the buyer; declining keeps the order active.
          </p>
          <div className="space-y-3">
            {disputes.map(o => (
              <div key={o.id} className="flex flex-wrap items-center gap-3 bg-orange-50/50 rounded-lg p-3">
                <div className="flex-1 min-w-[220px]">
                  <p className="text-sm font-medium text-ink-900">
                    Order #{o.id} —{' '}
                    <Link to={`/auction/${o.auctionId}`} className="link-subtle">
                      {o.auctionTitle}
                    </Link>
                  </p>
                  <p className="text-xs text-ink-500">{o.counterparty} · {formatCurrency(o.amount)}</p>
                  {o.refundReason && (
                    <p className="text-xs text-ink-600 mt-1 italic">
                      Reason: {decodeHtmlEntities(o.refundReason)}
                    </p>
                  )}
                </div>
                <div className="flex gap-2">
                  <button
                    type="button"
                    disabled={busyId === o.id}
                    onClick={() => handleResolve(o.id, true)}
                    className="px-3 py-1.5 bg-green-600 hover:bg-green-700 text-white text-xs font-medium rounded-lg disabled:opacity-50"
                  >
                    Approve refund
                  </button>
                  <button
                    type="button"
                    disabled={busyId === o.id}
                    onClick={() => handleResolve(o.id, false)}
                    className="px-3 py-1.5 border border-ink-300 hover:bg-ink-100 text-ink-700 text-xs font-medium rounded-lg disabled:opacity-50"
                  >
                    Decline
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Filters */}
      <div className="flex flex-wrap items-center gap-3 mb-4">
        <input
          type="text"
          value={query}
          onChange={e => setQuery(e.target.value)}
          placeholder="Search order #, auction, buyer or seller…"
          className="input-field w-72"
        />
        <div className="flex gap-1">
          {STATUS_FILTERS.map(s => (
            <button
              key={s}
              type="button"
              onClick={() => setStatusFilter(s)}
              className={`tab-pill text-xs px-3 py-1.5 ${statusFilter === s ? 'tab-pill-active' : ''}`}
            >
              {s === 'ALL' ? 'All' : s.replace('_', ' ')}
            </button>
          ))}
        </div>
      </div>

      <div className="card overflow-hidden">
        {loading ? (
          <div className="text-center py-10 text-ink-400 text-sm">Loading orders…</div>
        ) : filtered.length === 0 ? (
          <div className="text-center py-10 text-ink-400 text-sm">No matching orders.</div>
        ) : (
          <table className="w-full text-sm">
            <thead className="text-xs text-ink-500 uppercase tracking-wider bg-ink-50 border-b border-ink-200">
              <tr>
                {['Order', 'Auction', 'Parties', 'Amount', 'Status', 'Refund', 'Created', 'Paid', 'Completed', ''].map(h => (
                  <th key={h} className="px-4 py-3 text-left font-bold whitespace-nowrap">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-ink-100">
              {filtered.map(o => (
                <tr key={o.id} className="hover:bg-ink-50 transition-colors">
                  <td className="px-4 py-3 font-medium">#{o.id}</td>
                  <td className="px-4 py-3">
                    <Link to={`/auction/${o.auctionId}`} className="link-subtle">{o.auctionTitle}</Link>
                  </td>
                  <td className="px-4 py-3 text-ink-600 text-xs">{o.counterparty}</td>
                  <td className="px-4 py-3 font-medium">{formatCurrency(o.amount)}</td>
                  <td className="px-4 py-3">
                    <span className={`badge ${STATUS_STYLE[o.status] || 'bg-ink-100'}`}>
                      {o.status?.replace('_', ' ')}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    {o.refundStatus ? (
                      <span className={`badge ${REFUND_STYLE[o.refundStatus] || 'bg-ink-100'}`}>
                        {o.refundStatus}
                      </span>
                    ) : (
                      <span className="text-ink-300 text-xs">—</span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-ink-400 text-xs whitespace-nowrap">
                    {o.createdAt ? new Date(o.createdAt).toLocaleString() : '—'}
                  </td>
                  <td className="px-4 py-3 text-ink-400 text-xs whitespace-nowrap">
                    {o.paidAt ? new Date(o.paidAt).toLocaleString() : '—'}
                  </td>
                  <td className="px-4 py-3 text-ink-400 text-xs whitespace-nowrap">
                    {o.completedAt ? new Date(o.completedAt).toLocaleString() : '—'}
                  </td>
                  <td className="px-4 py-3">
                    <button
                      type="button"
                      onClick={() => setCorrecting({ id: o.id, status: o.status, reason: '' })}
                      className="text-xs font-medium text-primary-600 hover:text-primary-800 whitespace-nowrap"
                    >
                      Correct state
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {correcting && (
        <Modal
          title="Correct order state"
          subtitle={`Order #${correcting.id}`}
          onClose={() => setCorrecting(null)}
        >
          <div className="p-6 space-y-4">
            <p className="text-xs text-ink-500 leading-relaxed bg-ink-50 rounded-lg p-3">
              Use this to reconcile an order whose recorded state has drifted from what
              actually happened — a payment settled out of band, or a delivery that never got
              marked. The amount is not editable: it is the settled sale value and it feeds
              platform revenue and the seller's earnings. The change is recorded in the admin
              audit log with its previous value.
            </p>
            <div>
              <label className="block text-xs text-ink-500 mb-1" htmlFor="correct-status">
                New state
              </label>
              <select
                id="correct-status"
                value={correcting.status}
                onChange={e => setCorrecting(c => ({ ...c, status: e.target.value }))}
                className="input-field w-full"
              >
                {ORDER_STATUSES.map(s => (
                  <option key={s} value={s}>{s.replace('_', ' ')}</option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-xs text-ink-500 mb-1" htmlFor="correct-reason">
                Reason (required, recorded in the audit log)
              </label>
              <input
                id="correct-reason"
                value={correcting.reason}
                onChange={e => setCorrecting(c => ({ ...c, reason: e.target.value }))}
                placeholder="e.g. buyer paid by bank transfer outside the platform"
                className="input-field w-full"
              />
            </div>
            <div className="flex gap-2 pt-2">
              <button
                type="button"
                onClick={saveCorrection}
                disabled={busyId === correcting.id}
                className="btn-primary"
              >
                {busyId === correcting.id ? 'Saving…' : 'Apply correction'}
              </button>
              <button type="button" onClick={() => setCorrecting(null)} className="btn-secondary">
                Cancel
              </button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
}
