import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import {
  Mail, Phone, MapPin, Edit3, CreditCard, Trash2, Plus, Package, Star, Truck,
  MessageCircle, RotateCcw, ClipboardList, Flag, Calendar, Check, Settings,
  Wallet, Landmark, ShoppingBag, Tag, TrendingUp, Inbox,
} from 'lucide-react';
import {
  getProfile, getTransactionHistory, getMyReviews,
  getPaymentMethods, addPaymentMethod, deletePaymentMethod, setDefaultPaymentMethod,
} from '../api/user';
import { getOrders, payOrder, completeOrder, advanceOrderShipping, resolveOrderRefund } from '../api/orders';
import { getMyReports, getMyWrittenReviews, updateMyReview, deleteMyReview } from '../api/auction';
import { formatCurrency, getRoleDisplay, decodeHtmlEntities } from '../utils/helpers';
import { publicPath } from '../utils/appBase';
import StarRating from '../components/StarRating';
import OrderTrackingModal from '../components/OrderTrackingModal';
import RateBuyerModal from '../components/RateBuyerModal';
import OrderMessageModal from '../components/OrderMessageModal';
import OrderRefundModal from '../components/OrderRefundModal';
import Modal from '../components/Modal';

// Backend fields:
// profile: { id, username, email, role, profileImageUrl, memberSince, phone, address, rating: RatingSummary, transactions: [...] }
// RatingSummary: { average, reviewCount, starCountsHighToLow[5] }
// ProfileTransactionRow: { displayId, transactionDate, itemTitle, transactionType, amount, status }

const PROFILE_TABS = [
  { key: 'transactions', label: 'Transactions', icon: ClipboardList },
  { key: 'orders',       label: 'Orders',       icon: Package },
  { key: 'reviews',      label: 'Reviews',      icon: Star },
  { key: 'payment',      label: 'Payment',      icon: CreditCard },
  { key: 'reports',      label: 'Reports',      icon: Flag },
];

const METHOD_ICONS = { CARD: CreditCard, PAYPAL: Wallet, BANK_TRANSFER: Landmark };

/** Same wording the backend uses, mapped onto the shared badge styles. */
function orderBadgeClass(status) {
  if (status === 'COMPLETED') return 'badge-success';
  if (status === 'PAID') return 'badge-info';
  if (status === 'CANCELLED') return 'badge-danger';
  return 'badge-warning';
}

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
  const [reviews, setReviews] = useState([]);
  const [cards, setCards] = useState([]);
  const [orders, setOrders] = useState([]);
  const [myReports, setMyReports] = useState([]);
  const [writtenReviews, setWrittenReviews] = useState([]);
  const [editingReview, setEditingReview] = useState(null);
  const [editScore, setEditScore] = useState(0);
  const [editComment, setEditComment] = useState('');
  const [reviewMsg, setReviewMsg] = useState('');
  const [tab, setTab] = useState('transactions');
  const [filter, setFilter] = useState('All');
  const [methodType, setMethodType] = useState('card'); // card | paypal | bank
  const [cardForm, setCardForm] = useState({ cardHolder: '', cardNumber: '', expMonth: '', expYear: '', makeDefault: false });
  const [paypalForm, setPaypalForm] = useState({ paypalEmail: '', makeDefault: false });
  const [bankForm, setBankForm] = useState({ accountHolder: '', accountNumber: '', bankName: '', makeDefault: false });
  const [cardMsg, setCardMsg] = useState('');
  const [cardErr, setCardErr] = useState('');
  const [payingOrder, setPayingOrder] = useState(null);   // order awaiting payment-method choice
  const [payMethodId, setPayMethodId] = useState(null);
  const [orderMsg, setOrderMsg] = useState('');
  const [trackOrder, setTrackOrder] = useState(null);
  const [rateOrder, setRateOrder] = useState(null);
  const [contactOrder, setContactOrder] = useState(null);
  const [refundOrder, setRefundOrder] = useState(null);

  const shippingActionLabel = (status) => {
    const s = (status || 'PREPARING').toUpperCase();
    if (s === 'PREPARING') return 'Mark shipped';
    if (s === 'SHIPPED') return 'Mark in transit';
    if (s === 'IN_TRANSIT') return 'Mark delivered';
    return null;
  };

  /** True when this order is waiting on something the signed-in user must do. */
  const needsMyAction = (o) => {
    const shipping = (o.shippingStatus || '').toUpperCase();
    if (o.role === 'buyer' && o.status === 'PENDING_PAYMENT') return true;
    if (o.role === 'buyer' && o.status === 'PAID' && shipping === 'DELIVERED'
        && o.refundStatus !== 'REQUESTED' && o.refundStatus !== 'APPROVED') return true;
    if (o.role === 'seller' && o.status === 'PAID' && shippingActionLabel(o.shippingStatus)) return true;
    if (o.role === 'seller' && o.refundStatus === 'REQUESTED') return true;
    if (o.status === 'COMPLETED' && !o.hasRated) return true;
    return false;
  };

  const loadCards = () => getPaymentMethods().then(r => setCards(r.data ?? [])).catch(() => {});
  const loadOrders = () => getOrders().then(r => setOrders(r.data ?? [])).catch(() => {});
  const loadWrittenReviews = () =>
    getMyWrittenReviews().then(r => setWrittenReviews(Array.isArray(r.data) ? r.data : [])).catch(() => {});

  useEffect(() => {
    getProfile().then(r => setProfile(r.data)).catch(() => {});
    getTransactionHistory().then(r => setTransactions(r.data ?? [])).catch(() => {});
    getMyReviews().then(r => setReviews(r.data ?? [])).catch(() => {});
    getMyReports().then(r => setMyReports(Array.isArray(r.data) ? r.data : [])).catch(() => {});
    loadWrittenReviews();
    loadCards();
    loadOrders();
  }, []);

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
      setReviewMsg(err.response?.data?.error || 'Could not update the review.');
    }
  };

  const handleDeleteReview = async (id) => {
    if (!window.confirm('Delete this review? This cannot be undone.')) return;
    setReviewMsg('');
    try {
      await deleteMyReview(id);
      setReviewMsg('Review deleted.');
      loadWrittenReviews();
    } catch (err) {
      setReviewMsg(err.response?.data?.error || 'Could not delete the review.');
    }
  };

  const openPayChooser = (order) => {
    setOrderMsg('');
    if (cards.length === 0) {
      setOrderMsg('Add a payment method in the Payment tab first.');
      return;
    }
    const defaultCard = cards.find(c => c.default) ?? cards[0];
    setPayMethodId(defaultCard?.id ?? null);
    setPayingOrder(order);
  };

  const handlePayOrder = async () => {
    if (!payingOrder) return;
    setOrderMsg('');
    try {
      await payOrder(payingOrder.id, payMethodId);
      setOrderMsg('Payment successful.');
      setPayingOrder(null);
      loadOrders();
    } catch (err) {
      setOrderMsg(err.response?.data?.error || 'Payment failed. Add a payment method first.');
      setPayingOrder(null);
    }
  };

  const handleConfirmReceipt = async (orderId) => {
    if (!window.confirm('Confirm you received the item in good condition? This cannot be undone.')) return;
    setOrderMsg('');
    try {
      await completeOrder(orderId);
      setOrderMsg('Receipt confirmed. You can now rate the seller.');
      loadOrders();
    } catch (err) { setOrderMsg(err.response?.data?.error || 'Could not confirm receipt.'); }
  };

  const handleAdvanceShipping = async (orderId) => {
    setOrderMsg('');
    try { await advanceOrderShipping(orderId); loadOrders(); }
    catch (err) { setOrderMsg(err.response?.data?.error || 'Could not update shipping.'); }
  };

  const handleResolveRefund = async (orderId, approve) => {
    const verb = approve ? 'approve' : 'decline';
    if (!window.confirm(`Are you sure you want to ${verb} this refund request?`)) return;
    setOrderMsg('');
    try {
      await resolveOrderRefund(orderId, approve);
      setOrderMsg(approve ? 'Refund approved and order cancelled.' : 'Refund request declined.');
      loadOrders();
    } catch (err) { setOrderMsg(err.response?.data?.error || 'Could not update the refund request.'); }
  };

  const handleAddMethod = async (e) => {
    e.preventDefault();
    setCardErr(''); setCardMsg('');
    try {
      if (methodType === 'paypal') {
        await addPaymentMethod({ type: 'paypal', ...paypalForm });
        setPaypalForm({ paypalEmail: '', makeDefault: false });
      } else if (methodType === 'bank') {
        await addPaymentMethod({ type: 'bank_transfer', ...bankForm });
        setBankForm({ accountHolder: '', accountNumber: '', bankName: '', makeDefault: false });
      } else {
        await addPaymentMethod(cardForm);
        setCardForm({ cardHolder: '', cardNumber: '', expMonth: '', expYear: '', makeDefault: false });
      }
      setCardMsg('Payment method added.');
      loadCards();
    } catch (err) {
      setCardErr(err.response?.data?.error || 'Could not add payment method.');
    }
  };

  const handleDeleteCard = async (id) => {
    try { await deletePaymentMethod(id); loadCards(); } catch { /* ignore */ }
  };

  const handleDefaultCard = async (id) => {
    try { await setDefaultPaymentMethod(id); loadCards(); } catch { /* ignore */ }
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

  const totalPurchases = transactions.filter(t => t.transactionType === 'purchase').length;
  const totalSales = transactions.filter(t => t.transactionType === 'sale').length;
  const totalVolume = transactions.reduce((s, t) => s + (Number(t.amount) || 0), 0);
  const actionCount = orders.filter(needsMyAction).length;

  const tabCounts = {
    transactions: transactions.length,
    orders: orders.length,
    reviews: reviews.length + writtenReviews.length,
    payment: cards.length,
    reports: myReports.length,
  };

  return (
    <div className="max-w-5xl mx-auto px-4 py-8">
      <div className="flex flex-wrap items-center justify-between gap-3 mb-6">
        <div>
          <h1 className="page-title">My profile</h1>
          <p className="page-subtitle">Your account, orders, reviews and payment methods.</p>
        </div>
        <Link to="/profile/settings" className="btn-secondary">
          <Settings size={14} /> Settings
        </Link>
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
            <Link to="/profile/edit" className="btn-secondary btn-block mt-5">
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
                  {key === 'orders' && actionCount > 0 && (
                    <span
                      className="w-1.5 h-1.5 rounded-full bg-accent-500"
                      title={`${actionCount} order${actionCount === 1 ? '' : 's'} need your attention`}
                    />
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

            {tab === 'orders' && (
              <div className="p-5">
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
                                <p className="text-xs text-ink-400">{c.cardHolder} · Exp {String(c.expMonth).padStart(2, '0')}/{c.expYear}</p>
                              )}
                            </div>
                          </label>
                        ))}
                      </div>
                      <div className="flex gap-3">
                        <button
                          onClick={handlePayOrder}
                          disabled={!payMethodId}
                          className="btn-primary flex-1"
                        >
                          Pay {formatCurrency(payingOrder.amount)}
                        </button>
                        <button
                          onClick={() => setPayingOrder(null)}
                          className="btn-secondary flex-1"
                        >
                          Cancel
                        </button>
                      </div>
                    </div>
                  </Modal>
                )}
                {trackOrder && <OrderTrackingModal order={trackOrder} onClose={() => setTrackOrder(null)} />}
                {rateOrder && (
                  <RateBuyerModal
                    order={rateOrder}
                    onClose={() => setRateOrder(null)}
                    onRated={() => { setOrderMsg('Rating submitted.'); loadOrders(); }}
                  />
                )}
                {contactOrder && (
                  <OrderMessageModal
                    order={contactOrder}
                    onClose={() => setContactOrder(null)}
                  />
                )}
                {refundOrder && (
                  <OrderRefundModal
                    order={refundOrder}
                    onClose={() => setRefundOrder(null)}
                    onSubmitted={() => { setOrderMsg('Refund request submitted.'); loadOrders(); }}
                  />
                )}

                <div className="flex flex-wrap items-start justify-between gap-3 mb-4">
                  <div>
                    <h3 className="font-bold text-ink-900 flex items-center gap-2">
                      <Package size={16} className="text-ink-400" /> Orders
                    </h3>
                    <p className="text-xs text-ink-400 mt-0.5 max-w-xl">
                      Buyers pay, sellers ship, buyers confirm receipt, then both can rate. Message the
                      other party or request a refund (the seller decides) at any point.
                    </p>
                  </div>
                  {actionCount > 0 && (
                    <span className="badge-warning">
                      {actionCount} need{actionCount === 1 ? 's' : ''} your action
                    </span>
                  )}
                </div>

                {orderMsg && <div className="alert-info mb-3"><span>{orderMsg}</span></div>}

                {orders.length === 0 ? (
                  <EmptyState icon={Package} title="No orders yet" hint="Orders appear here once you win an auction or sell an item." />
                ) : (
                  <div className="space-y-2.5">
                    {orders.map(o => (
                      <div
                        key={o.id}
                        className={`rounded-xl border px-4 py-3.5 transition-colors ${
                          needsMyAction(o) ? 'border-accent-200 bg-accent-50/40' : 'border-ink-200'
                        }`}
                      >
                        <div className="flex items-start justify-between gap-3">
                          <div className="min-w-0">
                            <p className="text-sm font-semibold text-ink-900">{o.auctionTitle}</p>
                            <p className="text-xs text-ink-400 mt-0.5">
                              {o.role === 'buyer' ? 'Bought from' : 'Sold to'} {o.counterparty} · {formatCurrency(o.amount)}
                            </p>
                            {o.shippingStatus && o.status === 'PAID' && (
                              <p className="text-xs text-primary-600 mt-1 flex items-center gap-1">
                                <Truck size={12} /> {o.shippingStatus.replace('_', ' ').toLowerCase()}
                              </p>
                            )}
                            {o.refundStatus === 'REQUESTED' && (
                              <p className="text-xs text-accent-600 mt-1 flex items-center gap-1">
                                <RotateCcw size={12} />
                                {o.role === 'seller' ? 'Refund requested by buyer' : 'Refund requested — awaiting seller'}
                              </p>
                            )}
                            {o.refundStatus === 'REQUESTED' && o.role === 'seller' && o.refundReason && (
                              <p className="text-xs text-ink-500 mt-1 italic">“{o.refundReason}”</p>
                            )}
                            {o.refundStatus === 'APPROVED' && (
                              <p className="text-xs text-emerald-600 mt-1 flex items-center gap-1">
                                <RotateCcw size={12} /> Refund approved · order cancelled
                              </p>
                            )}
                            {o.refundStatus === 'REJECTED' && (
                              <p className="text-xs text-ink-500 mt-1 flex items-center gap-1">
                                <RotateCcw size={12} /> Refund declined by seller
                              </p>
                            )}
                            {o.role === 'seller' && o.status === 'PAID' && (o.shippingStatus || '').toUpperCase() === 'DELIVERED' && (
                              <p className="text-xs text-amber-600 mt-1">Waiting for buyer to confirm receipt</p>
                            )}
                          </div>
                          <span className={`${orderBadgeClass(o.status)} shrink-0`}>
                            {o.status.replace('_', ' ')}
                          </span>
                        </div>

                        <div className="flex flex-wrap gap-2 mt-3">
                          {o.role === 'buyer' && o.status !== 'PENDING_PAYMENT' && (
                            <button onClick={() => setTrackOrder(o)} className="btn-secondary btn-sm">
                              <Truck size={12} /> Track order
                            </button>
                          )}
                          {o.role === 'buyer' && o.status === 'PENDING_PAYMENT' && (
                            <button onClick={() => openPayChooser(o)} className="btn-primary btn-sm">
                              Pay now
                            </button>
                          )}
                          {o.role === 'seller' && o.status === 'PAID' && shippingActionLabel(o.shippingStatus) && (
                            <button onClick={() => handleAdvanceShipping(o.id)} className="btn-primary btn-sm">
                              <Truck size={12} /> {shippingActionLabel(o.shippingStatus)}
                            </button>
                          )}
                          {o.role === 'buyer' && o.status === 'PAID' && (o.shippingStatus || '').toUpperCase() === 'DELIVERED' && o.refundStatus !== 'REQUESTED' && o.refundStatus !== 'APPROVED' && (
                            <button onClick={() => handleConfirmReceipt(o.id)} className="btn-success btn-sm">
                              <Check size={12} /> Confirm receipt
                            </button>
                          )}
                          {o.status !== 'PENDING_PAYMENT' && o.status !== 'CANCELLED' && (
                            <button onClick={() => setContactOrder(o)} className="btn-secondary btn-sm">
                              <MessageCircle size={12} /> {o.role === 'buyer' ? 'Contact seller' : 'Message buyer'}
                            </button>
                          )}
                          {o.role === 'buyer' && o.status === 'PAID' && !o.refundStatus && (
                            <button
                              onClick={() => setRefundOrder(o)}
                              className="btn-secondary btn-sm text-accent-700 border-accent-200 hover:bg-accent-50 hover:border-accent-300"
                            >
                              <RotateCcw size={12} /> Request refund
                            </button>
                          )}
                          {o.role === 'seller' && o.refundStatus === 'REQUESTED' && (
                            <>
                              <button onClick={() => handleResolveRefund(o.id, true)} className="btn-success btn-sm">
                                Approve refund
                              </button>
                              <button
                                onClick={() => handleResolveRefund(o.id, false)}
                                className="btn-secondary btn-sm text-red-600 border-red-200 hover:bg-red-50 hover:border-red-300"
                              >
                                Decline refund
                              </button>
                            </>
                          )}
                          {o.status === 'COMPLETED' && !o.hasRated && o.role === 'buyer' && (
                            <Link to={`/rate-seller/${o.auctionId}`} className="btn-primary btn-sm">
                              <Star size={12} /> Rate seller
                            </Link>
                          )}
                          {o.status === 'COMPLETED' && !o.hasRated && o.role === 'seller' && (
                            <button onClick={() => setRateOrder(o)} className="btn-primary btn-sm">
                              <Star size={12} /> Rate buyer
                            </button>
                          )}
                          {o.status === 'COMPLETED' && o.hasRated && (
                            <span className="inline-flex items-center gap-1 text-xs font-medium text-ink-400 px-1 py-1.5">
                              <Check size={13} /> Rated
                            </span>
                          )}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}

            {tab === 'payment' && (
              <div className="p-5">
                <h3 className="font-bold text-ink-900 flex items-center gap-2">
                  <CreditCard size={16} className="text-ink-400" /> Payment methods
                </h3>
                <p className="text-xs text-ink-400 mt-0.5 mb-4">
                  Card numbers are encrypted (AES-GCM) before storage. We never store your CVV.
                </p>

                <div className="space-y-2 mb-6">
                  {cards.length === 0 ? (
                    <EmptyState icon={CreditCard} title="No saved payment methods" hint="Add one below so you can pay for an order in one click." />
                  ) : cards.map(c => {
                    const MethodIcon = METHOD_ICONS[c.methodType] ?? CreditCard;
                    return (
                      <div key={c.id} className="flex items-center justify-between gap-3 border border-ink-200 rounded-xl px-4 py-3">
                        <div className="flex items-center gap-3 min-w-0">
                          <span className="grid place-items-center w-9 h-9 rounded-xl bg-ink-100 text-ink-500 shrink-0">
                            <MethodIcon size={17} />
                          </span>
                          <div className="min-w-0">
                            <p className="text-sm font-semibold text-ink-900 truncate">
                              {c.displayLabel ?? `${c.cardBrand} •••• ${c.last4}`}
                              {c.default && <span className="badge-info ml-2">Default</span>}
                            </p>
                            {c.methodType === 'CARD' && (
                              <p className="text-xs text-ink-400">{c.cardHolder} · Exp {String(c.expMonth).padStart(2, '0')}/{c.expYear}</p>
                            )}
                            {c.methodType === 'BANK_TRANSFER' && c.cardHolder && (
                              <p className="text-xs text-ink-400">{c.cardHolder}</p>
                            )}
                            {c.methodType === 'PAYPAL' && (
                              <p className="text-xs text-ink-400">PayPal account</p>
                            )}
                          </div>
                        </div>
                        <div className="flex items-center gap-3 shrink-0">
                          {!c.default && (
                            <button onClick={() => handleDefaultCard(c.id)} className="link-subtle text-xs">
                              Set default
                            </button>
                          )}
                          <button
                            onClick={() => handleDeleteCard(c.id)}
                            aria-label="Remove payment method"
                            className="text-ink-400 hover:text-red-500 transition-colors p-1"
                          >
                            <Trash2 size={16} />
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>

                <form onSubmit={handleAddMethod} className="divider pt-5 space-y-3">
                  <h4 className="text-sm font-semibold text-ink-900 flex items-center gap-1.5">
                    <Plus size={14} /> Add a payment method
                  </h4>

                  {cardMsg && <div className="alert-success"><Check size={15} className="mt-0.5 shrink-0" /><span>{cardMsg}</span></div>}
                  {cardErr && <div className="alert-error"><span>{cardErr}</span></div>}

                  <div className="flex flex-wrap gap-2">
                    {[
                      { key: 'card',   label: 'Card',          icon: CreditCard },
                      { key: 'paypal', label: 'PayPal',        icon: Wallet },
                      { key: 'bank',   label: 'Bank transfer', icon: Landmark },
                    ].map(({ key, label, icon: Icon }) => (
                      <button
                        key={key}
                        type="button"
                        onClick={() => { setMethodType(key); setCardErr(''); setCardMsg(''); }}
                        aria-pressed={methodType === key}
                        className={`tab-pill btn-sm inline-flex items-center gap-1.5 ${methodType === key ? 'tab-pill-active' : ''}`}
                      >
                        <Icon size={13} /> {label}
                      </button>
                    ))}
                  </div>

                  {methodType === 'card' && (
                    <div className="space-y-3">
                      <div>
                        <label className="field-label" htmlFor="pm-holder">Cardholder name</label>
                        <input
                          id="pm-holder"
                          type="text" required placeholder="Jane Tan"
                          value={cardForm.cardHolder}
                          onChange={e => setCardForm(f => ({ ...f, cardHolder: e.target.value }))}
                          className="input-field"
                        />
                      </div>
                      <div>
                        <label className="field-label" htmlFor="pm-number">Card number</label>
                        <input
                          id="pm-number"
                          type="text" required inputMode="numeric" placeholder="4242 4242 4242 4242"
                          value={cardForm.cardNumber}
                          onChange={e => setCardForm(f => ({ ...f, cardNumber: e.target.value }))}
                          className="input-field"
                        />
                      </div>
                      <div className="flex flex-wrap items-end gap-3">
                        <div>
                          <label className="field-label" htmlFor="pm-month">Exp. month</label>
                          <input
                            id="pm-month"
                            type="number" required min="1" max="12" placeholder="MM"
                            value={cardForm.expMonth}
                            onChange={e => setCardForm(f => ({ ...f, expMonth: e.target.value }))}
                            className="input-field w-24"
                          />
                        </div>
                        <div>
                          <label className="field-label" htmlFor="pm-year">Exp. year</label>
                          <input
                            id="pm-year"
                            type="number" required min="2024" placeholder="YYYY"
                            value={cardForm.expYear}
                            onChange={e => setCardForm(f => ({ ...f, expYear: e.target.value }))}
                            className="input-field w-28"
                          />
                        </div>
                        <label className="flex items-center gap-2 text-sm text-ink-600 pb-2.5">
                          <input
                            type="checkbox"
                            checked={cardForm.makeDefault}
                            onChange={e => setCardForm(f => ({ ...f, makeDefault: e.target.checked }))}
                            className="w-4 h-4 rounded border-ink-300 text-primary-600"
                          />
                          Make default
                        </label>
                      </div>
                    </div>
                  )}

                  {methodType === 'paypal' && (
                    <div className="space-y-3">
                      <div>
                        <label className="field-label" htmlFor="pm-paypal">PayPal email</label>
                        <input
                          id="pm-paypal"
                          type="email" required placeholder="you@example.com"
                          value={paypalForm.paypalEmail}
                          onChange={e => setPaypalForm(f => ({ ...f, paypalEmail: e.target.value }))}
                          className="input-field"
                        />
                      </div>
                      <label className="flex items-center gap-2 text-sm text-ink-600">
                        <input
                          type="checkbox"
                          checked={paypalForm.makeDefault}
                          onChange={e => setPaypalForm(f => ({ ...f, makeDefault: e.target.checked }))}
                          className="w-4 h-4 rounded border-ink-300 text-primary-600"
                        />
                        Make default
                      </label>
                    </div>
                  )}

                  {methodType === 'bank' && (
                    <div className="space-y-3">
                      <div>
                        <label className="field-label" htmlFor="pm-account-holder">Account holder name</label>
                        <input
                          id="pm-account-holder"
                          type="text" required placeholder="Jane Tan"
                          value={bankForm.accountHolder}
                          onChange={e => setBankForm(f => ({ ...f, accountHolder: e.target.value }))}
                          className="input-field"
                        />
                      </div>
                      <div className="grid sm:grid-cols-2 gap-3">
                        <div>
                          <label className="field-label" htmlFor="pm-account-number">Account number</label>
                          <input
                            id="pm-account-number"
                            type="text" required inputMode="numeric" placeholder="0123456789"
                            value={bankForm.accountNumber}
                            onChange={e => setBankForm(f => ({ ...f, accountNumber: e.target.value }))}
                            className="input-field"
                          />
                        </div>
                        <div>
                          <label className="field-label" htmlFor="pm-bank-name">Bank name</label>
                          <input
                            id="pm-bank-name"
                            type="text" required placeholder="DBS"
                            value={bankForm.bankName}
                            onChange={e => setBankForm(f => ({ ...f, bankName: e.target.value }))}
                            className="input-field"
                          />
                        </div>
                      </div>
                      <label className="flex items-center gap-2 text-sm text-ink-600">
                        <input
                          type="checkbox"
                          checked={bankForm.makeDefault}
                          onChange={e => setBankForm(f => ({ ...f, makeDefault: e.target.checked }))}
                          className="w-4 h-4 rounded border-ink-300 text-primary-600"
                        />
                        Make default
                      </label>
                    </div>
                  )}

                  <button type="submit" className="btn-primary">
                    {methodType === 'paypal' ? 'Link PayPal' : methodType === 'bank' ? 'Add bank account' : 'Add card'}
                  </button>
                </form>
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
