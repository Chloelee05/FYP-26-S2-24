import { useState, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  X, ArrowRight, ShieldCheck, Gavel, Sparkles, TrendingUp, Search as SearchIcon,
  Watch, Headphones, Car, Smartphone, Home as HomeIcon, Camera, Tag, SearchX,
  AlertCircle, RotateCcw,
} from 'lucide-react';
import { apiErrorMessage } from '../utils/apiError';
import AuctionCard from '../components/AuctionCard';
import {
  getTrendingAuctions, getCategories, getRecommendations, getFeaturedListings,
  dismissRecommendation, recordRecommendationImpressions, recordRecommendationClick,
} from '../api/auction';
import { useAuth } from '../context/AuthContext';

const HERO_TILES = [Watch, Headphones, Car, Smartphone, HomeIcon, Camera];

const TRUST_POINTS = [
  { icon: ShieldCheck, title: 'Verified sellers', text: 'Ratings and reviews on every listing.' },
  { icon: Gavel, title: 'Three auction types', text: 'Ascending, Dutch and sealed-bid listings.' },
  { icon: Sparkles, title: 'Smart picks', text: 'Recommendations tuned to what you bid on.' },
];

function SectionHeader({ title, subtitle, action, icon: Icon }) {
  return (
    <div className="flex items-end justify-between gap-4 mb-6">
      <div>
        <h2 className="section-title flex items-center gap-2">
          {Icon && <Icon size={20} className="text-primary-600" />}
          {title}
        </h2>
        {subtitle && <p className="text-sm text-ink-500 mt-1">{subtitle}</p>}
      </div>
      {action}
    </div>
  );
}

function CardSkeleton() {
  return (
    <div className="card overflow-hidden">
      <div className="aspect-square skeleton rounded-none" />
      <div className="p-4 space-y-2.5">
        <div className="skeleton h-3.5 w-4/5" />
        <div className="skeleton h-3 w-1/2" />
        <div className="skeleton h-6 w-2/3 mt-3" />
        <div className="skeleton h-9 w-full rounded-xl" />
      </div>
    </div>
  );
}

export default function Home() {
  const navigate = useNavigate();
  const { user } = useAuth();
  const [auctions, setAuctions] = useState([]);
  const [categories, setCategories] = useState([]);
  const [recommended, setRecommended] = useState([]);
  const [personalised, setPersonalised] = useState(false);
  const [featured, setFeatured] = useState([]);
  const [loadingTrending, setLoadingTrending] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    setLoadError('');
    setLoadingTrending(true);
    getCategories().then(r => setCategories(r.data)).catch(() => {});
    // Genuinely trending, ranked server-side. /api/search has no bid-count sort and
    // ignores unknown params, so it cannot produce this list.
    getTrendingAuctions(8)
      .then(r => setAuctions(r.data.results ?? []))
      // A failure here leaves the page empty, so say why rather than showing nothing.
      .catch(err => setLoadError(apiErrorMessage(err, 'Could not load auctions right now.')))
      .finally(() => setLoadingTrending(false));
    getFeaturedListings(8).then(r => setFeatured(r.data.results ?? [])).catch(() => {});
  }, [reloadKey]);

  useEffect(() => {
    getRecommendations()
      .then(r => {
        const results = r.data.results ?? [];
        setRecommended(results);
        setPersonalised(Boolean(r.data.personalised));
        // Impression tracking for recommendation performance metrics.
        if (results.length > 0) {
          recordRecommendationImpressions(results.map(a => a.auctionId)).catch(() => {});
        }
      })
      .catch(() => { setRecommended([]); setPersonalised(false); });
  }, [user]);

  const handleDismiss = async (auctionId) => {
    setRecommended(prev => prev.filter(a => a.auctionId !== auctionId));
    try { await dismissRecommendation(auctionId); } catch { /* keep hidden locally */ }
  };

  return (
    <div className="min-h-screen">
      {/* Hero */}
      <section className="relative overflow-hidden bg-ink-900 text-white">
        <div
          className="absolute inset-0 opacity-90"
          style={{
            backgroundImage:
              'radial-gradient(48rem 32rem at 12% 10%, #1d4dd8, transparent 62%), radial-gradient(42rem 30rem at 88% 90%, #2560eb, transparent 58%), radial-gradient(30rem 22rem at 70% 5%, rgba(249,126,7,0.35), transparent 60%)',
          }}
        />
        <div className="relative max-w-7xl mx-auto px-4 py-16 md:py-24 grid md:grid-cols-2 items-center gap-12">
          <div className="animate-fade-up">
            <span className="inline-flex items-center gap-2 rounded-full bg-white/10 border border-white/15 px-3.5 py-1.5 text-xs font-semibold backdrop-blur-sm">
              <span className="w-1.5 h-1.5 rounded-full bg-accent-400 animate-pulse-dot" />
              Live auctions running right now
            </span>
            <h1 className="font-display text-4xl md:text-6xl font-extrabold mt-5 leading-[1.05] tracking-tight">
              Bid smart,<br />buy&nbsp;
              <span className="bg-gradient-to-r from-accent-300 to-accent-500 bg-clip-text text-transparent">right.</span>
            </h1>
            <p className="text-base md:text-lg text-white/70 mt-5 max-w-md leading-relaxed">
              List your items, bid on your favourites, and find the perfect deal — with live pricing and no surprises.
            </p>

            <div className="flex flex-wrap gap-3 mt-8">
              <button
                onClick={() => navigate('/search')}
                className="inline-flex items-center gap-2 bg-white text-ink-900 px-6 py-3 rounded-xl font-semibold text-sm shadow-lift hover:bg-ink-100 hover:-translate-y-0.5 transition-all"
              >
                <SearchIcon size={16} /> Explore auctions
              </button>
              {!user && (
                <Link
                  to="/register"
                  className="inline-flex items-center gap-2 border border-white/25 bg-white/5 backdrop-blur-sm px-6 py-3 rounded-xl font-semibold text-sm hover:bg-white/15 transition-colors"
                >
                  Start selling <ArrowRight size={16} />
                </Link>
              )}
            </div>

            <div className="flex flex-wrap gap-x-8 gap-y-3 mt-10 pt-8 border-t border-white/10">
              {TRUST_POINTS.map(({ icon: Icon, title, text }) => (
                <div key={title} className="flex items-start gap-2.5">
                  <Icon size={16} className="text-accent-300 mt-0.5 shrink-0" />
                  <div>
                    <p className="text-sm font-semibold">{title}</p>
                    <p className="text-xs text-white/55">{text}</p>
                  </div>
                </div>
              ))}
            </div>
          </div>

          <div className="hidden md:grid grid-cols-3 gap-3 max-w-sm ml-auto">
            {HERO_TILES.map((Icon, i) => (
              <div
                key={i}
                className="aspect-square rounded-2xl bg-white/10 border border-white/15 backdrop-blur-sm flex items-center justify-center text-white/80
                           shadow-pop transition-all duration-300 hover:scale-105 hover:bg-white/15 hover:text-white"
                style={{ animation: `fade-up 0.5s cubic-bezier(0.22,1,0.36,1) ${i * 0.06}s both` }}
              >
                <Icon size={30} strokeWidth={1.5} />
              </div>
            ))}
          </div>
        </div>
      </section>

      <div className="max-w-7xl mx-auto px-4 py-12 space-y-14">
        {/* Categories */}
        {categories.length > 0 && (
          <section>
            <SectionHeader
              title="Popular Categories"
              subtitle="Jump straight into the collections buyers browse most."
            />
            <div className="grid grid-cols-3 sm:grid-cols-4 md:grid-cols-6 lg:grid-cols-8 gap-3">
              {categories.map((cat) => (
                <Link
                  key={cat.name}
                  to={`/search?category=${encodeURIComponent(cat.name)}`}
                  className="card card-hover flex flex-col items-center gap-2 py-5 px-2 text-center group"
                >
                  <span className="grid place-items-center w-14 h-14 rounded-2xl bg-ink-100 text-ink-500 transition-colors group-hover:bg-primary-50 group-hover:text-primary-600">
                    <Tag size={22} strokeWidth={1.75} />
                  </span>
                  <span className="text-xs font-semibold text-ink-700 leading-tight line-clamp-2 group-hover:text-primary-600 transition-colors">
                    {cat.name}
                  </span>
                </Link>
              ))}
            </div>
          </section>
        )}

        {/* Featured listings */}
        {featured.length > 0 && (
          <section>
            <SectionHeader
              icon={Sparkles}
              title="Featured Listings"
              subtitle="Promoted auctions from our sellers."
            />
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4 sm:gap-5">
              {featured.map(a => <AuctionCard key={`f-${a.auctionId}`} auction={a} />)}
            </div>
          </section>
        )}

        {/* Recommendations */}
        {recommended.length > 0 && (
          <section>
            <SectionHeader
              title={personalised ? 'Recommended for You' : 'Popular Right Now'}
              subtitle={personalised
                ? 'Based on items you and similar buyers have bid on or watched.'
                : 'Trending auctions across the marketplace. Sign in for personalised picks.'}
            />
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4 sm:gap-5">
              {recommended.map(a => (
                <div
                  key={a.auctionId}
                  className="relative group/rec"
                  onClickCapture={e => {
                    if (e.target.closest('[data-dismiss]')) return; // dismissing ≠ clicking through
                    recordRecommendationClick(a.auctionId).catch(() => {});
                  }}
                >
                  {user && (
                    <button
                      type="button"
                      data-dismiss
                      onClick={e => { e.preventDefault(); e.stopPropagation(); handleDismiss(a.auctionId); }}
                      title="Not interested — hide this recommendation"
                      className="absolute -top-2 -right-2 z-20 bg-white text-ink-400 hover:text-ink-900 hover:scale-110 rounded-full p-1.5
                                 shadow-lift border border-ink-200 opacity-0 group-hover/rec:opacity-100 transition-all"
                    >
                      <X size={14} />
                    </button>
                  )}
                  <AuctionCard auction={a} />
                </div>
              ))}
            </div>
          </section>
        )}

        {/* Trending Auctions */}
        <section>
          <SectionHeader
            icon={TrendingUp}
            title="Trending Auctions"
            subtitle="The listings collecting the most bids today."
            action={
              <Link to="/search" className="hidden sm:inline-flex items-center gap-1.5 text-sm font-semibold text-primary-600 hover:text-primary-700 transition-colors">
                View all <ArrowRight size={15} />
              </Link>
            }
          />
          {loadingTrending ? (
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4 sm:gap-5">
              {Array.from({ length: 4 }, (_, i) => <CardSkeleton key={i} />)}
            </div>
          ) : loadError ? (
            <div className="card p-12 text-center">
              <span className="grid place-items-center w-14 h-14 rounded-2xl bg-red-50 text-red-500 mx-auto mb-4">
                <AlertCircle size={26} />
              </span>
              <p className="font-semibold text-ink-800">Couldn’t load auctions</p>
              <p className="text-sm text-ink-500 mt-1">{loadError}</p>
              <button onClick={() => setReloadKey(k => k + 1)} className="btn-primary mt-5">
                <RotateCcw size={16} /> Try again
              </button>
            </div>
          ) : auctions.length === 0 ? (
            <div className="card p-12 text-center">
              <span className="grid place-items-center w-14 h-14 rounded-2xl bg-ink-100 text-ink-400 mx-auto mb-4">
                <SearchX size={26} />
              </span>
              <p className="font-semibold text-ink-800">No live auctions right now</p>
              <p className="text-sm text-ink-500 mt-1">Check back soon, or browse everything on the market.</p>
              <Link to="/search" className="btn-primary mt-5">Browse all auctions</Link>
            </div>
          ) : (
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4 sm:gap-5">
              {auctions.map(a => <AuctionCard key={a.auctionId ?? a.id} auction={a} />)}
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
