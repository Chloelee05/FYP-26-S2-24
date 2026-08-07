import { useState, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  X, ArrowRight, ShieldCheck, Gavel, Sparkles, TrendingUp, Search as SearchIcon,
  Watch, Headphones, Car, Smartphone, Home as HomeIcon, Camera, SearchX,
  AlertCircle, RotateCcw, UserPlus, BadgeDollarSign, Star, Quote, Check, Scale,
  Timer, LockKeyhole, LineChart, Users, Minus,
} from 'lucide-react';
import { apiErrorMessage } from '../utils/apiError';
import AuctionCard from '../components/AuctionCard';
import CategoryVisual from '../components/CategoryVisual';
import Reveal from '../components/Reveal';
import CountUp from '../components/CountUp';
import {
  getTrendingAuctions, getCategories, getRecommendations, getFeaturedListings,
  dismissRecommendation, recordRecommendationImpressions, recordRecommendationClick,
  getPlatformStats, getLandingContent,
} from '../api/auction';
import { useAuth } from '../context/AuthContext';
import { decodeHtmlEntities } from '../utils/helpers';

const HERO_TILES = [Watch, Headphones, Car, Smartphone, HomeIcon, Camera];

// Arm label for the plain popularity strip at the bottom of the page. Mirrors
// RecommendationDAO.REASON_TRENDING_CONTROL, which validates it server side.
const TRENDING_CONTROL_ARM = 'TRENDING_CONTROL';

// Copy lives in the landing_content table (admin-editable). The strings kept here are the
// fallbacks used when /api/landing-content returns nothing, so the page never renders blank.
const TRUST_POINTS = [
  { icon: ShieldCheck, key: 'trust1', title: 'Verified sellers', text: 'Ratings and reviews on every listing.' },
  { icon: Gavel, key: 'trust2', title: 'Three auction types', text: 'Ascending, Dutch and sealed-bid listings.' },
  { icon: Sparkles, key: 'trust3', title: 'Smart picks', text: 'Recommendations tuned to what you bid on.' },
];

/** Why AuctionHub vs typical fixed-price / classifieds competitors. */
const WHY_AUCTIONHUB = [
  {
    icon: Scale,
    key: 'card1',
    title: 'True price discovery',
    body: 'Bids compete in the open — you don’t guess a “Buy Now” number or settle for the first offer.',
    contrast: 'Fixed-price apps lock you into one sticker price.',
  },
  {
    icon: Timer,
    key: 'card2',
    title: 'Timed urgency that works',
    body: 'Live countdowns, ending-soon sorts and auto-bid mean serious buyers show up before the clock hits zero.',
    contrast: 'Listings on classifieds can sit for weeks with no momentum.',
  },
  {
    icon: LockKeyhole,
    key: 'card3',
    title: 'Built for trust',
    body: 'Masked bidder names, encrypted personal data, seller ratings and report tools — PDPA-aware by design.',
    contrast: 'Many peer-to-peer chats leave you negotiating in DMs with little protection.',
  },
  {
    icon: LineChart,
    key: 'card4',
    title: 'Formats for every item',
    body: 'Ascending, Dutch and sealed-bid auctions — plus Buy It Now when you want an instant sale.',
    contrast: 'One listing style fits every category elsewhere.',
  },
];

/** Head-to-head rows under the differentiation cards. Copy overridden by landing_content. */
const WHY_COMPARISON = [
  {
    key: 'row1',
    label: 'Who sets the price',
    ours: 'Buyers compete in the open, so the market decides what it is worth.',
    theirs: 'One seller picks a number and waits to be haggled down.',
  },
  {
    key: 'row2',
    label: 'Momentum',
    ours: 'A live countdown turns idle interest into a decision.',
    theirs: 'Listings drift for weeks with no deadline to act on.',
  },
  {
    key: 'row3',
    label: 'Your privacy',
    ours: 'Bidder names are masked and personal data is encrypted.',
    theirs: 'Full-name direct messages with strangers in your inbox.',
  },
  {
    key: 'row4',
    label: 'Selling formats',
    ours: 'Ascending, Dutch, sealed-bid — plus Buy It Now for instant sales.',
    theirs: 'One listing style stretched across every category.',
  },
  {
    key: 'row5',
    label: 'Cost to take part',
    ours: 'Free to browse, watch and bid — sellers pay only on a sale.',
    theirs: 'Paid bumps and boosts just to stay visible in the feed.',
  },
];

const CTA_POINTS = ['Live ascending bids', 'Dutch & sealed formats', 'Auto-bid proxy', 'Seller ratings'];

/**
 * The hero headline is a single content row ("Bid smart, buy") but renders across two lines,
 * so the presentational break after the comma is re-applied here rather than stored in the DB.
 */
function splitHeadline(text) {
  const at = text.indexOf(', ');
  return at < 0 ? [text, ''] : [text.slice(0, at + 1), text.slice(at + 2)];
}

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
  const [stats, setStats] = useState(null);
  const [content, setContent] = useState({});
  // How many days of bids the server's trending ranking actually counted. The subtitle
  // states this number, so it is read back from the API rather than assumed here.
  const [trendingWindowDays, setTrendingWindowDays] = useState(null);
  const [loadingTrending, setLoadingTrending] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    getCategories().then(r => setCategories(r.data)).catch(() => {});
    // Genuinely trending, ranked server-side. /api/search has no bid-count sort and
    // ignores unknown params, so it cannot produce this list.
    getTrendingAuctions(8)
      .then(r => {
        setAuctions(r.data.results ?? []);
        setTrendingWindowDays(r.data.trendingWindowDays ?? null);
      })
      // A failure here leaves the page empty, so say why rather than showing nothing.
      .catch(err => setLoadError(apiErrorMessage(err, 'Could not load auctions right now.')))
      .finally(() => setLoadingTrending(false));
    getFeaturedListings(8).then(r => setFeatured(r.data.results ?? [])).catch(() => {});
    // Live platform metrics + fee schedule + testimonials — all DB-driven, never hardcoded.
    getPlatformStats()
      .then(r => setStats(r.data && Object.keys(r.data).length ? r.data : null))
      .catch(() => setStats(null));
    // Admin-editable marketing copy; an empty map falls back to the defaults below.
    getLandingContent().then(r => setContent(r.data ?? {})).catch(() => setContent({}));
  }, [reloadKey]);

  useEffect(() => {
    getRecommendations()
      .then(r => {
        const results = r.data.results ?? [];
        setRecommended(results);
        setPersonalised(Boolean(r.data.personalised));
        // Impression tracking, labelled with the arm that produced each card so the admin
        // dashboard can report click-through per stage instead of one pooled figure.
        if (results.length > 0) {
          recordRecommendationImpressions(
            results.map(a => ({ auctionId: a.auctionId, reasonCode: a.why?.reasonCode }))
          ).catch(() => {});
        }
      })
      .catch(() => { setRecommended([]); setPersonalised(false); });
  }, [user]);

  // The lower "Trending Auctions" strip is not produced by the recommender at all. Recording
  // it under its own arm gives the personalised strip a popularity baseline to be read
  // against. It is not a randomised experiment — see TRENDING_CONTROL in RecommendationDAO.
  useEffect(() => {
    if (auctions.length === 0) return;
    recordRecommendationImpressions(
      auctions.map(a => ({ auctionId: a.auctionId ?? a.id, reasonCode: TRENDING_CONTROL_ARM }))
    ).catch(() => {});
  }, [auctions]);

  const handleDismiss = async (auctionId) => {
    setRecommended(prev => prev.filter(a => a.auctionId !== auctionId));
    try { await dismissRecommendation(auctionId); } catch { /* keep hidden locally */ }
  };

  const c = (key, fallback) => content[key] ?? fallback;
  const [headlineTop, headlineRest] = splitHeadline(c('hero.headline', 'Bid smart, buy'));

  // The trending subtitle names the ranking window. Until the API has answered, the
  // sentence is held back entirely rather than shown with a guessed number in it.
  const trendingSubtitle = (() => {
    const copy = c('section.trending.subtitle', 'The listings collecting the most bids in the last {days} days.');
    if (!copy.includes('{days}')) return copy;
    return trendingWindowDays == null ? '' : copy.replace('{days}', trendingWindowDays);
  })();

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
              <span className="live-dot" />
              {c('hero.eyebrow', 'Live auctions running right now')}
            </span>
            <h1 className="font-display text-4xl md:text-6xl font-extrabold mt-5 leading-[1.05] tracking-tight">
              {headlineTop}{headlineRest && <><br />{headlineRest}</>}&nbsp;
              <span className="bg-gradient-to-r from-accent-300 to-accent-500 bg-clip-text text-transparent">
                {c('hero.headlineAccent', 'right.')}
              </span>
            </h1>
            <p className="text-base md:text-lg text-white/70 mt-5 max-w-md leading-relaxed">
              {c('hero.subheading', 'List your items, bid on your favourites, and find the perfect deal — with live pricing and no surprises.')}
            </p>

            <div className="flex flex-wrap gap-3 mt-8">
              <button
                onClick={() => navigate('/search')}
                className="inline-flex items-center gap-2 bg-white text-ink-900 px-6 py-3 rounded-xl font-semibold text-sm shadow-lift hover:bg-ink-100 hover:-translate-y-0.5 transition-all"
              >
                <SearchIcon size={16} /> {c('hero.ctaPrimary', 'Explore auctions')}
              </button>
              {!user && (
                <Link
                  to="/register"
                  className="group inline-flex items-center gap-2 border border-white/25 bg-white/5 backdrop-blur-sm px-6 py-3 rounded-xl font-semibold text-sm hover:bg-white/15 hover:border-white/40 transition-all"
                >
                  {c('hero.ctaSecondary', 'Start selling')}
                  <ArrowRight size={16} className="transition-transform group-hover:translate-x-0.5" />
                </Link>
              )}
            </div>

            <div className="flex flex-wrap gap-x-8 gap-y-3 mt-10 pt-8 border-t border-white/10">
              {TRUST_POINTS.map(({ icon: Icon, key, title, text }) => (
                <div key={key} className="flex items-start gap-2.5">
                  <Icon size={16} className="text-accent-300 mt-0.5 shrink-0" />
                  <div>
                    <p className="text-sm font-semibold">{c(`hero.${key}.title`, title)}</p>
                    <p className="text-xs text-white/55">{c(`hero.${key}.text`, text)}</p>
                  </div>
                </div>
              ))}
            </div>

            {/* Live platform metrics straight from the database */}
            {stats && (
              <div className="flex flex-wrap gap-x-10 gap-y-4 mt-8 animate-fade-up">
                {[
                  { label: 'Live auctions', value: stats.activeListings },
                  { label: 'Registered users', value: stats.totalUsers },
                  { label: 'Completed sales', value: stats.completedOrders },
                ].filter(s => s.value != null).map(s => (
                  <div key={s.label}>
                    <p className="font-display text-3xl font-extrabold tabular-nums">
                      <CountUp value={s.value} />
                    </p>
                    <p className="text-xs text-white/55 mt-0.5">{s.label}</p>
                  </div>
                ))}
              </div>
            )}
          </div>

          <div className="hidden md:grid grid-cols-3 gap-3 max-w-sm ml-auto">
            {/* Idle drift sits on the wrapper so it never clobbers the hover scale. */}
            {HERO_TILES.map((Icon, i) => (
              <div key={i} className="hero-tile group aspect-square" style={{ '--i': i }}>
                <div
                  className="w-full h-full rounded-2xl bg-white/10 border border-white/15 backdrop-blur-sm flex items-center justify-center text-white/80
                             shadow-pop transition-all duration-300 group-hover:scale-105 group-hover:bg-white/15 group-hover:text-white"
                >
                  <Icon size={30} strokeWidth={1.5} />
                </div>
              </div>
            ))}
          </div>
        </div>
      </section>

      <div className="max-w-7xl mx-auto px-4 py-12 md:py-16 space-y-16 md:space-y-20">
        {/* Why AuctionHub — differentiation vs fixed-price / classified competitors */}
        <Reveal as="section" className="relative overflow-hidden rounded-[2rem] border border-ink-200/70 bg-white shadow-soft">
          <div
            className="pointer-events-none absolute inset-0 opacity-90"
            style={{
              backgroundImage:
                'radial-gradient(36rem 22rem at 0% 0%, rgba(29,77,216,0.08), transparent 55%), radial-gradient(28rem 18rem at 100% 100%, rgba(249,126,7,0.08), transparent 50%)',
            }}
          />
          <div className="relative px-6 py-10 md:px-12 md:py-14">
            <div className="max-w-2xl">
              <p className="inline-flex items-center gap-2 text-[11px] font-bold uppercase tracking-[0.18em] text-primary-600">
                <Users size={14} /> {c('why.eyebrow', 'Why bid here')}
              </p>
              <h2 className="font-display text-3xl md:text-[2.6rem] font-extrabold text-ink-900 mt-4 tracking-tight leading-[1.08]">
                {c('why.heading', 'Not another marketplace.')}&nbsp;
                <span className="text-primary-600">{c('why.headingAccent', 'A real auction floor.')}</span>
              </h2>
              <p className="text-sm md:text-base text-ink-500 mt-4 leading-relaxed">
                {c('why.intro', 'Carousell, Facebook Marketplace and big listing sites are great for fixed prices. AuctionHub is for when you want competition, fair discovery and a clock that actually closes the deal.')}
              </p>
            </div>

            <div className="mt-10 grid md:grid-cols-2 gap-4">
              {WHY_AUCTIONHUB.map(({ icon: Icon, key, title, body, contrast }, i) => (
                <Reveal key={key} delay={80 + i * 70}>
                  <article
                    className="group h-full rounded-2xl border border-ink-200/70 bg-white/85 backdrop-blur-sm p-5 md:p-6
                               transition-all duration-300 hover:-translate-y-0.5 hover:border-primary-200 hover:shadow-lift"
                  >
                    <div className="flex items-start gap-4">
                      <span className="grid place-items-center w-11 h-11 rounded-xl bg-ink-900 text-white shrink-0
                                       transition-all duration-300 group-hover:bg-primary-600 group-hover:scale-105">
                        <Icon size={20} strokeWidth={1.75} />
                      </span>
                      <div className="min-w-0">
                        <h3 className="font-display text-lg font-bold text-ink-900 leading-snug">{c(`why.${key}.title`, title)}</h3>
                        <p className="text-sm text-ink-600 mt-2 leading-relaxed">{c(`why.${key}.body`, body)}</p>
                        <p className="flex items-start gap-2 text-xs text-ink-400 mt-4 leading-relaxed border-t border-ink-100 pt-3">
                          <Minus size={13} className="mt-0.5 shrink-0 text-ink-300" />
                          <span>
                            <span className="font-semibold text-ink-500">{c('why.contrastLabel', 'Elsewhere:')} </span>
                            {c(`why.${key}.contrast`, contrast)}
                          </span>
                        </p>
                      </div>
                    </div>
                  </article>
                </Reveal>
              ))}
            </div>

            {/* Head-to-head: the differentiation claims lined up against the alternative */}
            <Reveal className="mt-10" delay={80}>
              <p className="text-[11px] font-bold uppercase tracking-[0.18em] text-ink-400">
                {c('why.compare.eyebrow', 'Head to head')}
              </p>
              <h3 className="font-display text-xl md:text-2xl font-bold text-ink-900 mt-1.5 tracking-tight">
                {c('why.compare.heading', 'The same item, two very different outcomes.')}
              </h3>

              <div className="mt-5 overflow-hidden rounded-2xl border border-ink-200/70 bg-white/80 backdrop-blur-sm shadow-soft">
                <div className="hidden md:grid grid-cols-[minmax(0,0.8fr)_minmax(0,1.1fr)_minmax(0,1.1fr)] gap-6 border-b border-ink-200/70 bg-ink-50/70 px-6 py-3.5">
                  <span />
                  <span className="inline-flex items-center gap-2 text-sm font-bold text-primary-700">
                    <Gavel size={15} className="shrink-0" />
                    {c('why.compare.ours', 'AuctionHub')}
                  </span>
                  <span className="text-sm font-semibold text-ink-400">
                    {c('why.compare.theirs', 'Fixed-price marketplaces')}
                  </span>
                </div>

                {WHY_COMPARISON.map(({ key, label, ours, theirs }) => (
                  <div
                    key={key}
                    className="grid md:grid-cols-[minmax(0,0.8fr)_minmax(0,1.1fr)_minmax(0,1.1fr)] gap-x-6 gap-y-2 px-6 py-4
                               border-b border-ink-100 last:border-0 transition-colors hover:bg-ink-50/50"
                  >
                    <p className="text-xs font-bold uppercase tracking-[0.12em] text-ink-400 self-center">
                      {c(`why.compare.${key}.label`, label)}
                    </p>
                    <p className="flex items-start gap-2 text-sm font-medium text-ink-800 leading-relaxed">
                      <Check size={15} className="mt-0.5 shrink-0 text-emerald-500" />
                      {c(`why.compare.${key}.ours`, ours)}
                    </p>
                    <p className="flex items-start gap-2 text-sm text-ink-400 leading-relaxed">
                      <Minus size={15} className="mt-0.5 shrink-0 text-ink-300" />
                      {c(`why.compare.${key}.theirs`, theirs)}
                    </p>
                  </div>
                ))}
              </div>
            </Reveal>

            <div className="sheen-host mt-10 rounded-2xl bg-ink-900 text-white px-5 py-6 md:px-8 md:py-7 flex flex-col md:flex-row md:items-center gap-6">
              <div className="flex-1">
                <p className="font-display text-lg md:text-xl font-bold leading-snug max-w-xl">
                  {c('why.ctaHeadline', 'Free to browse. Free to bid. Sellers only pay when something sells.')}
                </p>
                <ul className="mt-4 flex flex-wrap gap-x-5 gap-y-2 text-sm text-white/70">
                  {CTA_POINTS.map((item, i) => (
                    <li key={item} className="inline-flex items-center gap-1.5">
                      <Check size={14} className="text-accent-400 shrink-0" />
                      {c(`why.ctaPoint${i + 1}`, item)}
                    </li>
                  ))}
                </ul>
              </div>
              <div className="relative flex flex-wrap gap-2 shrink-0">
                <Link to="/search" className="btn bg-white text-ink-900 hover:bg-ink-100 hover:-translate-y-0.5 hover:shadow-lift shadow-sm">
                  {c('why.ctaPrimary', 'Explore live auctions')}
                </Link>
                {!user && (
                  <Link
                    to="/register"
                    className="group btn border border-white/25 bg-white/5 hover:bg-white/15 hover:border-white/40 text-white"
                  >
                    {c('why.ctaSecondary', 'Create free account')}
                    <ArrowRight size={15} className="transition-transform group-hover:translate-x-0.5" />
                  </Link>
                )}
              </div>
            </div>
          </div>
        </Reveal>

        {/* Categories — ranked by real listing counts, not a hand-picked order */}
        {categories.length > 0 && (
          <Reveal as="section">
            <SectionHeader
              title={c('section.categories.title', 'Popular Categories')}
              subtitle={c('section.categories.subtitle', 'Ranked by live listing count across the marketplace.')}
            />
            <div className="grid grid-cols-3 sm:grid-cols-4 md:grid-cols-6 lg:grid-cols-8 gap-3">
              {[...categories]
                .sort((a, b) => (b.auctionCount ?? 0) - (a.auctionCount ?? 0))
                .slice(0, 8)
                .map((cat, i) => (
                <Reveal key={cat.name} delay={i * 45}>
                  <Link
                    to={`/search?category=${encodeURIComponent(cat.name)}`}
                    className="card card-hover h-full flex flex-col items-center gap-2 py-5 px-2 text-center group"
                  >
                    <CategoryVisual
                      category={cat}
                      size="md"
                      className="transition-transform duration-300 group-hover:scale-105"
                    />
                    <span className="text-xs font-semibold text-ink-700 leading-tight line-clamp-2 group-hover:text-primary-600 transition-colors">
                      {cat.name}
                    </span>
                    {cat.auctionCount > 0 && (
                      <span className="text-[11px] text-ink-400">{cat.auctionCount} listings</span>
                    )}
                  </Link>
                </Reveal>
              ))}
            </div>
          </Reveal>
        )}

        {/* Featured listings */}
        {featured.length > 0 && (
          <Reveal as="section">
            <SectionHeader
              icon={Sparkles}
              title={c('section.featured.title', 'Featured Listings')}
              subtitle={c('section.featured.subtitle', 'Promoted auctions from our sellers.')}
            />
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4 sm:gap-5">
              {featured.map(a => <AuctionCard key={`f-${a.auctionId}`} auction={a} />)}
            </div>
          </Reveal>
        )}

        {/* Recommendations */}
        {recommended.length > 0 && (
          <Reveal as="section">
            <SectionHeader
              icon={Sparkles}
              title={personalised
                ? c('section.recommended.title', 'Recommended for You')
                : c('section.popular.title', 'Popular Right Now')}
              subtitle={personalised
                ? c('section.recommended.subtitle', 'Based on items you and similar buyers have bid on or watched. Open “why this?” on any card to see the reasoning.')
                : user
                  ? c('section.popular.subtitle.member', 'Trending auctions across the marketplace. Bid on or watch a few listings and this strip becomes your personalised picks.')
                  : c('section.popular.subtitle', 'Trending auctions across the marketplace. Sign in for personalised picks.')}
            />
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4 sm:gap-5">
              {recommended.map(a => (
                <div
                  key={a.auctionId}
                  className="relative group/rec"
                  onClickCapture={e => {
                    // Dismissing, or opening "why this?", is not a click-through.
                    if (e.target.closest('[data-dismiss]') || e.target.closest('[data-why]')) return;
                    recordRecommendationClick(a.auctionId, a.why?.keywords?.[0], a.why?.reasonCode)
                      .catch(() => {});
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
          </Reveal>
        )}

        {/* Trending Auctions */}
        <Reveal as="section">
          <SectionHeader
            icon={TrendingUp}
            title={c('section.trending.title', 'Trending Auctions')}
            subtitle={trendingSubtitle}
            action={
              <Link to="/search" className="group hidden sm:inline-flex items-center gap-1.5 text-sm font-semibold text-primary-600 hover:text-primary-700 transition-colors">
                View all
                <ArrowRight size={15} className="transition-transform group-hover:translate-x-0.5" />
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
              <button
                onClick={() => {
                  setLoadError('');
                  setLoadingTrending(true);
                  setReloadKey(k => k + 1);
                }}
                className="btn-primary mt-5"
              >
                <RotateCcw size={16} /> Try again
              </button>
            </div>
          ) : auctions.length === 0 ? (
            <div className="card p-12 text-center">
              <span className="grid place-items-center w-14 h-14 rounded-2xl bg-ink-100 text-ink-400 mx-auto mb-4">
                <SearchX size={26} />
              </span>
              <p className="font-semibold text-ink-800">No live auctions right now</p>
              <p className="text-sm text-ink-500 mt-1 max-w-sm mx-auto">
                Active timed listings may have ended. Sellers can create a new auction once signed in —
                or browse the full catalogue for recently closed lots.
              </p>
              <Link to="/search" className="btn-primary mt-5">Browse all auctions</Link>
            </div>
          ) : (
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4 sm:gap-5">
              {auctions.map(a => (
                <div
                  key={a.auctionId ?? a.id}
                  // Clicks on the popularity strip are recorded under their own arm so its
                  // click-through can be compared with the personalised strip above.
                  onClickCapture={() => {
                    recordRecommendationClick(a.auctionId ?? a.id, undefined, TRENDING_CONTROL_ARM)
                      .catch(() => {});
                  }}
                >
                  <AuctionCard auction={a} />
                </div>
              ))}
            </div>
          )}
        </Reveal>

        {/* Fee schedule — values come from the billing constants via /api/stats */}
        {stats?.fees && (
          <Reveal as="section">
            <SectionHeader
              icon={BadgeDollarSign}
              title={c('section.fees.title', 'Simple, Transparent Costs')}
              subtitle={c('section.fees.subtitle', 'No surprises — this is everything AuctionHub charges.')}
            />
            <div className="grid sm:grid-cols-3 gap-4">
              <Reveal delay={60}>
                <div className="card card-hover h-full p-6">
                  <p className="font-display text-3xl font-extrabold text-emerald-600">Free</p>
                  <p className="text-sm font-semibold text-ink-800 mt-2">Browsing &amp; bidding</p>
                  <p className="text-xs text-ink-500 mt-1 leading-relaxed">
                    Creating an account, watching and bidding never cost anything.
                  </p>
                </div>
              </Reveal>
              <Reveal delay={130}>
                <div className="card card-hover h-full p-6">
                  <p className="font-display text-3xl font-extrabold text-primary-600">
                    {stats.fees.commissionPercent}%
                  </p>
                  <p className="text-sm font-semibold text-ink-800 mt-2">Commission on sales</p>
                  <p className="text-xs text-ink-500 mt-1 leading-relaxed">
                    Sellers pay a small commission only when an item actually sells.
                  </p>
                </div>
              </Reveal>
              <Reveal delay={200}>
                <div className="card card-hover h-full p-6">
                  <p className="font-display text-3xl font-extrabold text-accent-600">
                    ${Number(stats.fees.featuredListingFee).toFixed(2)}
                  </p>
                  <p className="text-sm font-semibold text-ink-800 mt-2">Featured listing (optional)</p>
                  <p className="text-xs text-ink-500 mt-1 leading-relaxed">
                    Promote a listing to the front page for extra visibility.
                  </p>
                </div>
              </Reveal>
            </div>
          </Reveal>
        )}

        {/* Testimonials — real buyer reviews pulled from the reviews table */}
        {stats?.testimonials?.length > 0 && (
          <Reveal as="section">
            <SectionHeader
              icon={Quote}
              title={c('section.testimonials.title', 'What Buyers Say')}
              subtitle={c('section.testimonials.subtitle', 'Real reviews left by buyers after completed orders.')}
            />
            <div className="grid md:grid-cols-3 gap-4">
              {stats.testimonials.map((t, i) => (
                <Reveal key={i} delay={60 + i * 70}>
                  <figure className="card card-hover h-full p-6 flex flex-col">
                    <div className="flex items-center gap-0.5 mb-3">
                      {Array.from({ length: 5 }, (_, s) => (
                        <Star
                          key={s}
                          size={14}
                          className={s < t.rating ? 'text-amber-400 fill-amber-400' : 'text-ink-200'}
                        />
                      ))}
                    </div>
                    <blockquote className="text-sm text-ink-700 leading-relaxed flex-1">
                      “{decodeHtmlEntities(t.comment)}”
                    </blockquote>
                    <figcaption className="text-xs text-ink-400 mt-4">
                      <span className="font-semibold text-ink-600">{t.reviewerName}</span>
                      {t.auctionTitle && <> · bought “{t.auctionTitle}”</>}
                    </figcaption>
                  </figure>
                </Reveal>
              ))}
            </div>
          </Reveal>
        )}

        {/* Guest sign-up band */}
        {!user && (
          <Reveal as="section" className="sheen-host rounded-3xl bg-ink-900 text-white px-8 py-12 text-center">
            <div
              className="absolute inset-0 opacity-80"
              style={{
                backgroundImage:
                  'radial-gradient(32rem 20rem at 20% 0%, #1d4dd8, transparent 60%), radial-gradient(28rem 18rem at 85% 100%, rgba(249,126,7,0.3), transparent 55%)',
              }}
            />
            <div className="relative">
              <h2 className="font-display text-2xl md:text-3xl font-extrabold">
                {c('guest.heading', 'Ready to place your first bid?')}
              </h2>
              <p className="text-sm text-white/65 mt-2 max-w-md mx-auto">
                {c('guest.subtext', 'Join {users} registered users — create a free account to bid, watch and sell.')
                  .replace('{users}', stats?.totalUsers ? Number(stats.totalUsers).toLocaleString() : 'our')}
              </p>
              <div className="flex justify-center gap-3 mt-6">
                <Link
                  to="/register"
                  className="inline-flex items-center gap-2 bg-white text-ink-900 px-6 py-3 rounded-xl font-semibold text-sm shadow-lift hover:bg-ink-100 hover:-translate-y-0.5 transition-all"
                >
                  <UserPlus size={16} /> Create free account
                </Link>
                <Link
                  to="/login"
                  className="inline-flex items-center gap-2 border border-white/25 bg-white/5 px-6 py-3 rounded-xl font-semibold text-sm hover:bg-white/15 hover:border-white/40 hover:-translate-y-0.5 transition-all"
                >
                  Sign in
                </Link>
              </div>
            </div>
          </Reveal>
        )}
      </div>
    </div>
  );
}
