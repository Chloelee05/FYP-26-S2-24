/**
 * Seller summary in the sidebar of an auction detail page: avatar, average rating,
 * joined date, sale counts and the two most recent reviews.
 *
 * Props: `seller`, the nested seller object from GET /api/auction/{id}. Renders nothing
 * if it is absent, which is what a listing whose seller row has gone looks like.
 *
 * Reviewer names arrive masked from the server, and comments are run through
 * decodeHtmlEntities because they were escaped by SecurityUtil.sanitize on the way in.
 */
import { Link } from 'react-router-dom';
import { ArrowRight } from 'lucide-react';
import StarRating from './StarRating';
import { publicPath } from '../utils/appBase';
import { decodeHtmlEntities } from '../utils/helpers';

export default function AuctionSellerCard({ seller }) {
  if (!seller) return null;

  const memberYear = seller.memberSince
    ? new Date(seller.memberSince).toLocaleDateString('en-US', { month: 'short', year: 'numeric' })
    : '—';

  const stats = [
    { label: 'Joined', value: memberYear },
    { label: 'Completed sales', value: seller.completedSales ?? 0 },
    { label: 'Active listings', value: seller.activeListings ?? 0 },
  ];

  return (
    <div className="card p-5 mb-4">
      <p className="eyebrow mb-4">Seller</p>
      <div className="flex gap-4">
        {/* The gradient belongs to the initial fallback only — behind a photo with
            transparency it would tint the image. */}
        {seller.profileImageUrl ? (
          <img
            src={publicPath(seller.profileImageUrl)}
            alt=""
            className="w-14 h-14 rounded-2xl object-cover bg-white border border-ink-200 shrink-0"
          />
        ) : (
          <div className="w-14 h-14 rounded-2xl bg-gradient-to-br from-primary-500 to-purple-500 flex items-center justify-center text-white text-xl font-bold shrink-0 shadow-sm">
            {seller.username?.[0]?.toUpperCase() ?? 'S'}
          </div>
        )}
        <div className="flex-1 min-w-0">
          <Link to={`/seller/${seller.id}`} className="font-bold text-ink-900 hover:text-primary-600 transition-colors">
            {seller.username}
          </Link>
          <div className="flex items-center gap-2 mt-1">
            <StarRating value={Math.round(seller.avgRating ?? 0)} size={14} />
            <span className="text-sm font-bold text-ink-800">{(seller.avgRating ?? 0).toFixed(1)}</span>
            <span className="text-xs text-ink-400">({seller.reviewCount ?? 0} reviews)</span>
          </div>
          <div className="grid grid-cols-3 gap-2 mt-3">
            {stats.map(s => (
              <div key={s.label} className="surface-muted px-2 py-2 text-center">
                <p className="text-sm font-bold text-ink-800 leading-tight">{s.value}</p>
                <p className="text-[11px] text-ink-400 mt-0.5 leading-tight">{s.label}</p>
              </div>
            ))}
          </div>
        </div>
      </div>

      {seller.reviews?.length > 0 && (
        <div className="mt-4 pt-4 border-t border-ink-100">
          <p className="eyebrow mb-2.5">Recent reviews</p>
          <div className="space-y-2">
            {seller.reviews.slice(0, 2).map((r, i) => (
              <div key={i} className="surface-muted p-3">
                <div className="flex items-center gap-2 mb-1">
                  <StarRating value={r.rating} size={12} />
                  <span className="text-xs text-ink-400">{r.reviewerMaskedName ?? r.reviewerName}</span>
                </div>
                {r.comment && <p className="text-xs text-ink-600 line-clamp-2 leading-relaxed">{decodeHtmlEntities(r.comment)}</p>}
              </div>
            ))}
          </div>
          <Link
            to={`/seller/${seller.id}`}
            className="inline-flex items-center gap-1 text-xs font-semibold text-primary-600 hover:text-primary-700 mt-3 transition-colors"
          >
            View full seller profile <ArrowRight size={13} />
          </Link>
        </div>
      )}
    </div>
  );
}
