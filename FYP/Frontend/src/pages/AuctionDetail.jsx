import { useState, useEffect } from 'react';
import { useParams, Link } from 'react-router-dom';
import { Heart, Share2, AlertCircle, ChevronLeft, Flag } from 'lucide-react';
import CountdownTimer from '../components/CountdownTimer';
import ReportModal from '../components/ReportModal';
import { getAuctionDetail, getAuctionBids, getAuctionQuestions, placeBid, acceptDutchPrice, setAutoBid, cancelAutoBid, addToWatchlist, removeFromWatchlist, getWatchlist, askQuestion, getSellerProfile } from '../api/auction';
import AuctionSellerCard from '../components/AuctionSellerCard';
import { replyToQuestion } from '../api/seller';
import { declareWinner } from '../api/orders';
import { useAuth } from '../context/AuthContext';
import { formatCurrency, decodeHtmlEntities } from '../utils/helpers';
import { appBase, publicPath } from '../utils/appBase';

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
  const [watched, setWatched] = useState(false);
  const [now, setNow] = useState(Date.now());
  const [sellerProfile, setSellerProfile] = useState(null);

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
  }, [id]);

  // Local 1s tick so the Dutch descending clock animates smoothly between SSE frames.
  useEffect(() => {
    if (auction?.auctionType !== 2 || !auction?.open) return;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [auction?.auctionType, auction?.open]);

  // Real-time price sync: other buyers' bids update this screen live over SSE.
  useEffect(() => {
    const es = new EventSource(`${appBase()}/api/auction-events/${id}`);
    es.addEventListener('bid', (e) => {
      try {
        const data = JSON.parse(e.data);
        setAuction(prev => prev ? {
          ...prev,
          currentBid: data.currentBid != null ? data.currentBid : prev.currentBid,
          numBids: data.numBids != null ? data.numBids : prev.numBids,
          open: data.open != null ? data.open : prev.open,
        } : prev);
        getAuctionBids(id).then(r => setBids(r.data.bids ?? [])).catch(() => {});
      } catch { /* ignore malformed frame */ }
    });
    return () => es.close();
  }, [id]);

  // Reflect whether this auction is already in the buyer's watchlist
  useEffect(() => {
    if (user?.role !== 'BUYER') { setWatched(false); return; }
    getWatchlist()
      .then(r => {
        const ids = (r.data ?? []).map(w => String(w.auctionId));
        setWatched(ids.includes(String(id)));
      })
      .catch(() => {});
  }, [id, user]);

  const handleToggleWatch = async () => {
    if (!user) { setError('Please log in to use your watchlist.'); return; }
    if (user.role !== 'BUYER') { setError('Only buyers can add items to a watchlist.'); return; }
    setError(''); setMessage('');
    try {
      if (watched) {
        await removeFromWatchlist(id);
        setWatched(false);
        setMessage('Removed from watchlist.');
      } else {
        await addToWatchlist(id);
        setWatched(true);
        setMessage('Added to watchlist.');
      }
    } catch (err) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Could not update watchlist.');
    }
  };

  if (!auction) {
    return <div className="max-w-7xl mx-auto px-4 py-16 text-center text-gray-400">Loading auction…</div>;
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
  const minBid = (auction.currentBid || auction.startingPrice || 0) + 50;
  const sealedMinBid = auction.startingPrice || 0;
  const reserveMet = auction.currentBid >= auction.reservePrice;

  const apiError = (err, fallback) => {
    const data = err.response?.data;
    if (typeof data === 'object' && data) return data.error || data.message || fallback;
    if (err.response?.status === 403) return 'Access denied. Use a buyer account for this action.';
    if (err.response?.status === 401) return 'Please log in to continue.';
    return fallback;
  };

  const handlePlaceBid = async () => {
    if (!user) { setError('Please log in to place a bid.'); return; }
    if (user.role !== 'BUYER') { setError('Only buyers can place bids. Switch to a buyer account.'); return; }
    const amount = Number(String(bidAmount).replace(/[^0-9.]/g, ''));
    if (!amount || amount <= 0) { setError('Enter a valid bid amount.'); return; }
    if (amount < minBid) { setError(`Your bid must be at least ${formatCurrency(minBid)}.`); return; }
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
    if (user.role !== 'BUYER') { setError('Only buyers can set auto-bid.'); return; }
    setError(''); setMessage('');
    try {
      await setAutoBid(id, autoBidMax, null, autoBidIncrement);
      const inc = parseFloat(autoBidIncrement) || 50;
      setMyAutoBid({ enabled: true, maxAmount: parseFloat(autoBidMax), bidIncrement: inc });
      setAutoBidEditing(false);
      setMessage('Auto-bid enabled!');
    } catch (err) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Failed to enable auto-bid.');
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
      setError(err.response?.data?.error || err.response?.data?.message || 'Failed to cancel auto-bid.');
    }
  };

  const handleAcceptDutch = async () => {
    if (!user) { setError('Please log in to accept this price.'); return; }
    if (user.role !== 'BUYER') { setError('Only buyers can accept a Dutch price.'); return; }
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
    if (user.role !== 'BUYER') { setError('Only buyers can submit a sealed bid.'); return; }
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
    if (user.role !== 'BUYER') { setError('Only buyers can ask questions. Switch to a buyer account.'); return; }
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

  const isListingSeller = user?.role === 'SELLER' && Number(user.id) === Number(auction.sellerId);

  return (
    <div className="max-w-7xl mx-auto px-4 py-6">
      {showReport && (
        <ReportModal
          auctionId={id}
          auctionTitle={auction.title}
          onClose={() => setShowReport(false)}
        />
      )}
      <Link to="/search" className="flex items-center gap-1 text-sm text-blue-500 hover:underline mb-4">
        <ChevronLeft size={16} /> Back to Auctions
      </Link>

      <div className="flex flex-col lg:flex-row gap-8">
        {/* Left: Images + Info */}
        <div className="flex-1">
          <div className="flex items-start justify-between mb-2">
            <div>
              <h1 className="text-2xl font-bold text-gray-900">{auction.title}</h1>
              <div className="flex items-center gap-2 text-sm text-gray-500 mt-1 flex-wrap">
                <span className="bg-gray-100 px-2 py-0.5 rounded text-xs">{auction.category}</span>
                <Link to={`/seller/${auction.sellerId}`} className="text-blue-500 hover:underline">
                  Seller: {auction.seller}
                </Link>
                <span>Condition: {auction.condition}</span>
                {auction.quantity > 1 && <span>Qty: {auction.quantity}</span>}
                {auction.costPrice != null && (
                  <span className="text-gray-400">Your cost: {formatCurrency(auction.costPrice)}</span>
                )}
              </div>
              {auction.tags?.length > 0 && (
                <div className="flex flex-wrap gap-1.5 mt-2">
                  {auction.tags.map(tag => (
                    <span key={tag.id} className="bg-blue-50 text-blue-600 border border-blue-100 px-2 py-0.5 rounded-full text-xs font-medium">
                      {tag.name}
                    </span>
                  ))}
                </div>
              )}
            </div>
            <div className="flex gap-2">
              <button
                onClick={handleToggleWatch}
                title={watched ? 'Remove from watchlist' : 'Add to watchlist'}
                className={`p-2 border rounded-full transition-colors ${watched ? 'border-red-200 bg-red-50' : 'border-gray-200 hover:bg-gray-50'}`}
              >
                <Heart size={18} className={watched ? 'text-red-500 fill-red-500' : 'text-gray-400'} />
              </button>
              <button className="p-2 border border-gray-200 rounded-full hover:bg-gray-50">
                <Share2 size={18} className="text-gray-400" />
              </button>
              {user && user.role === 'BUYER' && (
                <button
                  onClick={() => setShowReport(true)}
                  className="p-2 border border-gray-200 rounded-full hover:bg-red-50 hover:border-red-200 transition-colors"
                  title="Report this auction"
                >
                  <Flag size={18} className="text-gray-400 hover:text-red-400" />
                </button>
              )}
            </div>
          </div>

          {/* Image */}
          <div className="bg-gray-100 rounded-xl aspect-video flex items-center justify-center mb-3 text-gray-400">
            {auction.images?.[selectedImage]
              ? <img src={publicPath(auction.images[selectedImage])} alt={auction.title} className="w-full h-full object-contain rounded-xl" />
              : <span className="text-lg">{auction.title.split(' ').slice(0, 2).join(' ')}</span>
            }
          </div>
          {auction.images?.length > 1 && (
            <div className="flex gap-2 mb-4">
              {auction.images.map((img, i) => (
                <button
                  key={i}
                  onClick={() => setSelectedImage(i)}
                  className={`w-20 h-16 rounded-lg overflow-hidden border-2 ${selectedImage === i ? 'border-blue-500' : 'border-gray-200'}`}
                >
                  <img src={publicPath(img)} alt="" className="w-full h-full object-cover" />
                </button>
              ))}
            </div>
          )}

          <AuctionSellerCard seller={sellerProfile} />

          {/* Description */}
          <div className="card p-5 mb-4">
            <h3 className="font-bold text-gray-900 mb-2">Description</h3>
            <p className="text-gray-600 text-sm leading-relaxed">{auction.description}</p>
          </div>

          {/* Bid History */}
          <div className="card p-5">
            <h3 className="font-bold text-gray-900 mb-4 flex items-center gap-2">
              <span>📈</span> Bid History
            </h3>
            {bids.length === 0 ? (
              <p className="text-sm text-gray-400">No bids yet.</p>
            ) : (
              <div className="space-y-3">
                {(() => {
                  let shownCurrent = false;
                  return bids.map((bid, i) => {
                  const isCurrentLeader = bid.currentLeader === true && !shownCurrent;
                  if (bid.currentLeader === true && !shownCurrent) shownCurrent = true;
                  const isSelf = bid.self === true;
                  return (
                    <div
                      key={i}
                      className={`flex items-center justify-between p-3 rounded-lg ${
                        isCurrentLeader
                          ? 'bg-green-50 border border-green-200'
                          : isSelf
                            ? 'bg-blue-50 border border-blue-100'
                            : 'bg-gray-50'
                      }`}
                    >
                      <div>
                        <div className="flex items-center gap-1.5 flex-wrap mb-0.5">
                          {isCurrentLeader && (
                            <span className="text-xs bg-green-500 text-white px-2 py-0.5 rounded">CURRENT</span>
                          )}
                          {isSelf && (
                            <span className="text-xs bg-blue-500 text-white px-2 py-0.5 rounded">YOU</span>
                          )}
                          <span className="font-medium text-sm">{bid.maskedBidderName}</span>
                        </div>
                        <p className="text-xs text-gray-400">{new Date(bid.bidTime).toLocaleString()}</p>
                      </div>
                      <span className="font-bold text-green-600">{formatCurrency(bid.bidAmount)}</span>
                    </div>
                  );
                });
                })()}
              </div>
            )}
          </div>

          {/* Q&A */}
          <div className="card p-5 mt-4">
            <h3 className="font-bold text-gray-900 mb-4">Questions & Answers</h3>
            {message && <div className="text-green-600 text-xs mb-3">{message}</div>}
            {error && <div className="text-red-500 text-xs mb-3">{error}</div>}
            {questions.length === 0 && (
              <p className="text-sm text-gray-400 mb-4">No questions yet.</p>
            )}
            {questions.map((q) => (
              <div key={q.id} className="mb-4 p-3 bg-gray-50 rounded-lg">
                <p className="text-sm font-medium text-gray-700">
                  {q.askerUsername ?? q.buyerUsername ?? 'Buyer'}: {decodeHtmlEntities(q.questionText)}
                </p>
                {q.answerText || q.replyText ? (
                  <p className="text-sm text-blue-700 mt-2 pl-3 border-l-2 border-blue-200">
                    <span className="font-semibold">Seller:</span>{' '}
                    {decodeHtmlEntities(q.answerText || q.replyText)}
                  </p>
                ) : isListingSeller ? (
                  <form
                    onSubmit={(e) => { e.preventDefault(); handleReply(q.id); }}
                    className="flex gap-2 mt-2"
                  >
                    <input
                      value={replyDrafts[q.id] ?? ''}
                      onChange={e => setReplyDrafts(d => ({ ...d, [q.id]: e.target.value }))}
                      placeholder="Write a reply to this question…"
                      className="flex-1 border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-200"
                    />
                    <button
                      type="submit"
                      className="bg-blue-500 text-white px-4 py-2 rounded-lg text-sm hover:bg-blue-600 shrink-0"
                    >
                      Reply
                    </button>
                  </form>
                ) : (
                  <p className="text-xs text-gray-400 mt-2">Awaiting seller reply…</p>
                )}
              </div>
            ))}
            {user?.role === 'BUYER' && (
              <form onSubmit={handleAskQuestion} className="flex gap-2 mt-3">
                <input
                  value={question}
                  onChange={e => setQuestion(e.target.value)}
                  placeholder="Ask a question about this item…"
                  className="flex-1 border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-200"
                />
                <button type="submit" className="bg-blue-500 text-white px-4 py-2 rounded-lg text-sm hover:bg-blue-600">Ask</button>
              </form>
            )}
          </div>
        </div>

        {/* Right: Bidding Panel */}
        <div className="lg:w-80 space-y-4">
          {/* Strategy badge */}
          <div className="card p-3 flex items-center justify-between">
            <span className="text-xs font-medium text-gray-500">Auction type</span>
            <span className={`text-xs font-semibold px-2 py-1 rounded-full ${
              isDutch ? 'bg-amber-50 text-amber-700 border border-amber-200'
              : isBlind ? 'bg-purple-50 text-purple-700 border border-purple-200'
              : 'bg-blue-50 text-blue-700 border border-blue-200'}`}>
              {auction.auctionTypeName ?? 'Standard (Ascending)'}
            </span>
          </div>

          {/* Current price / status */}
          <div className="card p-5">
            <div className="flex items-center justify-between mb-1">
              <span className="text-sm text-gray-500">
                {isDutch ? (auction.open ? 'Current Price' : 'Final Price')
                  : isBlind ? (auction.open ? 'Sealed Bids' : 'Winning Bid')
                  : 'Current Bid'}
              </span>
              {!(isDutch && auction.open) && (
                <span className="text-xs text-gray-400">👤 {auction.numBids} bids</span>
              )}
            </div>
            {isBlind && auction.open ? (
              <div className="text-2xl font-bold text-purple-600 mb-2">🔒 Hidden until close</div>
            ) : (
              <div className={`text-4xl font-bold mb-2 ${isDutch && auction.open ? 'text-amber-600' : 'text-green-500'}`}>
                {formatCurrency(displayPrice)}
              </div>
            )}
            <CountdownTimer endTime={auction.endTime} />
            {isStandard && auction.reservePrice != null && !reserveMet && (
              <div className="flex items-center gap-1 text-orange-500 text-xs mt-2">
                <AlertCircle size={14} />
                Reserve not met ({formatCurrency(auction.reservePrice)})
              </div>
            )}
            {isDutch && auction.open && (
              <p className="text-xs text-gray-500 mt-2">
                Price falls toward {formatCurrency(auction.dutchFloorPrice)}. Accept now to win instantly.
              </p>
            )}
          </div>

          {auction.isOwner && auction.open && (auction.numBids ?? 0) > 0 && (
            <div className="card p-5 text-sm border border-amber-200 bg-amber-50">
              <p className="font-medium text-amber-800 mb-2">Seller: early close</p>
              {message && <div className="text-green-600 text-xs mb-2">{message}</div>}
              {error && <div className="text-red-500 text-xs mb-2">{error}</div>}
              <button
                onClick={() => handleDeclareWinner(true)}
                className="w-full bg-amber-600 hover:bg-amber-700 text-white font-medium py-2.5 rounded-lg transition-colors"
              >
                Declare Winner Early
              </button>
              <p className="text-xs text-amber-700 mt-2">Ends the auction now and sells to the current highest bidder.</p>
            </div>
          )}

          {!auction.open ? (
            isScheduled ? (
              <div className="card p-5 text-center text-sm">
                <p className="mb-1 font-medium text-orange-600">This auction hasn't started yet.</p>
                <p className="text-xs text-gray-400">
                  Bidding opens {new Date(auction.startTime).toLocaleString()}
                </p>
              </div>
            ) : (
            <div className="card p-5 text-center text-sm text-gray-500">
              <p className="mb-3">This auction has ended.</p>
              {auction.isOwner && (
                <>
                  {message && <div className="text-green-600 text-xs mb-2">{message}</div>}
                  {error && <div className="text-red-500 text-xs mb-2">{error}</div>}
                  <button
                    onClick={() => handleDeclareWinner(false)}
                    className="w-full bg-gray-900 hover:bg-gray-800 text-white font-medium py-2.5 rounded-lg transition-colors"
                  >
                    Declare Winner &amp; Create Order
                  </button>
                  <p className="text-xs text-gray-400 mt-2">Finalises the sale to the highest bidder.</p>
                </>
              )}
            </div>
            )
          ) : isStandard ? (
            <>
              {/* Place Bid (ascending) */}
              <div className="card p-5">
                <h3 className="font-bold text-gray-900 mb-3">Place Bid</h3>
                <p className="text-xs text-gray-500 mb-2">Your Bid (Min: {formatCurrency(minBid)})</p>
                <div className="flex items-center border border-gray-200 rounded-lg overflow-hidden mb-2">
                  <span className="px-3 text-gray-400 text-sm">$</span>
                  <input
                    type="number"
                    value={bidAmount}
                    onChange={e => setBidAmount(e.target.value)}
                    placeholder={minBid}
                    className="flex-1 py-2 pr-3 text-sm focus:outline-none"
                  />
                </div>
                <div className="flex gap-2 mb-3">
                  {[50, 100, 250].map(inc => (
                    <button
                      key={inc}
                      onClick={() => setBidAmount(String((auction.currentBid || 0) + inc))}
                      className="border border-gray-200 rounded px-3 py-1 text-xs hover:bg-gray-50 transition-colors"
                    >
                      +${inc}
                    </button>
                  ))}
                </div>
                {message && <div className="text-green-600 text-xs mb-2">{message}</div>}
                {error && <div className="text-red-500 text-xs mb-2">{error}</div>}
                <button
                  onClick={handlePlaceBid}
                  className="w-full bg-blue-500 hover:bg-blue-600 text-white font-medium py-3 rounded-lg transition-colors"
                >
                  Place Bid
                </button>
              </div>

              {/* Auto-Bid */}
              <div className="card p-5 border-purple-200">
                <h3 className="font-bold text-gray-900 mb-1">Auto-Bid</h3>

                {myAutoBid && !autoBidEditing ? (
                  /* Active auto-bid: show summary + Cancel / Edit buttons */
                  <div>
                    <div className="bg-purple-50 border border-purple-200 rounded-lg p-3 mb-3">
                      <p className="text-xs text-purple-600 font-semibold mb-1">Auto-Bid Active</p>
                      <div className="flex justify-between text-sm text-gray-700">
                        <span>Max bid</span>
                        <span className="font-bold text-purple-700">{formatCurrency(myAutoBid.maxAmount)}</span>
                      </div>
                      <div className="flex justify-between text-sm text-gray-700 mt-0.5">
                        <span>Bid increment</span>
                        <span className="font-medium">{formatCurrency(myAutoBid.bidIncrement)}</span>
                      </div>
                    </div>
                    {message && <div className="text-green-600 text-xs mb-2">{message}</div>}
                    {error && <div className="text-red-500 text-xs mb-2">{error}</div>}
                    <div className="flex gap-2">
                      <button
                        onClick={() => {
                          setAutoBidMax(String(myAutoBid.maxAmount));
                          setAutoBidIncrement(String(myAutoBid.bidIncrement));
                          setAutoBidEditing(true);
                        }}
                        className="flex-1 border border-purple-300 text-purple-700 font-medium py-2 rounded-lg text-sm hover:bg-purple-50 transition-colors"
                      >
                        Edit
                      </button>
                      <button
                        onClick={handleCancelAutoBid}
                        className="flex-1 border border-red-300 text-red-600 font-medium py-2 rounded-lg text-sm hover:bg-red-50 transition-colors"
                      >
                        Cancel Auto-Bid
                      </button>
                    </div>
                  </div>
                ) : (
                  /* No active auto-bid or editing mode: show the form */
                  <div>
                    <p className="text-xs text-gray-500 mb-3">
                      {autoBidEditing ? 'Update your auto-bid settings below.' : 'Set a maximum bid and let the system automatically bid for you'}
                    </p>
                    <label className="text-xs text-gray-500 block mb-1">Maximum Bid</label>
                    <div className="flex items-center border border-gray-200 rounded-lg overflow-hidden mb-3">
                      <span className="px-3 text-gray-400 text-sm">$</span>
                      <input
                        type="number"
                        value={autoBidMax}
                        onChange={e => setAutoBidMax(e.target.value)}
                        placeholder="2500"
                        className="flex-1 py-2 pr-3 text-sm focus:outline-none"
                      />
                    </div>
                    <label className="text-xs text-gray-500 block mb-1">Bid Increment</label>
                    <div className="flex items-center border border-gray-200 rounded-lg overflow-hidden mb-3">
                      <span className="px-3 text-gray-400 text-sm">$</span>
                      <input
                        type="number"
                        value={autoBidIncrement}
                        onChange={e => setAutoBidIncrement(e.target.value)}
                        className="flex-1 py-2 pr-3 text-sm focus:outline-none"
                      />
                    </div>
                    {message && <div className="text-green-600 text-xs mb-2">{message}</div>}
                    {error && <div className="text-red-500 text-xs mb-2">{error}</div>}
                    <div className="flex gap-2">
                      {autoBidEditing && (
                        <button
                          onClick={() => setAutoBidEditing(false)}
                          className="flex-1 border border-gray-300 text-gray-600 font-medium py-2.5 rounded-lg text-sm hover:bg-gray-50 transition-colors"
                        >
                          Back
                        </button>
                      )}
                      <button
                        onClick={handleAutoBid}
                        className="flex-1 bg-purple-600 hover:bg-purple-700 text-white font-medium py-3 rounded-lg transition-colors"
                      >
                        {autoBidEditing ? 'Update Auto-Bid' : 'Enable Auto-Bid'}
                      </button>
                    </div>
                  </div>
                )}
              </div>
            </>
          ) : isDutch ? (
            /* Dutch: accept current clock price */
            <div className="card p-5">
              <h3 className="font-bold text-gray-900 mb-2">Buy at Current Price</h3>
              <p className="text-xs text-gray-500 mb-3">
                The first buyer to accept wins immediately at the displayed price.
              </p>
              {message && <div className="text-green-600 text-xs mb-2">{message}</div>}
              {error && <div className="text-red-500 text-xs mb-2">{error}</div>}
              <button
                onClick={handleAcceptDutch}
                className="w-full bg-amber-500 hover:bg-amber-600 text-white font-medium py-3 rounded-lg transition-colors"
              >
                Accept {formatCurrency(displayPrice)}
              </button>
            </div>
          ) : (
            /* Blind: submit one sealed bid */
            <div className="card p-5">
              <h3 className="font-bold text-gray-900 mb-2">Submit Sealed Bid</h3>
              <p className="text-xs text-gray-500 mb-2">
                One hidden bid per buyer (Min: {formatCurrency(sealedMinBid)}). Amounts stay secret until close.
              </p>
              <div className="flex items-center border border-gray-200 rounded-lg overflow-hidden mb-2">
                <span className="px-3 text-gray-400 text-sm">$</span>
                <input
                  type="number"
                  value={bidAmount}
                  onChange={e => setBidAmount(e.target.value)}
                  placeholder={sealedMinBid}
                  className="flex-1 py-2 pr-3 text-sm focus:outline-none"
                />
              </div>
              {message && <div className="text-green-600 text-xs mb-2">{message}</div>}
              {error && <div className="text-red-500 text-xs mb-2">{error}</div>}
              <button
                onClick={handleSealedBid}
                className="w-full bg-purple-600 hover:bg-purple-700 text-white font-medium py-3 rounded-lg transition-colors"
              >
                Submit Sealed Bid
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
