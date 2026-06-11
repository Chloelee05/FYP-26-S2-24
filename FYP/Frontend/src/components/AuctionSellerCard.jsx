import { Link } from 'react-router-dom';
import StarRating from './StarRating';
import { publicPath } from '../utils/appBase';

export default function AuctionSellerCard({ seller }) {
  if (!seller) return null;

  const memberYear = seller.memberSince
    ? new Date(seller.memberSince).toLocaleDateString('en-US', { month: 'short', year: 'numeric' })
    : '—';

  return (
    <div className="card p-4 mb-4">
      <p className="text-xs font-semibold text-gray-400 uppercase tracking-wide mb-3">Seller</p>
      <div className="flex gap-4">
        <div className="w-14 h-14 rounded-full bg-gradient-to-br from-blue-400 to-purple-500 flex items-center justify-center text-white text-xl font-bold shrink-0">
          {seller.profileImageUrl
            ? <img src={publicPath(seller.profileImageUrl)} alt="" className="w-full h-full rounded-full object-cover" />
            : (seller.username?.[0]?.toUpperCase() ?? 'S')}
        </div>
        <div className="flex-1 min-w-0">
          <Link to={`/seller/${seller.id}`} className="font-bold text-gray-900 hover:text-blue-500">
            {seller.username}
          </Link>
          <div className="flex items-center gap-2 mt-1">
            <StarRating value={Math.round(seller.avgRating ?? 0)} size={14} />
            <span className="text-sm font-medium">{(seller.avgRating ?? 0).toFixed(1)}</span>
            <span className="text-xs text-gray-400">({seller.reviewCount ?? 0} reviews)</span>
          </div>
          <div className="grid grid-cols-2 gap-x-4 gap-y-1 mt-2 text-xs text-gray-500">
            <span>Joined {memberYear}</span>
            <span>{seller.completedSales ?? 0} completed sales</span>
            <span>{seller.activeListings ?? 0} active listings</span>
          </div>
        </div>
      </div>
      {seller.reviews?.length > 0 && (
        <div className="mt-3 pt-3 border-t border-gray-100">
          <p className="text-xs font-semibold text-gray-400 mb-2">Recent reviews</p>
          <div className="space-y-2">
            {seller.reviews.slice(0, 2).map((r, i) => (
              <div key={i} className="text-xs bg-gray-50 rounded-lg p-2">
                <div className="flex items-center gap-2 mb-0.5">
                  <StarRating value={r.rating} size={12} />
                  <span className="text-gray-400">{r.reviewerMaskedName ?? r.reviewerName}</span>
                </div>
                {r.comment && <p className="text-gray-600 line-clamp-2">{r.comment}</p>}
              </div>
            ))}
          </div>
          <Link to={`/seller/${seller.id}`} className="text-xs text-blue-500 hover:underline mt-2 inline-block">
            View full seller profile →
          </Link>
        </div>
      )}
    </div>
  );
}
