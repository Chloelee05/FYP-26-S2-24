import { useState, useEffect, useMemo } from 'react';
import { Link } from 'react-router-dom';
import { AlertTriangle } from 'lucide-react';
import { getAdminOrders, adminResolveRefund } from '../../api/admin';
import { formatCurrency, decodeHtmlEntities } from '../../utils/helpers';

const STATUS_STYLE = {
  PENDING_PAYMENT: 'bg-yellow-100 text-yellow-700',
  PAID: 'bg-blue-100 text-blue-700',
  COMPLETED: 'bg-green-100 text-green-700',
  CANCELLED: 'bg-gray-100 text-gray-500',
};

const REFUND_STYLE = {
  REQUESTED: 'bg-orange-100 text-orange-700',
  APPROVED: 'bg-green-100 text-green-700',
  REJECTED: 'bg-gray-100 text-gray-500',
};

const STATUS_FILTERS = ['ALL', 'PENDING_PAYMENT', 'PAID', 'COMPLETED', 'CANCELLED'];

export default function AdminOrders() {
  const [orders, setOrders] = useState([]);
  const [loading, setLoading] = useState(true);
  const [msg, setMsg] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [query, setQuery] = useState('');
  const [busyId, setBusyId] = useState(null);

  const load = () => {
    getAdminOrders()
      .then(r => setOrders(r.data ?? []))
      .catch(() => {})
      .finally(() => setLoading(false));
  };
  useEffect(load, []);

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

  const totalVolume = useMemo(
    () => orders
      .filter(o => o.status === 'PAID' || o.status === 'COMPLETED')
      .reduce((sum, o) => sum + Number(o.amount ?? 0), 0),
    [orders]
  );

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
      setMsg(err.response?.data?.error || `Could not ${verb} the refund.`);
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="p-8">
      <h1 className="text-2xl font-bold text-gray-900 mb-1">Orders & Transactions</h1>
      <p className="text-gray-400 text-sm mb-6">Monitor all financial activity and resolve disputes</p>

      {msg && <div className="text-sm text-blue-600 mb-4">{msg}</div>}

      {/* Summary */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
        {[
          { label: 'Total Transactions', value: orders.length },
          { label: 'Transaction Volume', value: formatCurrency(totalVolume) },
          { label: 'Open Disputes', value: disputes.length },
          { label: 'Cancelled / Refunded', value: orders.filter(o => o.status === 'CANCELLED').length },
        ].map(s => (
          <div key={s.label} className="card p-4">
            <p className="text-xs text-gray-500 font-medium mb-1">{s.label}</p>
            <p className="text-2xl font-bold text-gray-900">{s.value}</p>
          </div>
        ))}
      </div>

      {/* Disputes / refund requests */}
      {disputes.length > 0 && (
        <div className="card p-5 mb-6 border-l-4 border-orange-400">
          <h2 className="font-bold text-gray-900 mb-1 flex items-center gap-2">
            <AlertTriangle size={16} className="text-orange-500" />
            Refund Requests Awaiting Resolution
          </h2>
          <p className="text-sm text-gray-400 mb-4">
            Approving cancels the order and refunds the buyer; declining keeps the order active.
          </p>
          <div className="space-y-3">
            {disputes.map(o => (
              <div key={o.id} className="flex flex-wrap items-center gap-3 bg-orange-50/50 rounded-lg p-3">
                <div className="flex-1 min-w-[220px]">
                  <p className="text-sm font-medium text-gray-900">
                    Order #{o.id} —{' '}
                    <Link to={`/auction/${o.auctionId}`} className="text-blue-500 hover:underline">
                      {o.auctionTitle}
                    </Link>
                  </p>
                  <p className="text-xs text-gray-500">{o.counterparty} · {formatCurrency(o.amount)}</p>
                  {o.refundReason && (
                    <p className="text-xs text-gray-600 mt-1 italic">
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
                    className="px-3 py-1.5 border border-gray-300 hover:bg-gray-100 text-gray-700 text-xs font-medium rounded-lg disabled:opacity-50"
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
          className="border border-gray-200 rounded-lg px-3 py-2 text-sm w-72"
        />
        <div className="flex gap-1">
          {STATUS_FILTERS.map(s => (
            <button
              key={s}
              type="button"
              onClick={() => setStatusFilter(s)}
              className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
                statusFilter === s ? 'bg-gray-900 text-white' : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
              }`}
            >
              {s === 'ALL' ? 'All' : s.replace('_', ' ')}
            </button>
          ))}
        </div>
      </div>

      <div className="card overflow-hidden">
        {loading ? (
          <div className="text-center py-10 text-gray-400 text-sm">Loading orders…</div>
        ) : filtered.length === 0 ? (
          <div className="text-center py-10 text-gray-400 text-sm">No matching orders.</div>
        ) : (
          <table className="w-full text-sm">
            <thead className="text-xs text-gray-400 uppercase tracking-wide bg-gray-50">
              <tr>
                {['Order', 'Auction', 'Parties', 'Amount', 'Status', 'Refund', 'Created', 'Paid', 'Completed'].map(h => (
                  <th key={h} className="px-4 py-3 text-left font-semibold">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-50">
              {filtered.map(o => (
                <tr key={o.id} className="hover:bg-gray-50">
                  <td className="px-4 py-3 font-medium">#{o.id}</td>
                  <td className="px-4 py-3">
                    <Link to={`/auction/${o.auctionId}`} className="text-blue-500 hover:underline">{o.auctionTitle}</Link>
                  </td>
                  <td className="px-4 py-3 text-gray-600 text-xs">{o.counterparty}</td>
                  <td className="px-4 py-3 font-medium">{formatCurrency(o.amount)}</td>
                  <td className="px-4 py-3">
                    <span className={`px-2 py-0.5 rounded text-xs font-medium ${STATUS_STYLE[o.status] || 'bg-gray-100'}`}>
                      {o.status?.replace('_', ' ')}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    {o.refundStatus ? (
                      <span className={`px-2 py-0.5 rounded text-xs font-medium ${REFUND_STYLE[o.refundStatus] || 'bg-gray-100'}`}>
                        {o.refundStatus}
                      </span>
                    ) : (
                      <span className="text-gray-300 text-xs">—</span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-gray-400 text-xs whitespace-nowrap">
                    {o.createdAt ? new Date(o.createdAt).toLocaleString() : '—'}
                  </td>
                  <td className="px-4 py-3 text-gray-400 text-xs whitespace-nowrap">
                    {o.paidAt ? new Date(o.paidAt).toLocaleString() : '—'}
                  </td>
                  <td className="px-4 py-3 text-gray-400 text-xs whitespace-nowrap">
                    {o.completedAt ? new Date(o.completedAt).toLocaleString() : '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
