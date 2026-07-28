import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import {
  getAdminListings, flagListing, removeListing, restoreListing,
  featureListing, unfeatureListing,
} from '../../api/admin';
import { formatCurrency } from '../../utils/helpers';
import Modal from '../../components/Modal';

const MODERATION_STYLE = {
  ACTIVE:  'bg-emerald-50 text-emerald-700 ring-emerald-200',
  FLAGGED: 'bg-amber-50 text-amber-700 ring-amber-200',
  REMOVED: 'bg-red-50 text-red-700 ring-red-200',
};

const AUCTION_STATUS_STYLE = {
  Active:    'bg-primary-50 text-primary-700 ring-primary-200',
  Pending:   'bg-accent-50 text-accent-700 ring-accent-200',
  Finished:  'bg-ink-100 text-ink-600 ring-ink-200',
  Cancelled: 'bg-red-50 text-red-700 ring-red-200',
};

const REPORT_STYLE = (n) => n === 0 ? 'bg-emerald-50 text-emerald-700 ring-emerald-200' : n < 5 ? 'bg-amber-50 text-amber-700 ring-amber-200' : 'bg-red-50 text-red-700 ring-red-200';

const normState = (s) => (s ?? '').toUpperCase();

export default function AdminListings() {
  const [listings, setListings] = useState([]);
  const [selected, setSelected] = useState(null);
  const [msg, setMsg] = useState('');

  const reload = () => getAdminListings().then(r => setListings(r.data ?? [])).catch(() => {});

  useEffect(() => { reload(); }, []);

  const patchListing = (auctionId, patch) => {
    setListings(prev => prev.map(l => l.auctionId !== auctionId ? l : { ...l, ...patch }));
    if (selected?.auctionId === auctionId) {
      setSelected(l => ({ ...l, ...patch }));
    }
  };

  const handle = async (action, auctionId) => {
    setMsg('');
    try {
      if (action === 'flag') await flagListing(auctionId);
      else if (action === 'remove') await removeListing(auctionId);
      else if (action === 'restore') await restoreListing(auctionId);
      else if (action === 'feature') await featureListing(auctionId, 7);
      else if (action === 'unfeature') await unfeatureListing(auctionId);

      if (action === 'flag') patchListing(auctionId, { moderationState: 'FLAGGED' });
      else if (action === 'remove') patchListing(auctionId, { moderationState: 'REMOVED' });
      else if (action === 'restore') patchListing(auctionId, { moderationState: 'ACTIVE' });
      else if (action === 'feature') patchListing(auctionId, { featured: true });
      else if (action === 'unfeature') patchListing(auctionId, { featured: false });

      const labels = {
        flag: 'flagged',
        remove: 'removed',
        restore: 'restored',
        feature: 'featured for 7 days ($9.99 platform fee recorded)',
        unfeature: 'removed from featured',
      };
      setMsg(`Listing ${labels[action]}.`);
    } catch {
      setMsg('Action failed.');
    }
  };

  return (
    <div className="p-8">
      <h1 className="page-title">Listing Moderation</h1>
      <p className="page-subtitle mb-6">Click a row to review details and take action</p>
      {msg && <div className="text-sm text-primary-600 mb-4">{msg}</div>}

      <div className="card overflow-hidden">
        <table className="w-full text-sm">
          <thead className="text-xs text-ink-500 uppercase tracking-wider bg-ink-50 border-b border-ink-200">
            <tr>
              {['Listing', 'Seller', 'Category', 'Current Bid', 'Reports', 'Featured', 'Lifecycle', 'Moderation'].map(h => (
                <th key={h} className="px-4 py-3 text-left font-bold whitespace-nowrap">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-ink-100">
            {listings.map(l => {
              const state = normState(l.moderationState);
              return (
                <tr
                  key={l.auctionId}
                  onClick={() => setSelected({ ...l, moderationState: state })}
                  className="hover:bg-primary-50/60 cursor-pointer transition-colors"
                >
                  <td className="px-4 py-4">
                    <p className="font-medium text-ink-900">{l.title}</p>
                    <p className="text-xs text-ink-400">#{l.auctionId} · Listed {l.listedDate}</p>
                  </td>
                  <td className="px-4 py-4 text-ink-600">{l.sellerUsername}</td>
                  <td className="px-4 py-4 text-ink-600">{l.category}</td>
                  <td className="px-4 py-4 font-medium">{formatCurrency(l.currentBid)}</td>
                  <td className="px-4 py-4">
                    <span className={`badge ${REPORT_STYLE(l.reportCount ?? 0)}`}>
                      {l.reportCount ?? 0} reports
                    </span>
                  </td>
                  <td className="px-4 py-4">
                    {l.featured ? (
                      <span className="badge bg-purple-50 text-purple-700 ring-purple-200">Featured</span>
                    ) : (
                      <span className="text-xs text-ink-400">—</span>
                    )}
                  </td>
                  <td className="px-4 py-4">
                    <span className={`badge ${AUCTION_STATUS_STYLE[l.auctionStatus] || 'bg-ink-100 text-ink-600 ring-ink-200'}`}>
                      {l.auctionStatus ?? '—'}
                    </span>
                  </td>
                  <td className="px-4 py-4">
                    <span className={`badge ${MODERATION_STYLE[state] || 'bg-ink-100 text-ink-600 ring-ink-200'}`}>
                      {state}
                    </span>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {listings.length === 0 && (
          <div className="text-center py-10 text-ink-400">No listings found.</div>
        )}
      </div>

      {selected && (
        <Modal
          title={selected.title}
          subtitle={`Auction #${selected.auctionId}`}
          size="lg"
          onClose={() => setSelected(null)}
        >
          <div className="p-6">
            <dl className="grid grid-cols-2 gap-3 text-sm mb-5">
              <div><dt className="text-ink-400">Seller</dt><dd className="font-medium">{selected.sellerUsername}</dd></div>
              <div><dt className="text-ink-400">Category</dt><dd>{selected.category}</dd></div>
              <div><dt className="text-ink-400">Current bid</dt><dd>{formatCurrency(selected.currentBid)}</dd></div>
              <div><dt className="text-ink-400">Reports</dt><dd>{selected.reportCount ?? 0}</dd></div>
              <div><dt className="text-ink-400">Featured</dt><dd>{selected.featured ? 'Yes (home promo)' : 'No'}</dd></div>
              <div><dt className="text-ink-400">Status</dt>
                <dd><span className={`badge ${MODERATION_STYLE[normState(selected.moderationState)] || 'bg-ink-100 text-ink-600 ring-ink-200'}`}>
                  {normState(selected.moderationState)}
                </span></dd>
              </div>
              <div><dt className="text-ink-400">Listed</dt><dd>{selected.listedDate}</dd></div>
            </dl>
            <div className="flex flex-wrap gap-2 mb-4">
              {normState(selected.moderationState) === 'ACTIVE' && (
                <>
                  <button onClick={() => handle('flag', selected.auctionId)} className="btn bg-amber-500 text-white hover:bg-amber-600 shadow-sm">Flag</button>
                  <button onClick={() => handle('remove', selected.auctionId)} className="btn-danger">Remove</button>
                </>
              )}
              {normState(selected.moderationState) === 'FLAGGED' && (
                <button onClick={() => handle('remove', selected.auctionId)} className="btn-danger">Remove</button>
              )}
              {normState(selected.moderationState) === 'REMOVED' && (
                <button onClick={() => handle('restore', selected.auctionId)} className="btn-success">Restore</button>
              )}
              {normState(selected.moderationState) === 'ACTIVE' && !selected.featured && (
                <button
                  onClick={() => handle('feature', selected.auctionId)}
                  className="btn bg-purple-600 text-white hover:bg-purple-700 shadow-sm"
                  title="Records $9.99 platform revenue; listing appears on home Featured section"
                >
                  Feature (7 days)
                </button>
              )}
              {selected.featured && (
                <button
                  onClick={() => handle('unfeature', selected.auctionId)}
                  className="btn bg-purple-100 text-purple-800 hover:bg-purple-200"
                >
                  Remove featured
                </button>
              )}
              <Link to={`/auction/${selected.auctionId}`} className="btn-secondary">
                View auction
              </Link>
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
}
