/*
 * The signed in account's own profile at "/profile". Behind ProtectedRoute, any role.
 * This is the private view of the account: identity, rating breakdown, transaction record,
 * reviews in both directions and reports raised. The public version other people see is
 * SellerProfilePublic at /seller/:id.
 * Pulls from five endpoints on mount: profile, transaction history, reviews about me, my
 * reports, and reviews I wrote. A review can be edited or deleted for 24 hours after it is
 * posted; the server sets rev.editable and the buttons follow that flag rather than the page
 * recomputing the window itself.
 * Reviewers are shown by masked name, and reports carry the moderation team's reply if one
 * has been written.
 */
import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import {
  Mail, Phone, MapPin, Edit3, Star, ClipboardList, Flag, Calendar, Settings,
  ShoppingBag, Tag, TrendingUp, Inbox,
} from 'lucide-react';
import { getProfile, getTransactionHistory, getMyReviews } from '../api/user';
import { getMyReports, getMyWrittenReviews, updateMyReview, deleteMyReview } from '../api/auction';
import { formatCurrency, getRoleDisplay, decodeHtmlEntities } from '../utils/helpers';
import { publicPath } from '../utils/appBase';
import StarRating from '../components/StarRating';
import Modal from '../components/Modal';
import { apiErrorMessage } from '../utils/apiError';

// Backend fields:
// profile: { id, username, email, role, profileImageUrl, memberSince, phone, address, rating: RatingSummary, transactions: [...] }
// RatingSummary: { average, reviewCount, starCountsHighToLow[5] }
// ProfileTransactionRow: { displayId, transactionDate, itemTitle, transactionType, amount, status }

// Orders live on /purchases and /sales, and payment methods in account settings,
// so this page is purely the account's own record.
const PROFILE_TABS = [
  { key: 'transactions', label: 'Transactions', icon: ClipboardList },
  { key: 'reviews',      label: 'Reviews',      icon: Star },
  { key: 'reports',      label: 'Reports',      icon: Flag },
];

function transactionBadgeClass(status) {
  if (status === 'Completed') return 'badge-success';
  if (status === 'Cancelled') return 'badge-danger';
  return 'badge-warning';
}

function EmptyState({ icon: Icon, title, hint }) {
  return (
    <div className="text-center py-12">
      <span className="grid place-items-center w-12 h-12 rounded-2xl bg-ink-100 text-ink-400 mx-auto mb-3">
        <Icon size={20} />
      </span>
      <p className="font-semibold text-sm text-ink-700">{title}</p>
      {hint && <p className="text-sm text-ink-400 mt-1">{hint}</p>}
    </div>
  );
}

function StatTile({ icon: Icon, label, value, tone }) {
  return (
    <div className="card card-hover p-4 flex items-center gap-3">
      <span className={`grid place-items-center w-10 h-10 rounded-xl shrink-0 ${tone}`}>
        <Icon size={18} />
      </span>
      <div className="min-w-0">
        <p className="text-lg font-bold text-ink-900 tabular-nums leading-tight">{value}</p>
        <p className="text-xs text-ink-400">{label}</p>
      </div>
    </div>
  );
}

export default function UserProfile() {
  const [profile, setProfile] = useState(null);
  const [transactions, setTransactions] = useState([]);
  // Reviews other people left about this account.
  const [reviews, setReviews] = useState([]);
  const [myReports, setMyReports] = useState([]);
  // Reviews this account wrote about other people. Only these can be edited.
  const [writtenReviews, setWrittenReviews] = useState([]);
  // The review the edit dialog is open for, or null.
  const [editingReview, setEditingReview] = useState(null);
  const [editScore, setEditScore] = useState(0);
  const [editComment, setEditComment] = useState('');
  const [reviewMsg, setReviewMsg] = useState('');
  const [tab, setTab] = useState('transactions');
  const [filter, setFilter] = useState('All');

  // Extracted because it is re-run after an edit or a delete, so the editable flag and the
  // stored text come back from the server rather than being guessed locally.
  const loadWrittenReviews = () =>
    getMyWrittenReviews().then(r => setWrittenReviews(Array.isArray(r.data) ? r.data : [])).catch(() => {});

  // Five independent requests on mount. Each section renders its own empty state if its call
  // fails, which is why none of them set a page level error.
  useEffect(() => {
    getProfile().then(r => setProfile(r.data)).catch(() => {});
    getTransactionHistory().then(r => setTransactions(r.data ?? [])).catch(() => {});
    getMyReviews().then(r => setReviews(r.data ?? [])).catch(() => {});
    getMyReports().then(r => setMyReports(Array.isArray(r.data) ? r.data : [])).catch(() => {});
    loadWrittenReviews();
  }, []);

  // Loads one review into the edit dialog. The stored comment is HTML escaped on the way in,
  // so it is decoded before it goes back into the textarea, otherwise editing would slowly
  // turn an apostrophe into &#39; and then into the escaped form of that.
  const openEditReview = (rev) => {
    setEditingReview(rev);
    setEditScore(rev.rating ?? 0);
    setEditComment(rev.comment ? decodeHtmlEntities(rev.comment) : '');
    setReviewMsg('');
  };

  const handleSaveReview = async () => {
    if (!editScore) { setReviewMsg('Select a star rating.'); return; }
    try {
      await updateMyReview(editingReview.id, editScore, editComment.trim());
      setEditingReview(null);
      setReviewMsg('Review updated.');
      loadWrittenReviews();
    } catch (err) {
      setReviewMsg(apiErrorMessage(err, 'Could not update the review.'));
    }
  };

  // Deletes a review the account wrote. The server enforces the same 24 hour window, so a
  // stale editable flag on screen still cannot delete an old review.
  const handleDeleteReview = async (id) => {
    if (!window.confirm('Delete this review? This cannot be undone.')) return;
    setReviewMsg('');
    try {
      await deleteMyReview(id);
      setReviewMsg('Review deleted.');
      loadWrittenReviews();
    } catch (err) {
      setReviewMsg(apiErrorMessage(err, 'Could not delete the review.'));
    }
  };

  if (!profile) {
    return (
      <div className="max-w-5xl mx-auto px-4 py-8">
        <div className="skeleton h-9 w-52 mb-6" />
        <div className="grid md:grid-cols-3 gap-6">
          <div className="space-y-4">
            <div className="skeleton h-72 rounded-2xl" />
            <div className="skeleton h-56 rounded-2xl" />
          </div>
          <div className="md:col-span-2 skeleton h-96 rounded-2xl" />
        </div>
      </div>
    );
  }

  const roleDisplay = getRoleDisplay(profile.role);

  const rating = profile.rating ?? {};
  const avgRating = rating.average ?? 0;
  const reviewCount = rating.reviewCount ?? 0;
  // starCountsHighToLow: index 0 = 5-star, index 4 = 1-star
  const starCounts = rating.starCountsHighToLow ?? [0, 0, 0, 0, 0];

  const filtered = filter === 'All'
    ? transactions
    : transactions.filter(t => t.transactionType === filter.toLowerCase());

  // Summary tiles are worked out from the transaction rows already loaded, so there is no
  // extra stats call and the numbers cannot disagree with the table below them.
  const totalPurchases = transactions.filter(t => t.transactionType === 'purchase').length;
  const totalSales = transactions.filter(t => t.transactionType === 'sale').length;
  const totalVolume = transactions.reduce((s, t) => s + (Number(t.amount) || 0), 0);

  const tabCounts = {
    transactions: transactions.length,
    reviews: reviews.length + writtenReviews.length,
    reports: myReports.length,
  };

  return (
    <div className="max-w-5xl mx-auto px-4 py-8">
      <div className="flex flex-wrap items-center justify-between gap-3 mb-6">
        <div>
          <h1 className="page-title">My profile</h1>
          <p className="page-subtitle">Your account record, reviews and reports.</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Link to="/purchases" className="btn-secondary">
            <ShoppingBag size={14} /> My purchases
          </Link>
          <Link to="/sales" className="btn-secondary">
            <Tag size={14} /> My sales
          </Link>
          <Link to="/profile/settings" className="btn-secondary">
            <Settings size={14} /> Settings
          </Link>
        </div>
      </div>

      {/* At-a-glance numbers, so they are not buried inside a tab. */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 mb-6">
        <StatTile icon={ShoppingBag} label="Purchases" value={totalPurchases} tone="bg-primary-50 text-primary-600" />
        <StatTile icon={Tag} label="Sales" value={totalSales} tone="bg-emerald-50 text-emerald-600" />
        <StatTile icon={TrendingUp} label="Total volume" value={formatCurrency(totalVolume)} tone="bg-purple-50 text-purple-600" />
        <StatTile
          icon={Star}
          label={reviewCount === 1 ? '1 review' : `${reviewCount} reviews`}
          value={reviewCount > 0 ? avgRating.toFixed(1) : '—'}
          tone="bg-amber-50 text-amber-600"
        />
      </div>

      <div className="grid md:grid-cols-3 gap-6 items-start">
        {/* Left: identity and rating */}
        <div className="space-y-4 md:sticky md:top-6">
          <div className="card card-pad text-center">
            {profile.profileImageUrl ? (
              <img
                src={publicPath(profile.profileImageUrl)}
                alt=""
                className="w-20 h-20 rounded-2xl object-cover border border-ink-200 mx-auto mb-3"
              />
            ) : (
              <div className="w-20 h-20 rounded-2xl bg-gradient-to-br from-purple-400 to-primary-600 flex items-center justify-center text-white text-2xl font-bold mx-auto mb-3 shadow-sm">
                {profile.username?.[0] ?? 'U'}
              </div>
            )}
            <h2 className="font-display font-bold text-lg text-ink-900">{profile.username}</h2>
            <div className="mt-2 mb-2 flex flex-wrap justify-center gap-1.5">
              <span className={roleDisplay.className}>{roleDisplay.label}</span>
              {/* canSell is the capability, not a role. It is badged separately so it is
                  clear that one account holds both sides. */}
              {profile.canSell && <span className="badge-purple">Selling enabled</span>}
            </div>
            <p className="text-sm text-ink-400 mb-5">
              Member since {profile.memberSince ? new Date(profile.memberSince).toLocaleDateString('en-SG', { month: 'long', year: 'numeric' }) : '—'}
            </p>
            <div className="space-y-2.5 text-sm text-ink-600 text-left">
              <div className="flex items-center gap-2.5 min-w-0"><Mail size={15} className="text-ink-400 shrink-0" /><span className="truncate">{profile.email}</span></div>
              {profile.phone && <div className="flex items-center gap-2.5"><Phone size={15} className="text-ink-400 shrink-0" />{profile.phone}</div>}
              {profile.address && <div className="flex items-center gap-2.5 min-w-0"><MapPin size={15} className="text-ink-400 shrink-0" /><span className="truncate">{profile.address}</span></div>}
            </div>
            <Link to="/profile/settings" className="btn-secondary btn-block mt-5">
              <Edit3 size={14} /> Edit profile
            </Link>
          </div>

          <div className="card card-pad">
            <h3 className="section-title text-base mb-4">Rating summary</h3>
            {reviewCount === 0 ? (
              <p className="text-sm text-ink-400">
                No ratings yet. Buyers and sellers can rate each other once an order is complete.
              </p>
            ) : (
              <>
                <div className="flex items-center gap-3 mb-4">
                  <span className="font-display text-4xl font-extrabold text-ink-900 tabular-nums">{avgRating.toFixed(1)}</span>
                  <div>
                    <StarRating value={Math.round(avgRating)} size={16} />
                    <p className="text-xs text-ink-400 mt-1">{reviewCount} {reviewCount === 1 ? 'review' : 'reviews'}</p>
                  </div>
                </div>
                {[5, 4, 3, 2, 1].map((star, idx) => (
                  <div key={star} className="flex items-center gap-2.5 mb-1.5">
                    <span className="text-xs font-medium text-ink-500 w-3 text-right">{star}</span>
                    <Star size={11} className="text-amber-400 fill-amber-400 shrink-0" />
                    <div className="flex-1 h-2 bg-ink-100 rounded-full overflow-hidden">
                      <div
                        className="h-full bg-amber-400 rounded-full transition-all duration-500"
                        style={{ width: `${reviewCount > 0 ? (starCounts[idx] / Math.max(reviewCount, 1)) * 100 : 0}%` }}
                      />
                    </div>
                    <span className="text-xs text-ink-400 w-5 text-right tabular-nums">{starCounts[idx] ?? 0}</span>
                  </div>
                ))}
              </>
            )}
          </div>
        </div>

        {/* Right: activity */}
        <div className="md:col-span-2">
          <div className="card overflow-hidden">
            <div className="flex border-b border-ink-100 overflow-x-auto">
              {PROFILE_TABS.map(({ key, label, icon: TabIcon }) => (
                <button
                  key={key}
                  onClick={() => setTab(key)}
                  aria-current={tab === key ? 'page' : undefined}
                  className={`flex-1 min-w-fit whitespace-nowrap px-4 py-3.5 text-sm font-semibold transition-colors border-b-2 inline-flex items-center justify-center gap-1.5 ${
                    tab === key
                      ? 'text-primary-600 border-primary-600 bg-primary-50/60'
                      : 'text-ink-500 border-transparent hover:text-ink-800 hover:bg-ink-50'
                  }`}
                >
                  <TabIcon size={15} /> {label}
                  {tabCounts[key] > 0 && (
                    <span className={`text-[11px] font-bold tabular-nums ${tab === key ? 'text-primary-500' : 'text-ink-400'}`}>
                      {tabCounts[key]}
                    </span>
                  )}
                </button>
              ))}
            </div>

            {tab === 'transactions' && (
              <div className="p-5">
                <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
                  <div>
                    <h3 className="font-bold text-ink-900">Recent transactions</h3>
                    <p className="text-xs text-ink-400 mt-0.5">Everything you have bought and sold.</p>
                  </div>
                  <label className="text-xs font-semibold text-ink-500">
                    <span className="sr-only">Filter transactions</span>
                    <select
                      value={filter}
                      onChange={e => setFilter(e.target.value)}
                      className="select-field py-1.5 text-sm"
                    >
                      {['All', 'Purchase', 'Sale'].map(f => <option key={f}>{f}</option>)}
                    </select>
                  </label>
                </div>

                {filtered.length === 0 ? (
                  <EmptyState
                    icon={ClipboardList}
                    title={filter === 'All' ? 'No transactions yet' : `No ${filter.toLowerCase()} transactions`}
                    hint="Win an auction or sell an item and it shows up here."
                  />
                ) : (
                  <div className="overflow-x-auto">
                    <table className="table-clean">
                      <thead>
                        <tr>
                          <th>ID</th>
                          <th>Date</th>
                          <th>Item</th>
                          <th>Type</th>
                          <th className="text-right">Amount</th>
                          <th>Status</th>
                        </tr>
                      </thead>
                      <tbody>
                        {filtered.map(t => (
                          <tr key={t.displayId}>
                            <td className="text-ink-400 tabular-nums">{t.displayId}</td>
                            <td className="text-ink-500 whitespace-nowrap">
                              <span className="flex items-center gap-1.5">
                                <Calendar size={13} className="text-ink-400 shrink-0" />{t.transactionDate}
                              </span>
                            </td>
                            <td className="font-medium text-ink-800">{t.itemTitle}</td>
                            <td>
                              <span className={t.transactionType === 'purchase' ? 'badge-info' : 'badge-success'}>
                                {t.transactionType}
                              </span>
                            </td>
                            <td className="text-right font-semibold tabular-nums whitespace-nowrap">
                              {formatCurrency(t.amount)}
                            </td>
                            <td>
                              <span className={transactionBadgeClass(t.status)}>{t.status}</span>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            )}

            {tab === 'reports' && (
              <div className="p-5">
                <h3 className="font-bold text-ink-900 flex items-center gap-2">
                  <Flag size={16} className="text-ink-400" /> My reports
                </h3>
                <p className="text-xs text-ink-400 mt-0.5 mb-4">
                  Reports you submitted about listings or users, with their status and any reply from our moderation team.
                </p>
                {myReports.length === 0 ? (
                  <EmptyState icon={Flag} title="No reports submitted" hint="Report a listing or user and you can follow it up here." />
                ) : (
                  <div className="space-y-3">
                    {myReports.map(r => (
                      <div key={`${r.type}-${r.id}`} className="border border-ink-200 rounded-xl px-4 py-3.5">
                        <div className="flex items-start justify-between gap-3 mb-1">
                          <div className="min-w-0">
                            <p className="text-sm font-semibold text-ink-900">{r.reason}</p>
                            <p className="text-xs text-ink-400 mt-0.5">
                              {r.type === 'listing' ? 'Listing report' : 'User report'}
                              {r.target_name ? ` · against ${r.target_name}` : ''}
                              {r.created_at ? ` · ${new Date(r.created_at).toLocaleDateString()}` : ''}
                            </p>
                          </div>
                          <span className={`${r.resolved ? 'badge-success' : 'badge-warning'} shrink-0`}>
                            {r.resolved ? 'Resolved' : 'Under review'}
                          </span>
                        </div>
                        {r.comment && <p className="text-sm text-ink-600 mt-1.5">“{decodeHtmlEntities(r.comment)}”</p>}
                        {r.admin_reply ? (
                          <div className="mt-2.5 rounded-xl bg-primary-50 border border-primary-100 px-3.5 py-2.5">
                            <p className="text-xs font-semibold text-primary-700 mb-0.5">Reply from moderation team</p>
                            <p className="text-sm text-ink-700">{decodeHtmlEntities(r.admin_reply)}</p>
                          </div>
                        ) : (
                          <p className="text-xs text-ink-400 mt-2">No reply from the moderation team yet.</p>
                        )}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}

            {tab === 'reviews' && (
              <div className="p-5">
                {editingReview && (
                  <Modal
                    title="Edit review"
                    subtitle={`${editingReview.auctionTitle ? `on ${editingReview.auctionTitle} · ` : ''}about ${editingReview.revieweeName}`}
                    icon={Star}
                    size="md"
                    onClose={() => setEditingReview(null)}
                  >
                    <div className="p-6">
                      <div className="flex justify-center mb-3">
                        <StarRating value={editScore} onChange={setEditScore} size={30} />
                      </div>
                      <textarea
                        value={editComment}
                        onChange={e => setEditComment(e.target.value.slice(0, 300))}
                        rows={3}
                        placeholder="Update your comment (optional)…"
                        className="textarea-field mb-1"
                      />
                      <p className="text-xs text-ink-400 text-right mb-3">{editComment.length} / 300</p>
                      {reviewMsg && <div className="text-xs text-red-500 mb-2">{reviewMsg}</div>}
                      <div className="flex gap-3">
                        <button onClick={handleSaveReview} className="btn-primary flex-1">Save</button>
                        <button onClick={() => setEditingReview(null)} className="btn-secondary flex-1">Cancel</button>
                      </div>
                    </div>
                  </Modal>
                )}

                <h3 className="font-bold text-ink-900">Reviews I wrote</h3>
                <p className="text-xs text-ink-400 mt-0.5 mb-3">
                  You can edit or delete a review within 24 hours of posting it.
                </p>
                {reviewMsg && !editingReview && <div className="alert-info mb-3"><span>{reviewMsg}</span></div>}
                {writtenReviews.length === 0 ? (
                  <EmptyState icon={Inbox} title="You haven’t written any reviews yet" hint="Rate a seller or buyer after an order completes." />
                ) : (
                  <div className="space-y-2.5 mb-6">
                    {writtenReviews.map(rev => (
                      <div key={rev.id} className="border border-ink-200 rounded-xl px-4 py-3.5">
                        <div className="flex items-start justify-between gap-3">
                          <div className="min-w-0">
                            <div className="flex flex-wrap items-center gap-2">
                              <StarRating value={rev.rating ?? 0} size={14} />
                              <span className="text-xs text-ink-400">
                                about {rev.revieweeName}
                                {rev.auctionTitle ? ` · on ${rev.auctionTitle}` : ''}
                              </span>
                            </div>
                            {rev.comment && (
                              <p className="text-sm text-ink-600 mt-1.5">{decodeHtmlEntities(rev.comment)}</p>
                            )}
                            <p className="text-xs text-ink-400 mt-1">
                              {rev.createdAt ? new Date(rev.createdAt).toLocaleDateString() : ''}
                            </p>
                          </div>
                          <div className="flex gap-2 shrink-0">
                            {rev.editable ? (
                              <>
                                <button onClick={() => openEditReview(rev)} className="link-subtle text-xs">
                                  Edit
                                </button>
                                <button onClick={() => handleDeleteReview(rev.id)} className="text-xs font-medium text-red-500 hover:underline">
                                  Delete
                                </button>
                              </>
                            ) : (
                              <span className="text-xs text-ink-300">Edit window closed</span>
                            )}
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>
                )}

                <h3 className="font-bold text-ink-900 pt-5 divider">Reviews about me</h3>
                {reviews.length === 0 ? (
                  <EmptyState icon={Star} title="No reviews about you yet" hint="They arrive once the people you trade with rate you." />
                ) : (
                  <div className="divide-y divide-ink-100">
                    {reviews.map((rev, i) => (
                      <div key={i} className="py-4 flex gap-3">
                        <div className="w-10 h-10 rounded-full bg-ink-200 flex items-center justify-center shrink-0 text-ink-600 font-semibold text-sm">
                          {(rev.reviewerMaskedName ?? 'U').charAt(0).toUpperCase()}
                        </div>
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center justify-between gap-3 mb-1">
                            <span className="font-semibold text-sm text-ink-900 truncate">{rev.reviewerMaskedName ?? 'User'}</span>
                            <span className="text-xs text-ink-400 shrink-0">
                              {rev.reviewDate ? new Date(rev.reviewDate).toLocaleDateString('en-US', { day: 'numeric', month: 'short', year: 'numeric' }) : ''}
                            </span>
                          </div>
                          <StarRating value={rev.rating ?? 0} size={14} />
                          {rev.auctionTitle && <p className="text-xs text-ink-400 mt-0.5">on {rev.auctionTitle}</p>}
                          {rev.comment && <p className="text-sm text-ink-600 mt-1.5">{decodeHtmlEntities(rev.comment)}</p>}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
