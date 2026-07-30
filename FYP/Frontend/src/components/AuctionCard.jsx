import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { Clock, ImageIcon, Sparkles, ChevronDown, MousePointerClick, Search } from 'lucide-react';
import { formatCurrency, timeRemaining } from '../utils/helpers';
import { publicPath } from '../utils/appBase';
import { getLandingContent } from '../api/auction';
import useNow from '../hooks/useNow';

/** Auctions closing within this window get the urgent (amber) treatment. */
const URGENT_MS = 6 * 60 * 60 * 1000;

const DEFAULT_VIEW_CTA = 'View Auction';
const DEFAULT_ENDED_CTA = 'View Result';

// One shared fetch for every card on the page — /api/landing-content is also
// cached server-side, so this stays cheap even when Home, Search and Detail
// all mount cards.
let landingContentPromise;
function loadLandingContentOnce() {
  if (!landingContentPromise) {
    landingContentPromise = getLandingContent()
      .then(r => r.data ?? {})
      .catch(() => ({}));
  }
  return landingContentPromise;
}

/**
 * Provenance for a recommended card: the short reason inline, the click/keyword
 * evidence behind a toggle so the card never becomes a wall of text.
 *
 * Everything shown here is aggregate or masked — the server never sends the
 * identities behind these numbers to a public page.
 */
function WhyThis({ why }) {
  const [open, setOpen] = useState(false);
  const keywords = why.keywords ?? [];
  const clicks = why.clickCount ?? 0;

  return (
    <div data-why className="mt-2.5 border-t border-ink-100 pt-2.5">
      <button
        type="button"
        onClick={e => { e.preventDefault(); e.stopPropagation(); setOpen(o => !o); }}
        aria-expanded={open}
        className="group/why flex w-full items-start gap-1.5 text-left text-[11px] leading-snug text-ink-500 hover:text-primary-600 transition-colors"
      >
        <Sparkles size={12} className="mt-px shrink-0 text-primary-500" />
        <span className="flex-1 line-clamp-2">{why.reason}</span>
        <ChevronDown
          size={12}
          className={`mt-px shrink-0 transition-transform ${open ? 'rotate-180' : ''}`}
        />
      </button>

      {open && (
        <div className="mt-2 space-y-1.5 rounded-lg bg-ink-50 px-2.5 py-2 text-[11px] text-ink-500">
          <p className="flex items-center gap-1.5">
            <MousePointerClick size={12} className="shrink-0 text-ink-400" />
            {clicks === 0
              ? 'No clicks recorded yet'
              : why.clickedByMasked
                ? `${why.clickedByMasked} and ${why.distinctClickers - 1} other${why.distinctClickers - 1 === 1 ? '' : 's'} clicked this — ${clicks} click${clicks === 1 ? '' : 's'} in total`
                : `Clicked ${clicks} time${clicks === 1 ? '' : 's'}`}
          </p>
          {keywords.length > 0 && (
            <p className="flex items-start gap-1.5">
              <Search size={12} className="mt-px shrink-0 text-ink-400" />
              <span className="flex flex-wrap gap-1">
                {keywords.map(k => (
                  <span key={k} className="rounded bg-white px-1.5 py-0.5 font-medium text-ink-600 ring-1 ring-inset ring-ink-200">
                    {k}
                  </span>
                ))}
              </span>
            </p>
          )}
        </div>
      )}
    </div>
  );
}

export default function AuctionCard({ auction }) {
  const {
    auctionId, title, currentPrice, endDate, thumbnailUrl, sellerUsername, category, why,
  } = auction;

  const [viewCta, setViewCta] = useState(DEFAULT_VIEW_CTA);
  const [endedCta, setEndedCta] = useState(DEFAULT_ENDED_CTA);

  useEffect(() => {
    let cancelled = false;
    loadLandingContentOnce().then(content => {
      if (cancelled) return;
      setViewCta(content['card.cta.viewAuction'] || DEFAULT_VIEW_CTA);
      setEndedCta(content['card.cta.viewResult'] || DEFAULT_ENDED_CTA);
    });
    return () => { cancelled = true; };
  }, []);

  // Ticking clock so the chip counts down (and flips to Ended) in place, without
  // needing a page refresh.
  const now = useNow();
  const msLeft = endDate ? new Date(endDate) - now : null;
  const ended = msLeft != null && msLeft <= 0;
  const urgent = msLeft != null && msLeft > 0 && msLeft < URGENT_MS;
  // Same label for guests and signed-in users — bidding stays on the detail page.
  const ctaLabel = ended ? endedCta : viewCta;

  return (
    <div className="card card-hover group overflow-hidden flex flex-col">
      <Link to={`/auction/${auctionId}`} className="block relative aspect-square bg-ink-100 overflow-hidden">
        {thumbnailUrl ? (
          <img
            src={publicPath(thumbnailUrl)}
            alt={title}
            loading="lazy"
            className="w-full h-full object-cover transition-transform duration-500 group-hover:scale-[1.06]"
          />
        ) : (
          <div className="w-full h-full flex flex-col items-center justify-center gap-1.5 text-ink-300 bg-gradient-to-br from-ink-100 to-ink-200">
            <ImageIcon size={26} />
            <span className="text-xs font-medium">No image</span>
          </div>
        )}

        {/* Time status chip */}
        <span
          className={`absolute top-2.5 left-2.5 inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-[11px] font-semibold backdrop-blur-md shadow-sm ${
            ended
              ? 'bg-ink-900/75 text-white'
              : urgent
                ? 'bg-accent-500/95 text-white'
                : 'bg-white/90 text-ink-700'
          }`}
        >
          <Clock size={11} />
          {ended ? 'Ended' : timeRemaining(endDate, now)}
        </span>

        {category && (
          <span className="absolute top-2.5 right-2.5 rounded-full bg-white/90 backdrop-blur-md px-2.5 py-1 text-[11px] font-semibold text-ink-600 shadow-sm">
            {category}
          </span>
        )}
      </Link>

      <div className="p-4 flex flex-col flex-1">
        <Link to={`/auction/${auctionId}`} className="block">
          <h3 className="font-semibold text-sm text-ink-900 leading-snug line-clamp-2 group-hover:text-primary-600 transition-colors">
            {title}
          </h3>
        </Link>
        {sellerUsername && (
          <p className="text-xs text-ink-400 mt-1 truncate">by {sellerUsername}</p>
        )}

        {why?.reason && <WhyThis why={why} />}

        <div className="mt-auto pt-3">
          <p className="text-[11px] font-semibold uppercase tracking-wide text-ink-400">Current bid</p>
          <p className="text-lg font-bold text-ink-900 tabular-nums">{formatCurrency(currentPrice)}</p>
          <Link
            to={`/auction/${auctionId}`}
            className="btn-primary btn-block mt-3 text-xs uppercase tracking-wide"
          >
            {ctaLabel}
          </Link>
        </div>
      </div>
    </div>
  );
}
