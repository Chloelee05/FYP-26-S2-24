import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { Mail, Phone, MapPin, Edit3, CreditCard, Trash2, Plus, Package, Star, Truck, MessageCircle, RotateCcw } from 'lucide-react';
import {
  getProfile, getTransactionHistory, getMyReviews,
  getPaymentMethods, addPaymentMethod, deletePaymentMethod, setDefaultPaymentMethod,
} from '../api/user';
import { getOrders, payOrder, completeOrder, advanceOrderShipping, resolveOrderRefund } from '../api/orders';
import { formatCurrency, getRoleDisplay, decodeHtmlEntities } from '../utils/helpers';
import StarRating from '../components/StarRating';
import OrderTrackingModal from '../components/OrderTrackingModal';
import RateBuyerModal from '../components/RateBuyerModal';
import OrderMessageModal from '../components/OrderMessageModal';
import OrderRefundModal from '../components/OrderRefundModal';

// Backend fields:
// profile: { id, username, email, role, profileImageUrl, memberSince, phone, address, rating: RatingSummary, transactions: [...] }
// RatingSummary: { average, reviewCount, starCountsHighToLow[5] }
// ProfileTransactionRow: { displayId, transactionDate, itemTitle, transactionType, amount, status }

export default function UserProfile() {
  const [profile, setProfile] = useState(null);
  const [transactions, setTransactions] = useState([]);
  const [reviews, setReviews] = useState([]);
  const [cards, setCards] = useState([]);
  const [orders, setOrders] = useState([]);
  const [tab, setTab] = useState('transactions');
  const [filter, setFilter] = useState('All');
  const [cardForm, setCardForm] = useState({ cardHolder: '', cardNumber: '', expMonth: '', expYear: '', makeDefault: false });
  const [cardMsg, setCardMsg] = useState('');
  const [cardErr, setCardErr] = useState('');
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

  const loadCards = () => getPaymentMethods().then(r => setCards(r.data ?? [])).catch(() => {});
  const loadOrders = () => getOrders().then(r => setOrders(r.data ?? [])).catch(() => {});

  useEffect(() => {
    getProfile().then(r => setProfile(r.data)).catch(() => {});
    getTransactionHistory().then(r => setTransactions(r.data ?? [])).catch(() => {});
    getMyReviews().then(r => setReviews(r.data ?? [])).catch(() => {});
    loadCards();
    loadOrders();
  }, []);

  const handlePayOrder = async (orderId) => {
    setOrderMsg('');
    const defaultCard = cards.find(c => c.default) ?? cards[0];
    try {
      await payOrder(orderId, defaultCard?.id);
      setOrderMsg('Payment successful.');
      loadOrders();
    } catch (err) {
      setOrderMsg(err.response?.data?.error || 'Payment failed. Add a payment method first.');
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

  const handleAddCard = async (e) => {
    e.preventDefault();
    setCardErr(''); setCardMsg('');
    try {
      await addPaymentMethod(cardForm);
      setCardMsg('Payment method added.');
      setCardForm({ cardHolder: '', cardNumber: '', expMonth: '', expYear: '', makeDefault: false });
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
    return <div className="max-w-5xl mx-auto px-4 py-16 text-center text-gray-400">Loading profile…</div>;
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

  return (
    <div className="max-w-5xl mx-auto px-4 py-8">
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold text-gray-900">User Profile</h1>
        <div className="flex gap-2">
          <Link to="/profile/settings" className="border border-gray-200 px-4 py-2 rounded-lg text-sm hover:bg-gray-50">Settings</Link>
        </div>
      </div>

      <div className="grid md:grid-cols-3 gap-6">
        {/* Left: Profile */}
        <div className="space-y-4">
          <div className="card p-6 text-center">
            <div className="w-20 h-20 rounded-full bg-gradient-to-br from-purple-400 to-blue-500 flex items-center justify-center text-white text-2xl font-bold mx-auto mb-3">
              {profile.username?.[0] ?? 'U'}
            </div>
            <h2 className="font-bold text-lg text-gray-900">{profile.username}</h2>
            <span className={`inline-block mt-1 mb-2 px-2.5 py-0.5 rounded-full text-xs font-semibold ${roleDisplay.className}`}>
              {roleDisplay.label}
            </span>
            <p className="text-sm text-gray-400 mb-4">
              Member since {profile.memberSince ? new Date(profile.memberSince).toLocaleDateString('en-SG', { month: 'long', year: 'numeric' }) : '—'}
            </p>
            <div className="space-y-2 text-sm text-gray-600 text-left">
              <div className="flex items-center gap-2"><Mail size={14} className="text-gray-400" />{profile.email}</div>
              {profile.phone && <div className="flex items-center gap-2"><Phone size={14} className="text-gray-400" />{profile.phone}</div>}
              {profile.address && <div className="flex items-center gap-2"><MapPin size={14} className="text-gray-400" />{profile.address}</div>}
            </div>
            <Link to="/profile/edit" className="mt-4 flex items-center justify-center gap-2 border border-gray-200 rounded-lg py-2 text-sm hover:bg-gray-50 transition-colors">
              <Edit3 size={14} /> Edit Profile
            </Link>
          </div>

          <div className="card p-5">
            <h3 className="font-bold text-gray-900 mb-3">Rating Summary</h3>
            <div className="flex items-center gap-3 mb-3">
              <span className="text-3xl font-bold text-gray-900">{avgRating.toFixed(1)}</span>
              <div>
                <StarRating value={Math.round(avgRating)} />
                <p className="text-xs text-gray-400 mt-0.5">{reviewCount} reviews</p>
              </div>
            </div>
            {[5, 4, 3, 2, 1].map((star, idx) => (
              <div key={star} className="flex items-center gap-2 mb-1">
                <span className="text-xs w-4">{star}★</span>
                <div className="flex-1 h-2 bg-gray-100 rounded-full overflow-hidden">
                  <div
                    className="h-full bg-yellow-400 rounded-full"
                    style={{ width: `${reviewCount > 0 ? (starCounts[idx] / Math.max(reviewCount, 1)) * 100 : 0}%` }}
                  />
                </div>
                <span className="text-xs text-gray-400">{starCounts[idx] ?? 0}</span>
              </div>
            ))}
          </div>
        </div>

        {/* Right: Transactions */}
        <div className="md:col-span-2">
          <div className="card overflow-hidden">
            <div className="flex border-b border-gray-100">
              {['transactions', 'orders', 'reviews', 'payment'].map(t => (
                <button
                  key={t}
                  onClick={() => setTab(t)}
                  className={`flex-1 py-3 text-sm font-medium capitalize transition-colors ${tab === t ? 'text-blue-500 border-b-2 border-blue-500 bg-blue-50' : 'text-gray-500 hover:bg-gray-50'}`}
                >
                  {t === 'transactions' ? '📋 Transactions' : t === 'orders' ? '📦 Orders' : t === 'reviews' ? '⭐ Reviews' : '💳 Payment'}
                </button>
              ))}
            </div>

            {tab === 'transactions' && (
              <div className="p-5">
                <div className="flex items-center justify-between mb-4">
                  <h3 className="font-bold text-gray-900">Recent Transactions</h3>
                  <select
                    value={filter}
                    onChange={e => setFilter(e.target.value)}
                    className="border border-gray-200 rounded-lg px-3 py-1.5 text-sm focus:outline-none"
                  >
                    {['All', 'Purchase', 'Sale'].map(f => <option key={f}>{f}</option>)}
                  </select>
                </div>
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="text-gray-400 text-xs text-left border-b border-gray-100">
                        <th className="pb-2">ID</th>
                        <th className="pb-2">Date</th>
                        <th className="pb-2">Item</th>
                        <th className="pb-2">Type</th>
                        <th className="pb-2">Amount</th>
                        <th className="pb-2">Status</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-50">
                      {filtered.map(t => (
                        <tr key={t.displayId}>
                          <td className="py-3 text-gray-500">{t.displayId}</td>
                          <td className="py-3 text-gray-500 flex items-center gap-1">
                            <span className="text-gray-400">📅</span>{t.transactionDate}
                          </td>
                          <td className="py-3 font-medium">{t.itemTitle}</td>
                          <td className="py-3">
                            <span className={`px-2 py-0.5 rounded text-xs font-medium ${t.transactionType === 'purchase' ? 'bg-blue-100 text-blue-600' : 'bg-green-100 text-green-600'}`}>
                              {t.transactionType}
                            </span>
                          </td>
                          <td className="py-3 font-medium">$ {Number(t.amount).toFixed(2)}</td>
                          <td className="py-3">
                            <span className={`text-xs font-medium ${
                              t.status === 'Completed' ? 'text-green-500' :
                              t.status === 'Cancelled' ? 'text-red-400' :
                              'text-yellow-500'
                            }`}>
                              {t.status}
                            </span>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  {filtered.length === 0 && (
                    <div className="text-center py-8 text-gray-400 text-sm">No transactions.</div>
                  )}
                </div>
                <div className="flex gap-6 pt-4 border-t border-gray-100 mt-4">
                  <div className="text-center"><span className="block text-xl font-bold text-blue-500">{totalPurchases}</span><span className="text-xs text-gray-400">Total Purchases</span></div>
                  <div className="text-center"><span className="block text-xl font-bold text-green-500">{totalSales}</span><span className="text-xs text-gray-400">Total Sales</span></div>
                  <div className="text-center"><span className="block text-xl font-bold text-purple-500">${totalVolume.toLocaleString()}</span><span className="text-xs text-gray-400">Total Volume</span></div>
                </div>
              </div>
            )}

            {tab === 'orders' && (
              <div className="p-5">
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
                <h3 className="font-bold text-gray-900 mb-1 flex items-center gap-2">
                  <Package size={16} className="text-gray-400" /> Orders
                </h3>
                <p className="text-xs text-gray-400 mb-4">
                  Pay as buyer · sellers ship · you confirm receipt · rate after complete · message the other party or request a refund (the seller decides) if needed.
                </p>
                {orderMsg && <div className="text-sm text-blue-600 mb-3">{orderMsg}</div>}
                {orders.length === 0 ? (
                  <div className="text-center text-gray-400 text-sm py-8">No orders yet.</div>
                ) : (
                  <div className="space-y-2">
                    {orders.map(o => (
                      <div key={o.id} className="border border-gray-200 rounded-lg px-4 py-3">
                        <div className="flex items-start justify-between gap-3">
                          <div>
                            <p className="text-sm font-medium text-gray-800">{o.auctionTitle}</p>
                            <p className="text-xs text-gray-400">
                              {o.role === 'buyer' ? 'Bought from' : 'Sold to'} {o.counterparty} · {formatCurrency(o.amount)}
                            </p>
                            {o.shippingStatus && o.status === 'PAID' && (
                              <p className="text-xs text-blue-500 mt-0.5 flex items-center gap-1">
                                <Truck size={12} /> {o.shippingStatus.replace('_', ' ').toLowerCase()}
                              </p>
                            )}
                            {o.refundStatus === 'REQUESTED' && (
                              <p className="text-xs text-orange-500 mt-0.5 flex items-center gap-1">
                                <RotateCcw size={12} />
                                {o.role === 'seller' ? 'Refund requested by buyer' : 'Refund requested — awaiting seller'}
                              </p>
                            )}
                            {o.refundStatus === 'REQUESTED' && o.role === 'seller' && o.refundReason && (
                              <p className="text-xs text-gray-500 mt-0.5 italic">“{o.refundReason}”</p>
                            )}
                            {o.refundStatus === 'APPROVED' && (
                              <p className="text-xs text-green-600 mt-0.5 flex items-center gap-1">
                                <RotateCcw size={12} /> Refund approved · order cancelled
                              </p>
                            )}
                            {o.refundStatus === 'REJECTED' && (
                              <p className="text-xs text-gray-500 mt-0.5 flex items-center gap-1">
                                <RotateCcw size={12} /> Refund declined by seller
                              </p>
                            )}
                            {o.role === 'seller' && o.status === 'PAID' && (o.shippingStatus || '').toUpperCase() === 'DELIVERED' && (
                              <p className="text-xs text-amber-600 mt-0.5">Waiting for buyer to confirm receipt</p>
                            )}
                          </div>
                          <span className={`text-xs font-medium px-2 py-0.5 rounded-full shrink-0 ${
                            o.status === 'COMPLETED' ? 'bg-green-100 text-green-600'
                            : o.status === 'PAID' ? 'bg-blue-100 text-blue-600'
                            : o.status === 'CANCELLED' ? 'bg-red-100 text-red-600'
                            : 'bg-yellow-100 text-yellow-700'}`}>
                            {o.status.replace('_', ' ')}
                          </span>
                        </div>
                        <div className="flex flex-wrap gap-2 mt-3">
                          {o.role === 'buyer' && o.status !== 'PENDING_PAYMENT' && (
                            <button
                              onClick={() => setTrackOrder(o)}
                              className="text-xs border border-gray-200 px-3 py-1.5 rounded-lg hover:bg-gray-50"
                            >
                              Track order
                            </button>
                          )}
                          {o.role === 'buyer' && o.status === 'PENDING_PAYMENT' && (
                            <button onClick={() => handlePayOrder(o.id)} className="text-xs bg-blue-500 hover:bg-blue-600 text-white px-3 py-1.5 rounded-lg">
                              Pay now
                            </button>
                          )}
                          {o.role === 'seller' && o.status === 'PAID' && shippingActionLabel(o.shippingStatus) && (
                            <button onClick={() => handleAdvanceShipping(o.id)} className="text-xs bg-indigo-500 hover:bg-indigo-600 text-white px-3 py-1.5 rounded-lg">
                              {shippingActionLabel(o.shippingStatus)}
                            </button>
                          )}
                          {o.role === 'buyer' && o.status === 'PAID' && (o.shippingStatus || '').toUpperCase() === 'DELIVERED' && o.refundStatus !== 'REQUESTED' && o.refundStatus !== 'APPROVED' && (
                            <button onClick={() => handleConfirmReceipt(o.id)} className="text-xs bg-green-500 hover:bg-green-600 text-white px-3 py-1.5 rounded-lg">
                              Confirm receipt
                            </button>
                          )}
                          {o.status !== 'PENDING_PAYMENT' && o.status !== 'CANCELLED' && (
                            <button
                              onClick={() => setContactOrder(o)}
                              className="text-xs flex items-center gap-1 border border-gray-200 px-3 py-1.5 rounded-lg hover:bg-gray-50"
                            >
                              <MessageCircle size={12} /> {o.role === 'buyer' ? 'Contact seller' : 'Message buyer'}
                            </button>
                          )}
                          {o.role === 'buyer' && o.status === 'PAID' && !o.refundStatus && (
                            <button
                              onClick={() => setRefundOrder(o)}
                              className="text-xs flex items-center gap-1 border border-orange-200 text-orange-600 px-3 py-1.5 rounded-lg hover:bg-orange-50"
                            >
                              <RotateCcw size={12} /> Request refund
                            </button>
                          )}
                          {o.role === 'seller' && o.refundStatus === 'REQUESTED' && (
                            <>
                              <button
                                onClick={() => handleResolveRefund(o.id, true)}
                                className="text-xs flex items-center gap-1 bg-green-500 hover:bg-green-600 text-white px-3 py-1.5 rounded-lg"
                              >
                                Approve refund
                              </button>
                              <button
                                onClick={() => handleResolveRefund(o.id, false)}
                                className="text-xs flex items-center gap-1 border border-red-200 text-red-600 px-3 py-1.5 rounded-lg hover:bg-red-50"
                              >
                                Decline refund
                              </button>
                            </>
                          )}
                          {o.status === 'COMPLETED' && !o.hasRated && o.role === 'buyer' && (
                            <Link
                              to={`/rate-seller/${o.auctionId}`}
                              className="text-xs flex items-center gap-1 bg-yellow-500 hover:bg-yellow-600 text-white px-3 py-1.5 rounded-lg"
                            >
                              <Star size={12} /> Rate seller
                            </Link>
                          )}
                          {o.status === 'COMPLETED' && !o.hasRated && o.role === 'seller' && (
                            <button
                              onClick={() => setRateOrder(o)}
                              className="text-xs flex items-center gap-1 bg-yellow-500 hover:bg-yellow-600 text-white px-3 py-1.5 rounded-lg"
                            >
                              <Star size={12} /> Rate buyer
                            </button>
                          )}
                          {o.status === 'COMPLETED' && o.hasRated && (
                            <span className="text-xs text-gray-400 px-2 py-1.5">Rated ✓</span>
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
                <h3 className="font-bold text-gray-900 mb-1 flex items-center gap-2">
                  <CreditCard size={16} className="text-gray-400" /> Payment Methods
                </h3>
                <p className="text-xs text-gray-400 mb-4">
                  Card numbers are encrypted (AES-GCM) before storage. We never store your CVV.
                </p>

                <div className="space-y-2 mb-6">
                  {cards.length === 0 ? (
                    <div className="text-center text-gray-400 text-sm py-6">No saved cards.</div>
                  ) : cards.map(c => (
                    <div key={c.id} className="flex items-center justify-between border border-gray-200 rounded-lg px-4 py-3">
                      <div className="flex items-center gap-3">
                        <CreditCard size={20} className="text-gray-400" />
                        <div>
                          <p className="text-sm font-medium text-gray-800">
                            {c.cardBrand} •••• {c.last4}
                            {c.default && <span className="ml-2 text-xs bg-blue-100 text-blue-600 px-2 py-0.5 rounded-full">Default</span>}
                          </p>
                          <p className="text-xs text-gray-400">{c.cardHolder} · Exp {String(c.expMonth).padStart(2, '0')}/{c.expYear}</p>
                        </div>
                      </div>
                      <div className="flex items-center gap-3">
                        {!c.default && (
                          <button onClick={() => handleDefaultCard(c.id)} className="text-xs text-blue-500 hover:underline">
                            Set default
                          </button>
                        )}
                        <button onClick={() => handleDeleteCard(c.id)} className="text-gray-400 hover:text-red-500">
                          <Trash2 size={16} />
                        </button>
                      </div>
                    </div>
                  ))}
                </div>

                <form onSubmit={handleAddCard} className="border-t border-gray-100 pt-4 space-y-3">
                  <h4 className="text-sm font-semibold text-gray-800 flex items-center gap-1"><Plus size={14} /> Add a card</h4>
                  {cardMsg && <div className="text-green-600 text-xs">{cardMsg}</div>}
                  {cardErr && <div className="text-red-500 text-xs">{cardErr}</div>}
                  <input
                    type="text" required placeholder="Cardholder name"
                    value={cardForm.cardHolder}
                    onChange={e => setCardForm(f => ({ ...f, cardHolder: e.target.value }))}
                    className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-200"
                  />
                  <input
                    type="text" required inputMode="numeric" placeholder="Card number"
                    value={cardForm.cardNumber}
                    onChange={e => setCardForm(f => ({ ...f, cardNumber: e.target.value }))}
                    className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-200"
                  />
                  <div className="flex gap-3">
                    <input
                      type="number" required min="1" max="12" placeholder="MM"
                      value={cardForm.expMonth}
                      onChange={e => setCardForm(f => ({ ...f, expMonth: e.target.value }))}
                      className="w-20 border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-200"
                    />
                    <input
                      type="number" required min="2024" placeholder="YYYY"
                      value={cardForm.expYear}
                      onChange={e => setCardForm(f => ({ ...f, expYear: e.target.value }))}
                      className="w-28 border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-200"
                    />
                    <label className="flex items-center gap-2 text-sm text-gray-600">
                      <input
                        type="checkbox"
                        checked={cardForm.makeDefault}
                        onChange={e => setCardForm(f => ({ ...f, makeDefault: e.target.checked }))}
                      />
                      Default
                    </label>
                  </div>
                  <button type="submit" className="bg-blue-500 hover:bg-blue-600 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors">
                    Add Card
                  </button>
                </form>
              </div>
            )}

            {tab === 'reviews' && (
              <div className="p-5">
                {reviews.length === 0 ? (
                  <div className="text-center text-gray-400 py-12">No reviews yet.</div>
                ) : (
                  <div className="divide-y divide-gray-100">
                    {reviews.map((rev, i) => (
                      <div key={i} className="py-4 flex gap-3">
                        <div className="w-10 h-10 rounded-full bg-gray-200 flex items-center justify-center shrink-0 text-gray-600 font-semibold text-sm">
                          {(rev.reviewerMaskedName ?? 'U').charAt(0).toUpperCase()}
                        </div>
                        <div className="flex-1">
                          <div className="flex items-center justify-between mb-1">
                            <span className="font-semibold text-sm text-gray-800">{rev.reviewerMaskedName ?? 'User'}</span>
                            <span className="text-xs text-gray-400">
                              {rev.reviewDate ? new Date(rev.reviewDate).toLocaleDateString('en-US', { day: 'numeric', month: 'short', year: 'numeric' }) : ''}
                            </span>
                          </div>
                          <div className="flex">
                            {[1, 2, 3, 4, 5].map(s => (
                              <span key={s} className={s <= (rev.rating ?? 0) ? 'text-yellow-400' : 'text-gray-200'}>★</span>
                            ))}
                          </div>
                          {rev.auctionTitle && <p className="text-xs text-gray-400 mt-0.5">on {rev.auctionTitle}</p>}
                          {rev.comment && <p className="text-sm text-gray-600 mt-1.5">{decodeHtmlEntities(rev.comment)}</p>}
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
