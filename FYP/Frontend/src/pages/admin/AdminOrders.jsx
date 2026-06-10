import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { getAdminOrders } from '../../api/admin';
import { formatCurrency } from '../../utils/helpers';

const STATUS_STYLE = {
  PENDING_PAYMENT: 'bg-yellow-100 text-yellow-700',
  PAID: 'bg-blue-100 text-blue-700',
  COMPLETED: 'bg-green-100 text-green-700',
  CANCELLED: 'bg-gray-100 text-gray-500',
};

export default function AdminOrders() {
  const [orders, setOrders] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    getAdminOrders()
      .then(r => setOrders(r.data ?? []))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  return (
    <div className="p-8">
      <h1 className="text-2xl font-bold text-gray-900 mb-1">Orders & Transactions</h1>
      <p className="text-gray-400 text-sm mb-6">All platform orders across buyers and sellers</p>

      <div className="card overflow-hidden">
        {loading ? (
          <div className="text-center py-10 text-gray-400 text-sm">Loading orders…</div>
        ) : orders.length === 0 ? (
          <div className="text-center py-10 text-gray-400 text-sm">No orders yet.</div>
        ) : (
          <table className="w-full text-sm">
            <thead className="text-xs text-gray-400 uppercase tracking-wide bg-gray-50">
              <tr>
                {['Order', 'Auction', 'Parties', 'Amount', 'Status', 'Created', 'Paid', 'Completed'].map(h => (
                  <th key={h} className="px-4 py-3 text-left font-semibold">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-50">
              {orders.map(o => (
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
