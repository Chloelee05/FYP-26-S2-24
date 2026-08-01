import { useState, useEffect, useCallback } from 'react';
import { useParams, Link } from 'react-router-dom';
import { Heart, Share2, AlertCircle, ChevronLeft, Flag, CheckCircle2, Gavel, MessageCircleQuestion, Lock, Package, Check, Store, LayoutDashboard, LogIn } from 'lucide-react';
import CountdownTimer from '../components/CountdownTimer';
import ReportModal from '../components/ReportModal';
import { getAuctionDetail, getAuctionBids, getAuctionQuestions, placeBid, acceptDutchPrice, buyItNow, setAutoBid, cancelAutoBid, addToWatchlist, removeFromWatchlist, checkWatching, askQuestion, getSellerProfile, getSimilarAuctions } from '../api/auction';
import AuctionCard from '../components/AuctionCard';
import AuctionSellerCard from '../components/AuctionSellerCard';
import { replyToQuestion } from '../api/seller';
import { declareWinner } from '../api/orders';
import { useAuth } from '../context/AuthContext';
import { formatCurrency, decodeHtmlEntities } from '../utils/helpers';
import { publicPath } from '../utils/appBase';
import useNow from '../hooks/useNow';
import usePolling from '../hooks/usePolling';
import { apiErrorMessage } from '../utils/apiError';

/** Inline status line shared by every panel in the right column. */
function Feedback({ message, error }) {
  if (!message && !error) return null;
  return (
    <div className="mb-3">
      {message && (
        <div className="alert-success text-xs py-2">
          <CheckCircle2 size={14} className="mt-0.5 shrink-0" />
          <span>{message}</span>
        </div>
      )}
      {error && (
        <div className="alert-error text-xs py-2">
          <AlertCircle size={14} className="mt-0.5 shrink-0" />
          <span>{error}</span>
        </div>
      )}
    </div>
  );
}

export default function AuctionDetail() {
  const { id } = useParams();
  const { user } = useAuth();
  const [auction, setAuction] = useState(null);
  const [bids, setBids] = useState([]);
  const [questions, setQuestions] = useState([]);
  const [selectedImage, setSelectedImage] = useState(0);
  const [bidAmount, setBidAmount] = useState('');
  const [autoBidMax, setAutoBidMax] = useState('');
  const [autoBidIncrement, setAutoBidIncrement] = useState('50');
  const [myAutoBid, setMyAutoBid] = useState(null);   // active auto-bid from server
  const [autoBidEditing, setAutoBidEditing] = useState(false); // edit mode toggle
  const [question, setQuestion] = useState('');
  const [replyDrafts, setReplyDrafts] = useState({});
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [showReport, setShowReport] = useState(false);
  const [watchedByMe, setWatchedByMe] = useState(false);
  const [sellerProfile, setSellerProfile] = useState(null);
  const [similar, setSimilar] = useState([]);
  const [copied, setCopied] = useState(false);

  // Buying and selling share one account, so buyer affordances are offered to every
  // signed-in member. Whether this particular listing is biddable is decided by
  // auction.isOwner (you cannot bid on your own) rather than by account type.
  const canBuy = Boolean(user) && user.role !== 'ADMIN';
  // Only members have a watchlist, so signed-out visitors and admins always read false
  // rather than needing an effect to reset the flag when the account changes.
  const watched = canBuy && watchedByMe;

  useEffect(() => {
    getAuctionDetail(id).then(r => {
      setAuction(r.data);
      // myAutoBid is injected server-side for the authenticated buyer
      if (r.data?.myAutoBid) {
        setMyAutoBid(r.data.myAutoBid);
      } else {
        setMyAutoBid(null);
      }
      if (r.data?.sellerId) {
        getSellerProfile(r.data.sellerId).then(sp => setSellerProfile(sp.data)).catch(() => {});
      }
    }).catch(() => {});
    getAuctionBids(id).then(r => setBids(r.data.bids ?? [])).catch(() => {});
    getAuctionQuestions(id).then(r => setQuestions(r.data ?? [])).catch(() => {});
    getSimilarAuctions(id, 4).then(r => setSimilar(r.data.results ?? [])).catch(() => setSimilar([]));
  }, [id]);

  // Shared 1s tick so the Dutch descending clock animates smoothly between polls.
  const now = useNow();

  // Real-time price sync via polling (SSE is blocked by Cloudflare on Render).
  // Poll every 4s while the auction is open; stop once it closes so a closed
  // listing left open in a tab does not keep hitting the server forever.
  const isOpen = auction?.open === true;
  const pollAuction = useCallback(async ({ signal }) => {
    const [detail, bidList] = await Promise.allSettled([
      getAuctionDetail(id, { signal }),
      getAuctionBids(id, undefined, { signal }),
    ]);
    if (detail.status === 'fulfilled' && detail.value.data) {
      const next = detail.value.data;
      if (next.myAutoBid !== undefined) setMyAutoBid(next.myAutoBid ?? null);
      setAuction(next);
    }
    if (bidList.status === 'fulfilled') setBids(bidList.value.data.bids ?? []);
  }, [id]);

  usePolling(pollAuction, 4000, isOpen);

  // Reflect whether this auction is already in the buyer's watchlist
  useEffect(() => {
    if (!canBuy) return;
    checkWatching(id)
      .then(r => setWatchedByMe(Boolean(r.data?.watching)))
      .catch(() => {});
  }, [id, canBuy]);

  // Native share sheet where supported (mobile), clipboard copy everywhere else.
  const handleShare = async () => {
    const url = window.location.href;
    try {
      if (navigator.share) {
        await navigator.share({ title: auction?.title ?? 'AuctionHub listing', url });
        return;
      }
      await navigator.clipboard.writeText(url);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // User dismissed the share sheet, or the clipboard is blocked — nothing to report.
    }
  };

  const handleToggleWatch = async () => {
    if (!user) { setError('Please log in to use your watchlist.'); return; }
    if (!canBuy) { setError('Admin accounts cannot use a watchlist.'); return; }
    setError(''); setMessage('');
    try {
      if (watched) {
        await removeFromWatchlist(id);
        setWatchedByMe(false);
        setMessage('Removed from watchlist.');
      } else {
        await addToWatchlist(id);
        setWatchedByMe(true);
        setMessage('Added to watchlist.');
      }
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not update watchlist.'));
    }
  };

  if (!auction) {
    return (
      <div className="max-w-7xl mx-auto px-4 py-8">
        <div className="flex flex-col lg:flex-row gap-8">
          <div className="flex-1 space-y-4">
            <div className="skeleton h-8 w-2/3" />
            <div className="skeleton h-4 w-1/3" />
            <div className="skeleton aspect-video w-full rounded-2xl" />
            <div className="skeleton h-32 w-full rounded-2xl" />
          </div>
          <div className="lg:w-[22rem] space-y-4">
            <div className="skeleton h-44 w-full rounded-2xl" />
            <div className="skeleton h-56 w-full rounded-2xl" />
          </div>
        </div>
      </div>
    );
  }

  const auctionType = auction.auctionType ?? 1; // 1=ascending, 2=dutch, 3=blind
  const isDutch = auctionType === 2;
  const isBlind = auctionType === 3;
  const isStandard = !isDutch && !isBlind;

  // Scheduled (PENDING): start time is still in the future — not the same as ended.
  const isScheduled = auction.startTime && now < new Date(auction.startTime).getTime();

  // Dutch descending clock, computed locally so the price animates between SSE frames.
  const dutchClockPrice = () => {
    const start = Number(auction.startingPrice ?? 0);
    const floor = Number(auction.dutchFloorPrice ?? 0);
    const t0 = auction.startTime ? new Date(auction.startTime).getTime() : null;
    const t1 = auction.endTime ? new Date(auction.endTime).getTime() : null;
    if (t0 == null || t1 == null || t1 <= t0) return start;
    if (now <= t0) return start;
    if (now >= t1) return floor;
    const frac = (now - t0) / (t1 - t0);
    return Math.max(floor, start - (start - floor) * frac);
  };

  const displayPrice = isDutch && auction.open ? dutchClockPrice() : auction.currentBid;
  // Mirrors BidDAO.placeBid: a bid must be strictly greater than the current floor
  // (highest bid, or the starting price when there are none yet). Anything stricter
  // here would reject bids the server would have accepted.
  const bidFloor = auction.currentBid || auction.startingPrice || 0;
  // Smallest amount the server will take, at cent precision.
  const minBid = Math.round((bidFloor + 0.01) * 100) / 100;
  const sealedMinBid = auction.startingPrice || 0;
  const reserveMet = auction.currentBid >= auction.reservePrice;

  const apiError = (err, fallback) => {
    const data = err.response?.data;
    if (typeof data === 'object' && data) return data.error || data.message || fallback;
    if (err.response?.status === 403) return 'Access denied for this action.';
    if (err.response?.status === 401) return 'Please log in to continue.';
    return fallback;
  };

  const handlePlaceBid = async () => {
    if (!user) { setError('Please log in to place a bid.'); return; }
    if (!canBuy) { setError('Admin accounts cannot place bids.'); return; }
    const amount = Number(String(bidAmount).replace(/[^0-9.]/g, ''));
    if (!amount || amount <= 0) { setError('Enter a valid bid amount.'); return; }
    if (amount <= bidFloor) { setError(`Your bid must be higher than ${formatCurrency(bidFloor)}.`); return; }
    setError(''); setMessage('');
    try {
      await placeBid(id, amount);
      setMessage('Bid placed successfully!');
      setBidAmount('');
      getAuctionDetail(id).then(r => setAuction(r.data)).catch(() => {});
      getAuctionBids(id).then(r => setBids(r.data.bids ?? [])).catch(() => {});
    } catch (err) {
      setError(apiError(err, 'Failed to place bid.'));
    }
  };

  const handleAutoBid = async () => {
    if (!user) { setError('Please log in to enable auto-bid.'); return; }
    if (!canBuy) { setError('Admin accounts cannot set auto-bid.'); return; }
    setError(''); setMessage('');
    try {
      await setAutoBid(id, autoBidMax, null, autoBidIncrement);
      const inc = parseFloat(autoBidIncrement) || 50;
      setMyAutoBid({ enabled: true, maxAmount: parseFloat(autoBidMax), bidIncrement: inc });
      setAutoBidEditing(false);
      setMessage('Auto-bid enabled!');
    } catch (err) {
      setError(apiErrorMessage(err, 'Failed to enable auto-bid.'));
    }
  };

  const handleCancelAutoBid = async () => {
    if (!window.confirm('Cancel your auto-bid for this auction?')) return;
    setError(''); setMessage('');
    try {
      await cancelAutoBid(id);
      setMyAutoBid(null);
      setMessage('Auto-bid cancelled.');
    } catch (err) {
      setError(apiErrorMessage(err, 'Failed to cancel auto-bid.'));
    }
  };

  const handleAcceptDutch = async () => {
    if (!user) { setError('Please log in to accept this price.'); return; }
    if (!canBuy) { setError('Admin accounts cannot accept a Dutch price.'); return; }
    setError(''); setMessage('');
    try {
      await acceptDutchPrice(id);
      setMessage('You accepted the current price and won this auction!');
      getAuctionDetail(id).then(r => setAuction(r.data)).catch(() => {});
      getAuctionBids(id).then(r => setBids(r.data.bids ?? [])).catch(() => {});
    } catch (err) {
      setError(apiError(err, 'Could not accept the current price.'));
    }
  };

  const handleBuyItNow = async () => {
    if (!user) { setError('Please log in to Buy It Now.'); return; }
    if (!canBuy) { setError('Admin accounts cannot use Buy It Now.'); return; }
    if (!window.confirm(`Buy this item now for ${formatCurrency(auction.buyItNowPrice)}?`)) return;
    setError(''); setMessage('');
    try {
      await buyItNow(id);
      setMessage('Buy It Now successful — you won this auction!');
      getAuctionDetail(id).then(r => setAuction(r.data)).catch(() => {});
      getAuctionBids(id).then(r => setBids(r.data.bids ?? [])).catch(() => {});
    } catch (err) {
      setError(apiError(err, 'Could not complete Buy It Now.'));
    }
  };

  const handleDeclareWinner = async (early = false) => {
    setError(''); setMessage('');
    if (early && !window.confirm('End this auction now and declare the current highest bidder as winner?')) return;
    try {
      await declareWinner(id, early);
      setMessage(early
        ? 'Winner declared early. An order was created and the buyer was notified.'
        : 'Winner declared. An order was created and the buyer was notified.');
      getAuctionDetail(id).then(r => setAuction(r.data)).catch(() => {});
    } catch (err) {
      setError(apiError(err, 'Could not declare a winner.'));
    }
  };

  const handleSealedBid = async () => {
    if (!user) { setError('Please log in to submit a sealed bid.'); return; }
    if (!canBuy) { setError('Admin accounts cannot submit a sealed bid.'); return; }
    const amount = Number(String(bidAmount).replace(/[^0-9.]/g, ''));
    if (!amount || amount <= 0) { setError('Enter a valid bid amount.'); return; }
    if (amount < sealedMinBid) { setError(`Your sealed bid must be at least ${formatCurrency(sealedMinBid)}.`); return; }
    setError(''); setMessage('');
    try {
      await placeBid(id, amount);
      setMessage('Your sealed bid was submitted. The winner is revealed when the auction ends.');
      setBidAmount('');
      getAuctionDetail(id).then(r => setAuction(r.data)).catch(() => {});
    } catch (err) {
      setError(apiError(err, 'Failed to submit sealed bid.'));
    }
  };

  const handleAskQuestion = async (e) => {
    e.preventDefault();
    if (!user) { setError('Please log in to ask a question.'); return; }
    if (!canBuy) { setError('Admin accounts cannot ask questions.'); return; }
    if (!question.trim()) { setError('Enter a question first.'); return; }
    setError(''); setMessage('');
    try {
      await askQuestion(id, question);
      setQuestion('');
      setMessage('Question submitted!');
      getAuctionQuestions(id).then(r => setQuestions(r.data ?? [])).catch(() => {});
    } catch (err) {
      setError(apiError(err, 'Failed to submit question.'));
    }
  };

  const handleReply = async (questionId) => {
    const text = (replyDrafts[questionId] ?? '').trim();
    if (!text) { setError('Enter a reply first.'); return; }
    setError(''); setMessage('');
    try {
      await replyToQuestion(questionId, text);
      setReplyDrafts(d => ({ ...d, [questionId]: '' }));
      setMessage('Reply posted!');
      getAuctionQuestions(id).then(r => setQuestions(r.data ?? [])).catch(() => {});
    } catch (err) {
      setError(apiError(err, 'Failed to post reply.'));
    }
  };

  // Replying to questions is ownership-based server-side (QuestionDAO checks the
  // auction's seller_id), so match that here rather than testing a role — a merged
  // buyer/seller account keeps the BUYER role while selling.
  const isListingSeller = Boolean(user) && Number(user.id) === Number(auction.sellerId);

  const typeBadgeClass = isDutch ? 'badge-accent' : isBlind ? 'badge-purple' : 'badge-info';

  return (
    <div className="max-w-7xl mx-auto px-4 py-6">
      {showReport && (
        <ReportModal
          auctionId={id}
          auctionTitle={auction.title}
          onClose={() => setShowReport(false)}
        />
      )}

      <Link to="/search" className="inline-flex items-center gap-1 text-sm font-medium text-ink-500 hover:text-primary-600 transition-colors mb-5">
        <ChevronLeft size={16} /> Back to auctions
      </Link>

      <div className="flex flex-col lg:flex-row gap-8">
        {/* Left: Images + Info */}
        <div className="flex-1 min-w-0">
          <div className="flex items-start justify-between gap-4 mb-4">
            <div className="min-w-0">
              <div className="flex items-center gap-2 flex-wrap mb-2">
                <span className={typeBadgeClass}>{auction.auctionTypeName ?? 'Standard (Ascending)'}</span>
                {auction.category && <span className="badge-neutral">{auction.category}</span>}
                {auction.condition && <span className="badge-neutral">{auction.condition}</span>}
                {auction.quantity > 1 && <span className="badge-neutral">Qty {auction.quantity}</span>}
              </div>
              <h1 className="font-display text-2xl sm:text-3xl font-bold text-ink-900 leading-tight">{auction.title}</h1>
              <div className="flex items-center gap-x-3 gap-y-1 text-sm text-ink-500 mt-2 flex-wrap">
                <Link to={`/seller/${auction.sellerId}`} className="link-subtle">
                  Sold by {auction.seller}
                </Link>
                {auction.costPrice != null && (
                  <span className="text-ink-400">Your cost: {formatCurrency(auction.costPrice)}</span>
                )}
              </div>
              {auction.tags?.length > 0 && (
                <div className="flex flex-wrap gap-1.5 mt-3">
                  {auction.tags.map(tag => (
                    <span key={tag.id} className="badge-info">{tag.name}</span>
                  ))}
                </div>
              )}
            </div>

            <div className="flex gap-2 shrink-0">
              {/* Guests browse read-only: no watchlist control until they sign in.
                  Watchlisting your own listing is rejected server-side (OWN_AUCTION). */}
              {user && !auction.isOwner && (
                <button
                  onClick={handleToggleWatch}
                  title={watched ? 'Remove from watchlist' : 'Add to watchlist'}
                  className={`p-2.5 rounded-xl border transition-all ${
                    watched ? 'border-red-200 bg-red-50 hover:bg-red-100' : 'border-ink-200 bg-white hover:bg-ink-50 hover:border-ink-300'
                  }`}
                >
                  <Heart size={18} className={watched ? 'text-red-500 fill-red-500' : 'text-ink-400'} />
                </button>
              )}
              <button
                onClick={handleShare}
                className="p-2.5 rounded-xl border border-ink-200 bg-white hover:bg-ink-50 hover:border-ink-300 transition-all"
                title={copied ? 'Link copied' : 'Share this auction'}
              >
                {copied
                  ? <Check size={18} className="text-emerald-600" />
                  : <Share2 size={18} className="text-ink-400" />}
              </button>
              {canBuy && !auction.isOwner && (
                <button
                  onClick={() => setShowReport(true)}
                  className="p-2.5 rounded-xl border border-ink-200 bg-white hover:bg-red-50 hover:border-red-200 transition-all group"
                  title="Report this auction"
                >
                  <Flag size={18} className="text-ink-400 group-hover:text-red-500" />
                </button>
              )}
            </div>
          </div>

          {/* Image gallery */}
          <div className="card overflow-hidden mb-4">
            <div className="bg-ink-100 aspect-video flex items-center justify-center text-ink-400">
              {auction.images?.[selectedImage]
                ? <img src={publicPath(auction.images[selectedImage])} alt={auction.title} className="w-full h-full object-contain" />
                : (
                  <div className="flex flex-col items-center gap-2">
                    <Package size={32} />
                    <span className="text-sm font-medium">{auction.title.split(' ').slice(0, 2).join(' ')}</span>
                  </div>
                )
              }
            </div>
            {auction.images?.length > 1 && (
              <div className="flex gap-2 p-3 overflow-x-auto border-t border-ink-100">
                {auction.images.map((img, i) => (
                  <button
                    key={i}
                    onClick={() => setSelectedImage(i)}
                    className={`w-20 h-16 shrink-0 rounded-lg overflow-hidden border-2 transition-all ${
                      selectedImage === i ? 'border-primary-500 ring-2 ring-primary-500/20' : 'border-ink-200 hover:border-ink-300 opacity-70 hover:opacity-100'
                    }`}
                  >
                    <img src={publicPath(img)} alt="" className="w-full h-full object-contain bg-ink-50 p-1" />
                  </button>
                ))}
              </div>
            )}
          </div>

          <AuctionSellerCard seller={sellerProfile} />

          {/* Description */}
          <div className="card p-6 mb-4">
            <h3 className="section-title text-base mb-3">Description</h3>
            <p className="text-ink-600 text-sm leading-relaxed whitespace-pre-line">{auction.description}</p>
          </div>

          {/* Bid History */}
          <div className="card p-6">
            <h3 className="section-title text-base mb-4 flex items-center gap-2">
              <Gavel size={18} className="text-primary-600" /> Bid History
              <span className="badge-neutral ml-1">{bids.length}</span>
            </h3>
            {bids.length === 0 ? (
              <p className="text-sm text-ink-400">No bids yet — be the first to bid.</p>
            ) : (
              <div className="space-y-2">
                {(() => {
                  let shownCurrent = false;
                  return bids.map((bid, i) => {
                  const isCurrentLeader = bid.currentLeader === true && !shownCurrent;
                  if (bid.currentLeader === true && !shownCurrent) shownCurrent = true;
                  const isSelf = bid.self === true;
                  return (
                    <div
                      key={i}
                      className={`flex items-center justify-between gap-3 p-3 rounded-xl ring-1 ring-inset transition-colors ${
                        isCurrentLeader
                          ? 'bg-emerald-50 ring-emerald-200'
                          : isSelf
                            ? 'bg-primary-50 ring-primary-100'
                            : 'bg-ink-50 ring-transparent'
                      }`}
                    >
                      <div className="min-w-0">
                        <div className="flex items-center gap-1.5 flex-wrap mb-0.5">
                          {isCurrentLeader && <span className="badge-success">Leading</span>}
                          {isSelf && <span className="badge-info">You</span>}
                          <span className="font-semibold text-sm text-ink-800 truncate">{bid.maskedBidderName}</span>
                        </div>
                        <p className="text-xs text-ink-400">{new Date(bid.bidTime).toLocaleString()}</p>
                      </div>
                      <span className={`font-bold tabular-nums shrink-0 ${isCurrentLeader ? 'text-emerald-600' : 'text-ink-800'}`}>
                        {formatCurrency(bid.bidAmount)}
                      </span>
                    </div>
                  );
                });
                })()}
              </div>
            )}
          </div>

          {/* Q&A */}
          <div className="card p-6 mt-4">
            <h3 className="section-title text-base mb-4 flex items-center gap-2">
              <MessageCircleQuestion size={18} className="text-primary-600" /> Questions &amp; Answers
            </h3>
            <Feedback message={message} error={error} />
            {questions.length === 0 && (
              <p className="text-sm text-ink-400 mb-4">No questions yet.</p>
            )}
            <div className="space-y-3">
              {questions.map((q) => (
                <div key={q.id} className="surface-muted p-4">
                  <p className="text-sm text-ink-800">
                    <span className="font-semibold">{q.askerUsername ?? q.buyerUsername ?? 'Buyer'}</span>
                    <span className="text-ink-400"> asked: </span>
                    {decodeHtmlEntities(q.questionText)}
                  </p>
                  {q.answerText || q.replyText ? (
                    <p className="text-sm text-ink-700 mt-2.5 pl-3 border-l-2 border-primary-300">
                      <span className="font-semibold text-primary-700">Seller:</span>{' '}
                      {decodeHtmlEntities(q.answerText || q.replyText)}
                    </p>
                  ) : isListingSeller ? (
                    <form
                      onSubmit={(e) => { e.preventDefault(); handleReply(q.id); }}
                      className="flex gap-2 mt-3"
                    >
                      <input
                        value={replyDrafts[q.id] ?? ''}
                        onChange={e => setReplyDrafts(d => ({ ...d, [q.id]: e.target.value }))}
                        placeholder="Write a reply to this question…"
                        className="input-field"
                      />
                      <button type="submit" className="btn-primary shrink-0">Reply</button>
                    </form>
                  ) : (
                    <p className="text-xs text-ink-400 mt-2">Awaiting seller reply…</p>
                  )}
                </div>
              ))}
            </div>
            {canBuy && !auction.isOwner && (
              <form onSubmit={handleAskQuestion} className="flex gap-2 mt-4">
                <input
                  value={question}
                  onChange={e => setQuestion(e.target.value)}
                  placeholder="Ask a question about this item…"
                  className="input-field"
                />
                <button type="submit" className="btn-primary shrink-0">Ask</button>
              </form>
            )}
          </div>
        </div>

        {/* Right: Bidding Panel */}
        <div className="lg:w-[22rem] shrink-0">
          <div className="space-y-4 lg:sticky lg:top-24">
            {/* Current price / status */}
            <div className="card p-6">
              <div className="flex items-center justify-between mb-1.5">
                <span className="eyebrow">
                  {isDutch ? (auction.open ? 'Current Price' : 'Final Price')
                    : isBlind ? (auction.open ? (auction.isOwner ? 'Highest Sealed Bid' : 'Sealed Bids') : 'Winning Bid')
                    : 'Current Bid'}
                </span>
                {!(isDutch && auction.open) && (
                  <span className="badge-neutral">{auction.numBids} bids</span>
                )}
              </div>
              {/* Sealed to buyers while the auction runs. The seller sees the standing
                  bid, since early close sells at exactly that amount. */}
              {isBlind && auction.open && !auction.isOwner ? (
                <div className="flex items-center gap-2 text-2xl font-bold text-purple-600 mb-3">
                  <Lock size={22} /> Hidden until close
                </div>
              ) : isBlind && auction.open && !auction.numBids ? (
                <div className="text-2xl font-bold text-ink-400 mb-3">No sealed bids yet</div>
              ) : (
                <div className={`font-display text-4xl font-extrabold mb-3 tabular-nums ${isDutch && auction.open ? 'text-accent-600' : 'text-emerald-600'}`}>
                  {formatCurrency(displayPrice)}
                </div>
              )}
              {isBlind && auction.open && auction.isOwner && (auction.numBids ?? 0) > 0 && (
                <p className="flex items-center gap-1.5 -mt-1 mb-3 text-xs text-ink-500">
                  <Lock size={12} className="shrink-0" /> Only you can see this — buyers see “Hidden until close”.
                </p>
              )}
              <div className="pt-3 border-t border-ink-100">
                <CountdownTimer endTime={auction.endTime} />
              </div>
              {isStandard && auction.reservePrice != null && !reserveMet && (
                <div className="alert-warning text-xs mt-3 py-2">
                  <AlertCircle size={14} className="mt-0.5 shrink-0" />
                  <span>Reserve not met ({formatCurrency(auction.reservePrice)})</span>
                </div>
              )}
              {isDutch && auction.open && (
                <p className="text-xs text-ink-500 mt-3 leading-relaxed">
                  Price falls toward {formatCurrency(auction.dutchFloorPrice)}. Accept now to win instantly.
                </p>
              )}
            </div>

            {auction.isOwner && auction.open && (auction.numBids ?? 0) > 0 && (
              <div className="card p-5 border-amber-200 bg-amber-50">
                <p className="font-bold text-amber-900 text-sm mb-2">Seller: early close</p>
                <Feedback message={message} error={error} />
                <button
                  onClick={() => handleDeclareWinner(true)}
                  className="btn btn-block bg-amber-600 text-white hover:bg-amber-700 shadow-sm"
                >
                  Declare Winner Early
                </button>
                <p className="text-xs text-amber-800/80 mt-2.5">Ends the auction now and sells to the current highest bidder.</p>
              </div>
            )}

            {!auction.open ? (
              isScheduled ? (
                <div className="card p-6 text-center">
                  <p className="font-semibold text-accent-600 mb-1">This auction hasn’t started yet.</p>
                  <p className="text-xs text-ink-400">
                    Bidding opens {new Date(auction.startTime).toLocaleString()}
                  </p>
                </div>
              ) : (
              <div className="card p-6 text-center">
                <p className="text-sm text-ink-500 mb-4">This auction has ended.</p>
                {/* Declaring is one-shot: once the order exists the button can only
                    fail, so it is replaced by a pointer to the order itself. */}
                {auction.isOwner && (auction.orderCreated ? (
                  <>
                    <Feedback message={message} error={error} />
                    <p className="flex items-center justify-center gap-1.5 text-sm font-semibold text-emerald-600">
                      <CheckCircle2 size={15} /> Winner declared
                    </p>
                    <Link to="/sales" className="btn-secondary btn-block mt-3">
                      View in My sales
                    </Link>
                  </>
                ) : (
                  <>
                    <Feedback message={message} error={error} />
                    <button onClick={() => handleDeclareWinner(false)} className="btn-dark btn-block">
                      Declare Winner &amp; Create Order
                    </button>
                    <p className="text-xs text-ink-400 mt-2.5">Finalises the sale to the highest bidder.</p>
                  </>
                ))}
              </div>
              )
            ) : !user ? (
              /* Guests browse read-only (assessor feedback): no bid / auto-bid /
                 sealed-bid forms — just a clear path to register or sign in. */
              <div className="card p-6">
                <div className="grid place-items-center w-12 h-12 rounded-2xl bg-primary-50 text-primary-600 mb-4">
                  <LogIn size={22} />
                </div>
                <h3 className="section-title text-base mb-2">Sign in to bid</h3>
                <p className="text-sm text-ink-500 leading-relaxed mb-4">
                  Watching is free — bidding needs an account. Create one in under a
                  minute to place bids, set auto-bids and build a watchlist.
                </p>
                <div className="flex flex-col gap-2">
                  <Link to="/register" className="btn-primary btn-block">
                    Create free account
                  </Link>
                  <Link to="/login" className="btn-secondary btn-block">
                    <LogIn size={16} /> Sign in
                  </Link>
                </div>
              </div>
            ) : auction.isOwner ? (
              /* Your own listing — the server rejects self-bids, so don't offer the form. */
              <div className="card p-6">
                <div className="grid place-items-center w-12 h-12 rounded-2xl bg-primary-50 text-primary-600 mb-4">
                  <Store size={22} />
                </div>
                <h3 className="section-title text-base mb-2">This is your listing</h3>
                <p className="text-sm text-ink-500 leading-relaxed mb-4">
                  You can’t bid on an auction you created. Watch the bids come in here, or
                  manage the listing from your seller dashboard.
                </p>
                <div className="flex flex-col gap-2">
                  <Link to="/seller/dashboard" className="btn-secondary btn-block">
                    <LayoutDashboard size={16} /> Seller dashboard
                  </Link>
                  <Link to={`/seller/auction/${id}/edit`} className="btn-ghost btn-block">
                    Edit listing
                  </Link>
                </div>
              </div>
            ) : isStandard ? (
              <>
                {/* Place Bid (ascending) */}
                <div className="card p-6">
                  <h3 className="section-title text-base mb-1">Place Bid</h3>
                  <p className="text-xs text-ink-500 mb-3">
                    Must be more than {formatCurrency(bidFloor)}
                  </p>
                  <div className="relative mb-3">
                    <span className="absolute left-4 top-1/2 -translate-y-1/2 text-ink-400 text-sm font-medium">$</span>
                    <input
                      type="number"
                      value={bidAmount}
                      onChange={e => setBidAmount(e.target.value)}
                      placeholder={minBid}
                      className="input-field pl-8 text-base font-semibold tabular-nums"
                    />
                  </div>
                  <div className="grid grid-cols-3 gap-2 mb-4">
                    {[50, 100, 250].map(inc => (
                      <button
                        key={inc}
                        onClick={() => setBidAmount(String((auction.currentBid || 0) + inc))}
                        className="rounded-lg border border-ink-200 bg-white px-2 py-1.5 text-xs font-semibold text-ink-600
                                   hover:border-primary-300 hover:text-primary-600 hover:bg-primary-50 transition-colors"
                      >
                        +${inc}
                      </button>
                    ))}
                  </div>
                  <Feedback message={message} error={error} />
                  <button onClick={handlePlaceBid} className="btn-primary btn-block btn-lg">
                    <Gavel size={16} /> Place Bid
                  </button>
                </div>

                {auction.buyItNowPrice != null && Number(auction.buyItNowPrice) > 0 && canBuy && (
                  <div className="card p-6 border-emerald-200 bg-emerald-50/60">
                    <h3 className="section-title text-base mb-1">Buy It Now</h3>
                    <p className="text-3xl font-bold text-emerald-600 mb-2 tabular-nums">{formatCurrency(auction.buyItNowPrice)}</p>
                    <p className="text-xs text-ink-500 mb-4">Purchase immediately at this price and win the auction.</p>
                    <Feedback message={message} error={error} />
                    <button onClick={handleBuyItNow} className="btn-success btn-block btn-lg">
                      Buy It Now
                    </button>
                  </div>
                )}

                {/* Auto-Bid */}
                <div className="card p-6">
                  <h3 className="section-title text-base mb-3">Auto-Bid</h3>

                  {myAutoBid && !autoBidEditing ? (
                    /* Active auto-bid: show summary + Cancel / Edit buttons */
                    <div>
                      <div className="rounded-xl bg-purple-50 ring-1 ring-inset ring-purple-200 p-4 mb-4">
                        <p className="text-xs font-bold uppercase tracking-wide text-purple-600 mb-2">Auto-Bid Active</p>
                        <div className="flex justify-between text-sm text-ink-700">
                          <span>Max bid</span>
                          <span className="font-bold text-purple-700 tabular-nums">{formatCurrency(myAutoBid.maxAmount)}</span>
                        </div>
                        <div className="flex justify-between text-sm text-ink-700 mt-1">
                          <span>Bid increment</span>
                          <span className="font-semibold tabular-nums">{formatCurrency(myAutoBid.bidIncrement)}</span>
                        </div>
                      </div>
                      <Feedback message={message} error={error} />
                      <div className="flex gap-2">
                        <button
                          onClick={() => {
                            setAutoBidMax(String(myAutoBid.maxAmount));
                            setAutoBidIncrement(String(myAutoBid.bidIncrement));
                            setAutoBidEditing(true);
                          }}
                          className="btn flex-1 border border-purple-300 text-purple-700 bg-white hover:bg-purple-50"
                        >
                          Edit
                        </button>
                        <button
                          onClick={handleCancelAutoBid}
                          className="btn flex-1 border border-red-200 text-red-600 bg-white hover:bg-red-50"
                        >
                          Cancel
                        </button>
                      </div>
                    </div>
                  ) : (
                    /* No active auto-bid or editing mode: show the form */
                    <div>
                      <p className="text-xs text-ink-500 mb-4">
                        {autoBidEditing ? 'Update your auto-bid settings below.' : 'Set a maximum bid and let the system bid for you automatically.'}
                      </p>
                      <label className="field-label">Maximum bid</label>
                      <div className="relative mb-3">
                        <span className="absolute left-4 top-1/2 -translate-y-1/2 text-ink-400 text-sm font-medium">$</span>
                        <input
                          type="number"
                          value={autoBidMax}
                          onChange={e => setAutoBidMax(e.target.value)}
                          placeholder="2500"
                          className="input-field pl-8 tabular-nums"
                        />
                      </div>
                      <label className="field-label">Bid increment</label>
                      <div className="relative mb-4">
                        <span className="absolute left-4 top-1/2 -translate-y-1/2 text-ink-400 text-sm font-medium">$</span>
                        <input
                          type="number"
                          value={autoBidIncrement}
                          onChange={e => setAutoBidIncrement(e.target.value)}
                          className="input-field pl-8 tabular-nums"
                        />
                      </div>
                      <Feedback message={message} error={error} />
                      <div className="flex gap-2">
                        {autoBidEditing && (
                          <button onClick={() => setAutoBidEditing(false)} className="btn-secondary flex-1">
                            Back
                          </button>
                        )}
                        <button
                          onClick={handleAutoBid}
                          className="btn flex-1 bg-purple-600 text-white hover:bg-purple-700 shadow-sm"
                        >
                          {autoBidEditing ? 'Update' : 'Enable Auto-Bid'}
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              </>
            ) : isDutch ? (
              /* Dutch: accept current clock price */
              <div className="card p-6">
                <h3 className="section-title text-base mb-2">Buy at Current Price</h3>
                <p className="text-xs text-ink-500 mb-4 leading-relaxed">
                  The first buyer to accept wins immediately at the displayed price.
                </p>
                <Feedback message={message} error={error} />
                <button
                  onClick={handleAcceptDutch}
                  className="btn btn-block btn-lg bg-accent-500 text-white hover:bg-accent-600 shadow-sm"
                >
                  Accept {formatCurrency(displayPrice)}
                </button>
              </div>
            ) : auction.mySealedBid ? (
              /* Blind: sealed bid already submitted */
              <div className="card p-6">
                <h3 className="section-title text-base mb-3">Sealed Bid Submitted</h3>
                <div className="rounded-xl bg-purple-50 ring-1 ring-inset ring-purple-200 px-4 py-3 mb-3">
                  <p className="text-sm text-purple-800 font-semibold flex items-center gap-1.5">
                    <CheckCircle2 size={15} /> Your sealed bid is in.
                  </p>
                  {auction.mySealedBidAmount != null && (
                    <p className="text-xs text-purple-600 mt-1">
                      Your bid: {formatCurrency(auction.mySealedBidAmount)}
                    </p>
                  )}
                </div>
                <p className="text-xs text-ink-500 leading-relaxed">
                  One hidden bid per buyer. All bids stay secret until the auction closes, when the winner is revealed.
                </p>
              </div>
            ) : (
              /* Blind: submit one sealed bid */
              <div className="card p-6">
                <h3 className="section-title text-base mb-2">Submit Sealed Bid</h3>
                <p className="text-xs text-ink-500 mb-3 leading-relaxed">
                  One hidden bid per buyer (min {formatCurrency(sealedMinBid)}). Amounts stay secret until close.
                </p>
                <div className="relative mb-4">
                  <span className="absolute left-4 top-1/2 -translate-y-1/2 text-ink-400 text-sm font-medium">$</span>
                  <input
                    type="number"
                    value={bidAmount}
                    onChange={e => setBidAmount(e.target.value)}
                    placeholder={sealedMinBid}
                    className="input-field pl-8 text-base font-semibold tabular-nums"
                  />
                </div>
                <Feedback message={message} error={error} />
                <button
                  onClick={handleSealedBid}
                  className="btn btn-block btn-lg bg-purple-600 text-white hover:bg-purple-700 shadow-sm"
                >
                  <Lock size={16} /> Submit Sealed Bid
                </button>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Buyers who bid on this also bid on… (collaborative similarity) */}
      {similar.length > 0 && (
        <div className="pt-14">
          <h2 className="section-title">Bidders Also Bid On</h2>
          <p className="text-sm text-ink-500 mt-1 mb-6">Live auctions popular with buyers interested in this item.</p>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4 sm:gap-5">
            {similar.map(a => <AuctionCard key={`sim-${a.auctionId}`} auction={a} />)}
          </div>
        </div>
      )}
    </div>
  );
}
