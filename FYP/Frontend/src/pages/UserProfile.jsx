import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { Mail, Phone, MapPin, Edit3 } from 'lucide-react';
import { getProfile, getTransactionHistory, getMyReviews } from '../api/user';
import { formatCurrency } from '../utils/helpers';
import StarRating from '../components/StarRating';

// Backend fields:
// profile: { id, username, email, role, profileImageUrl, memberSince, phone, address, rating: RatingSummary, transactions: [...] }
// RatingSummary: { average, reviewCount, starCountsHighToLow[5] }
// ProfileTransactionRow: { displayId, transactionDate, itemTitle, transactionType, amount, status }

export default function UserProfile() {
  const [profile, setProfile] = useState(null);
  const [transactions, setTransactions] = useState([]);
  const [reviews, setReviews] = useState([]);
  const [tab, setTab] = useState('transactions');
  const [filter, setFilter] = useState('All');

  useEffect(() => {
    getProfile().then(r => setProfile(r.data)).catch(() => {});
    getTransactionHistory().then(r => setTransactions(r.data ?? [])).catch(() => {});
    getMyReviews().then(r => setReviews(r.data ?? [])).catch(() => {});
  }, []);

  if (!profile) {
    return <div className="max-w-5xl mx-auto px-4 py-16 text-center text-gray-400">Loading profile…</div>;
  }

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
              {['transactions', 'reviews'].map(t => (
                <button
                  key={t}
                  onClick={() => setTab(t)}
                  className={`flex-1 py-3 text-sm font-medium capitalize transition-colors ${tab === t ? 'text-blue-500 border-b-2 border-blue-500 bg-blue-50' : 'text-gray-500 hover:bg-gray-50'}`}
                >
                  {t === 'transactions' ? '📋 Transaction History' : '⭐ Reviews'}
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
                          {rev.comment && <p className="text-sm text-gray-600 mt-1.5">{rev.comment}</p>}
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
