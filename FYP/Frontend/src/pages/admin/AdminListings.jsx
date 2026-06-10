import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { getAdminListings, flagListing, removeListing, restoreListing } from '../../api/admin';
import { formatCurrency } from '../../utils/helpers';

const STATUS_STYLE = {
  ACTIVE: 'bg-green-100 text-green-600',
  FLAGGED: 'bg-yellow-100 text-yellow-600',
  REMOVED: 'bg-red-100 text-red-600',
};

const REPORT_STYLE = (n) => n === 0 ? 'bg-green-100 text-green-600' : n < 5 ? 'bg-yellow-100 text-yellow-600' : 'bg-red-100 text-red-600';

const normState = (s) => (s ?? '').toUpperCase();

export default function AdminListings() {
  const [listings, setListings] = useState([]);
  const [selected, setSelected] = useState(null);
  const [msg, setMsg] = useState('');

  const reload = () => getAdminListings().then(r => setListings(r.data ?? [])).catch(() => {});

  useEffect(() => { reload(); }, []);

  const handle = async (action, auctionId) => {
    setMsg('');
    try {
      if (action === 'flag') await flagListing(auctionId);
      else if (action === 'remove') await removeListing(auctionId);
      else await restoreListing(auctionId);
      const nextState = action === 'flag' ? 'FLAGGED' : action === 'remove' ? 'REMOVED' : 'ACTIVE';
      setListings(prev => prev.map(l => l.auctionId !== auctionId ? l : { ...l, moderationState: nextState }));
      if (selected?.auctionId === auctionId) {
        setSelected(l => ({ ...l, moderationState: nextState }));
      }
      setMsg(`Listing ${action === 'flag' ? 'flagged' : action === 'remove' ? 'removed' : 'restored'}.`);
    } catch {
      setMsg('Action failed.');
    }
  };

  return (
    <div className="p-8">
      <h1 className="text-2xl font-bold text-gray-900 mb-1">Listing Moderation</h1>
      <p className="text-gray-400 text-sm mb-6">Click a row to review details and take action</p>
      {msg && <div className="text-sm text-blue-600 mb-4">{msg}</div>}

      <div className="card overflow-hidden">
        <table className="w-full text-sm">
          <thead className="text-xs text-gray-400 uppercase tracking-wide bg-gray-50">
            <tr>
              {['Listing', 'Seller', 'Category', 'Current Bid', 'Reports', 'Status'].map(h => (
                <th key={h} className="px-4 py-3 text-left font-semibold">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-50">
            {listings.map(l => {
              const state = normState(l.moderationState);
              return (
                <tr
                  key={l.auctionId}
                  onClick={() => setSelected({ ...l, moderationState: state })}
                  className="hover:bg-blue-50 cursor-pointer"
                >
                  <td className="px-4 py-4">
                    <p className="font-medium text-gray-900">{l.title}</p>
                    <p className="text-xs text-gray-400">#{l.auctionId} · Listed {l.listedDate}</p>
                  </td>
                  <td className="px-4 py-4 text-gray-600">{l.sellerUsername}</td>
                  <td className="px-4 py-4 text-gray-600">{l.category}</td>
                  <td className="px-4 py-4 font-medium">{formatCurrency(l.currentBid)}</td>
                  <td className="px-4 py-4">
                    <span className={`px-2 py-0.5 rounded text-xs font-medium ${REPORT_STYLE(l.reportCount ?? 0)}`}>
                      {l.reportCount ?? 0} reports
                    </span>
                  </td>
                  <td className="px-4 py-4">
                    <span className={`px-2 py-0.5 rounded text-xs font-medium ${STATUS_STYLE[state] || 'bg-gray-100 text-gray-500'}`}>
                      {state}
                    </span>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {listings.length === 0 && (
          <div className="text-center py-10 text-gray-400">No listings found.</div>
        )}
      </div>

      {selected && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4" onClick={() => setSelected(null)}>
          <div className="bg-white rounded-2xl shadow-xl max-w-lg w-full p-6" onClick={e => e.stopPropagation()}>
            <h2 className="text-lg font-bold text-gray-900 mb-1">{selected.title}</h2>
            <p className="text-sm text-gray-500 mb-4">Auction #{selected.auctionId}</p>
            <dl className="grid grid-cols-2 gap-3 text-sm mb-5">
              <div><dt className="text-gray-400">Seller</dt><dd className="font-medium">{selected.sellerUsername}</dd></div>
              <div><dt className="text-gray-400">Category</dt><dd>{selected.category}</dd></div>
              <div><dt className="text-gray-400">Current bid</dt><dd>{formatCurrency(selected.currentBid)}</dd></div>
              <div><dt className="text-gray-400">Reports</dt><dd>{selected.reportCount ?? 0}</dd></div>
              <div><dt className="text-gray-400">Status</dt>
                <dd><span className={`px-2 py-0.5 rounded text-xs font-medium ${STATUS_STYLE[normState(selected.moderationState)]}`}>
                  {normState(selected.moderationState)}
                </span></dd>
              </div>
              <div><dt className="text-gray-400">Listed</dt><dd>{selected.listedDate}</dd></div>
            </dl>
            <div className="flex flex-wrap gap-2 mb-4">
              {normState(selected.moderationState) === 'ACTIVE' && (
                <>
                  <button onClick={() => handle('flag', selected.auctionId)} className="px-4 py-2 bg-yellow-500 hover:bg-yellow-600 text-white text-sm rounded-lg">Flag</button>
                  <button onClick={() => handle('remove', selected.auctionId)} className="px-4 py-2 bg-red-500 hover:bg-red-600 text-white text-sm rounded-lg">Remove</button>
                </>
              )}
              {normState(selected.moderationState) === 'FLAGGED' && (
                <button onClick={() => handle('remove', selected.auctionId)} className="px-4 py-2 bg-red-500 hover:bg-red-600 text-white text-sm rounded-lg">Remove</button>
              )}
              {normState(selected.moderationState) === 'REMOVED' && (
                <button onClick={() => handle('restore', selected.auctionId)} className="px-4 py-2 bg-green-500 hover:bg-green-600 text-white text-sm rounded-lg">Restore</button>
              )}
              <Link to={`/auction/${selected.auctionId}`} className="px-4 py-2 border border-gray-200 text-gray-700 text-sm rounded-lg hover:bg-gray-50">
                View auction
              </Link>
            </div>
            <button onClick={() => setSelected(null)} className="w-full py-2 text-sm text-gray-500 hover:text-gray-700">Close</button>
          </div>
        </div>
      )}
    </div>
  );
}
