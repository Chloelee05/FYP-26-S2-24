import { useState, useEffect } from 'react';
import { getAdminListings, flagListing, removeListing, restoreListing } from '../../api/admin';
import { formatCurrency } from '../../utils/helpers';

// Backend AdminListingRow fields: auctionId, title, listedDate, sellerUsername,
//   category, currentBid, reportCount, moderationState (ACTIVE/FLAGGED/REMOVED)

const STATUS_STYLE = {
  ACTIVE: 'bg-green-100 text-green-600',
  FLAGGED: 'bg-yellow-100 text-yellow-600',
  REMOVED: 'bg-red-100 text-red-600',
};

const REPORT_STYLE = (n) => n === 0 ? 'bg-green-100 text-green-600' : n < 5 ? 'bg-yellow-100 text-yellow-600' : 'bg-red-100 text-red-600';

export default function AdminListings() {
  const [listings, setListings] = useState([]);

  useEffect(() => {
    getAdminListings().then(r => setListings(r.data ?? [])).catch(() => {});
  }, []);

  const handle = async (action, auctionId) => {
    try {
      if (action === 'flag') await flagListing(auctionId);
      else if (action === 'remove') await removeListing(auctionId);
      else await restoreListing(auctionId);
      setListings(prev => prev.map(l => l.auctionId !== auctionId ? l : {
        ...l,
        moderationState: action === 'flag' ? 'FLAGGED' : action === 'remove' ? 'REMOVED' : 'ACTIVE',
      }));
    } catch {}
  };

  return (
    <div className="p-8">
      <h1 className="text-2xl font-bold text-gray-900 mb-1">Listing Moderation</h1>
      <p className="text-gray-400 text-sm mb-6">Review and moderate auction listings</p>

      <div className="card overflow-hidden">
        <table className="w-full text-sm">
          <thead className="text-xs text-gray-400 uppercase tracking-wide bg-gray-50">
            <tr>
              {['Listing', 'Seller', 'Category', 'Current Bid', 'Reports', 'Status', 'Actions'].map(h => (
                <th key={h} className="px-4 py-3 text-left font-semibold">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-50">
            {listings.map(l => (
              <tr key={l.auctionId} className="hover:bg-gray-50">
                <td className="px-4 py-4">
                  <p className="font-medium text-gray-900">{l.title}</p>
                  <p className="text-xs text-gray-400">Listed {l.listedDate}</p>
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
                  <span className={`px-2 py-0.5 rounded text-xs font-medium ${STATUS_STYLE[l.moderationState] || 'bg-gray-100 text-gray-500'}`}>
                    {l.moderationState}
                  </span>
                </td>
                <td className="px-4 py-4">
                  <div className="flex gap-2">
                    {l.moderationState === 'ACTIVE' && (
                      <>
                        <button onClick={() => handle('flag', l.auctionId)} className="px-3 py-1 bg-yellow-500 hover:bg-yellow-600 text-white text-xs rounded transition-colors">Flag</button>
                        <button onClick={() => handle('remove', l.auctionId)} className="px-3 py-1 bg-red-500 hover:bg-red-600 text-white text-xs rounded transition-colors">Remove</button>
                      </>
                    )}
                    {l.moderationState === 'FLAGGED' && (
                      <button onClick={() => handle('remove', l.auctionId)} className="px-3 py-1 bg-red-500 hover:bg-red-600 text-white text-xs rounded transition-colors">Remove</button>
                    )}
                    {l.moderationState === 'REMOVED' && (
                      <button onClick={() => handle('restore', l.auctionId)} className="px-3 py-1 bg-green-500 hover:bg-green-600 text-white text-xs rounded transition-colors">Restore</button>
                    )}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {listings.length === 0 && (
          <div className="text-center py-10 text-gray-400">No listings found.</div>
        )}
      </div>
    </div>
  );
}
