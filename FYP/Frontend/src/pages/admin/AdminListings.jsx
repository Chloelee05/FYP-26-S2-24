import { useState, useEffect, useMemo } from 'react';
import { Link } from 'react-router-dom';
import {
  getAdminListings, flagListing, removeListing, restoreListing,
  featureListing, unfeatureListing, editListingContent, setListingKind,
  getListingContent,
} from '../../api/admin';
import { formatCurrency } from '../../utils/helpers';
import { LISTING_KIND_STYLE, normalizeListingKind } from '../../utils/listingKind';
import { apiErrorMessage } from '../../utils/apiError';
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

const KIND_STYLE = LISTING_KIND_STYLE;

const REPORT_STYLE = (n) => n === 0 ? 'bg-emerald-50 text-emerald-700 ring-emerald-200' : n < 5 ? 'bg-amber-50 text-amber-700 ring-amber-200' : 'bg-red-50 text-red-700 ring-red-200';

const normState = (s) => (s ?? '').toUpperCase();
// Sellers now set this field themselves on create, so the reading of it is shared with the
// seller and public pages rather than defined once per surface.
const normKind = normalizeListingKind;

export default function AdminListings() {
  const [listings, setListings] = useState([]);
  const [selected, setSelected] = useState(null);
  const [msg, setMsg] = useState('');
  const [kindFilter, setKindFilter] = useState('ALL');
  const [editing, setEditing] = useState(null);
  const [editBusy, setEditBusy] = useState(false);

  const reload = () => getAdminListings().then(r => setListings(r.data ?? [])).catch(() => {});

  useEffect(() => { reload(); }, []);

  const kindCounts = useMemo(() => listings.reduce((acc, l) => {
    const k = normKind(l.listingKind);
    return { ...acc, [k]: (acc[k] ?? 0) + 1 };
  }, { PRODUCT: 0, SERVICE: 0 }), [listings]);

  const visible = kindFilter === 'ALL'
    ? listings
    : listings.filter(l => normKind(l.listingKind) === kindFilter);

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
        restore: 'restored to active',
        feature: 'featured for 7 days ($9.99 platform fee recorded)',
        unfeature: 'removed from featured',
      };
      setMsg(`Listing ${labels[action]}.`);
    } catch (err) {
      setMsg(apiErrorMessage(err, 'Action failed.'));
    }
  };

  const openEditor = async (listing) => {
    setMsg('');
    try {
      const r = await getListingContent(listing.auctionId);
      setEditing({
        auctionId: listing.auctionId,
        title: r.data?.title ?? listing.title ?? '',
        description: r.data?.description ?? '',
        category: r.data?.category ?? listing.category ?? '',
        listingKind: normKind(r.data?.listingKind ?? listing.listingKind),
        bidCount: r.data?.bidCount ?? 0,
        reason: '',
      });
    } catch (err) {
      setMsg(apiErrorMessage(err, 'Could not load the listing for editing.'));
    }
  };

  const saveEdit = async () => {
    setEditBusy(true);
    setMsg('');
    try {
      const r = await editListingContent(editing.auctionId, editing);
      setMsg(r.data?.message ?? 'Listing updated.');
      patchListing(editing.auctionId, {
        title: editing.title,
        category: editing.category,
        listingKind: editing.listingKind,
      });
      setEditing(null);
    } catch (err) {
      setMsg(apiErrorMessage(err, 'Could not update the listing.'));
    } finally {
      setEditBusy(false);
    }
  };

  const reclassify = async (listing) => {
    const next = normKind(listing.listingKind) === 'SERVICE' ? 'PRODUCT' : 'SERVICE';
    const reason = window.prompt(
      `Reclassify "${listing.title}" as a ${next.toLowerCase()}?\nGive a reason for the audit log:`);
    if (reason == null || reason.trim() === '') return;
    setMsg('');
    try {
      const r = await setListingKind(listing.auctionId, next, reason);
      setMsg(r.data?.message ?? `Listing reclassified as ${next}.`);
      patchListing(listing.auctionId, { listingKind: next });
    } catch (err) {
      setMsg(apiErrorMessage(err, 'Could not reclassify the listing.'));
    }
  };

  return (
    <div className="p-8">
      <h1 className="page-title">Listing Management</h1>
      <p className="page-subtitle mb-4">
        Click a row to review, correct content, reclassify product/service, or moderate
      </p>
      {msg && <div className="text-sm text-primary-600 mb-4">{msg}</div>}

      <div className="flex flex-wrap items-center gap-2 mb-4">
        {[
          { key: 'ALL', label: `All (${listings.length})` },
          { key: 'PRODUCT', label: `Products (${kindCounts.PRODUCT})` },
          { key: 'SERVICE', label: `Services (${kindCounts.SERVICE})` },
        ].map(f => (
          <button
            key={f.key}
            type="button"
            onClick={() => setKindFilter(f.key)}
            className={`px-3 py-1.5 rounded-lg text-sm font-medium transition-colors ${
              kindFilter === f.key
                ? 'bg-primary-600 text-white'
                : 'border border-ink-200 text-ink-600 hover:bg-ink-50'
            }`}
          >
            {f.label}
          </button>
        ))}
      </div>

      <div className="card overflow-hidden">
        <table className="w-full text-sm">
          <thead className="text-xs text-ink-500 uppercase tracking-wider bg-ink-50 border-b border-ink-200">
            <tr>
              {['Listing', 'Kind', 'Seller', 'Category', 'Current Bid', 'Reports', 'Featured', 'Lifecycle', 'Moderation'].map(h => (
                <th key={h} className="px-4 py-3 text-left font-bold whitespace-nowrap">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-ink-100">
            {visible.map(l => {
              const state = normState(l.moderationState);
              const kind = normKind(l.listingKind);
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
                  <td className="px-4 py-4">
                    <span className={`badge ${KIND_STYLE[kind]}`}>{kind.toLowerCase()}</span>
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
        {visible.length === 0 && (
          <div className="text-center py-10 text-ink-400">No listings found.</div>
        )}
      </div>

      {selected && !editing && (
        <Modal
          title={selected.title}
          subtitle={`Auction #${selected.auctionId}`}
          size="lg"
          onClose={() => setSelected(null)}
        >
          <div className="p-6">
            <dl className="grid grid-cols-2 gap-3 text-sm mb-5">
              <div><dt className="text-ink-400">Seller</dt><dd className="font-medium">{selected.sellerUsername}</dd></div>
              <div><dt className="text-ink-400">Kind</dt>
                <dd><span className={`badge ${KIND_STYLE[normKind(selected.listingKind)]}`}>
                  {normKind(selected.listingKind).toLowerCase()}
                </span></dd>
              </div>
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
              <button onClick={() => openEditor(selected)} className="btn-primary">
                Edit content
              </button>
              <button onClick={() => reclassify(selected)} className="btn-secondary">
                Mark as {normKind(selected.listingKind) === 'SERVICE' ? 'product' : 'service'}
              </button>
              {normState(selected.moderationState) === 'ACTIVE' && (
                <button onClick={() => handle('flag', selected.auctionId)} className="btn bg-amber-500 text-white hover:bg-amber-600 shadow-sm">Flag</button>
              )}
              {/* A flagged listing needs its own way back to active: the backend RESTORE
                  works from any state, but the UI used to offer it only for REMOVED, so
                  undoing an accidental flag meant removing the listing first. */}
              {normState(selected.moderationState) !== 'ACTIVE' && (
                <button onClick={() => handle('restore', selected.auctionId)} className="btn-success">
                  {normState(selected.moderationState) === 'FLAGGED' ? 'Clear flag' : 'Restore'}
                </button>
              )}
              {normState(selected.moderationState) !== 'REMOVED' && (
                <button onClick={() => handle('remove', selected.auctionId)} className="btn-danger">Remove</button>
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

      {editing && (
        <Modal
          title="Edit listing content"
          subtitle={`Auction #${editing.auctionId}`}
          size="lg"
          onClose={() => setEditing(null)}
        >
          <div className="p-6 space-y-4">
            <p className="text-xs text-ink-500 leading-relaxed bg-ink-50 rounded-lg p-3">
              Price, reserve and quantity are not editable here. A bid is an offer against a
              published price, so changing it mid-auction would rewrite the terms buyers
              already committed to — those fields stay with the seller. Every change below is
              recorded in the admin audit log with its previous value.
            </p>
            {editing.bidCount > 0 && (
              <p className="text-xs text-amber-700 bg-amber-50 rounded-lg p-3">
                This listing already has {editing.bidCount} bid(s). Editing the title or
                description changes what those bidders were shown, so keep the correction to
                what is genuinely wrong and say why below.
              </p>
            )}
            <div>
              <label className="block text-xs text-ink-500 mb-1" htmlFor="edit-title">Title</label>
              <input
                id="edit-title"
                value={editing.title}
                maxLength={255}
                onChange={e => setEditing(f => ({ ...f, title: e.target.value }))}
                className="input-field w-full"
              />
            </div>
            <div>
              <label className="block text-xs text-ink-500 mb-1" htmlFor="edit-desc">Description</label>
              <textarea
                id="edit-desc"
                rows={5}
                value={editing.description}
                onChange={e => setEditing(f => ({ ...f, description: e.target.value }))}
                className="input-field w-full"
              />
            </div>
            <div className="grid sm:grid-cols-2 gap-4">
              <div>
                <label className="block text-xs text-ink-500 mb-1" htmlFor="edit-category">Category</label>
                <input
                  id="edit-category"
                  value={editing.category}
                  onChange={e => setEditing(f => ({ ...f, category: e.target.value }))}
                  className="input-field w-full"
                />
              </div>
              <div>
                <label className="block text-xs text-ink-500 mb-1" htmlFor="edit-kind">Kind</label>
                <select
                  id="edit-kind"
                  value={editing.listingKind}
                  onChange={e => setEditing(f => ({ ...f, listingKind: e.target.value }))}
                  className="input-field w-full"
                >
                  <option value="PRODUCT">Product</option>
                  <option value="SERVICE">Service</option>
                </select>
              </div>
            </div>
            <div>
              <label className="block text-xs text-ink-500 mb-1" htmlFor="edit-reason">
                Reason (recorded in the audit log)
              </label>
              <input
                id="edit-reason"
                value={editing.reason}
                onChange={e => setEditing(f => ({ ...f, reason: e.target.value }))}
                placeholder="e.g. removed offensive wording from the title"
                className="input-field w-full"
              />
            </div>
            <div className="flex gap-2 pt-2">
              <button type="button" onClick={saveEdit} disabled={editBusy} className="btn-primary">
                {editBusy ? 'Saving…' : 'Save changes'}
              </button>
              <button type="button" onClick={() => setEditing(null)} className="btn-secondary">
                Cancel
              </button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
}
