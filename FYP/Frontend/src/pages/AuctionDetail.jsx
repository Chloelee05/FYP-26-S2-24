import { useState, useEffect } from 'react';
import { useParams, Link } from 'react-router-dom';
import { Heart, Share2, AlertCircle, ChevronLeft, Flag } from 'lucide-react';
import CountdownTimer from '../components/CountdownTimer';
import ReportModal from '../components/ReportModal';
import { getAuctionDetail, getAuctionBids, getAuctionQuestions, placeBid, setAutoBid, addToWatchlist, removeFromWatchlist, getWatchlist, askQuestion } from '../api/auction';
import { useAuth } from '../context/AuthContext';
import { formatCurrency } from '../utils/helpers';

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
  const [question, setQuestion] = useState('');
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [showReport, setShowReport] = useState(false);
  const [watched, setWatched] = useState(false);

  useEffect(() => {
    getAuctionDetail(id).then(r => setAuction(r.data)).catch(() => {});
    getAuctionBids(id).then(r => setBids(r.data.bids ?? [])).catch(() => {});
    getAuctionQuestions(id).then(r => setQuestions(r.data ?? [])).catch(() => {});
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

  const minBid = (auction.currentBid || 0) + 50;
  const reserveMet = auction.currentBid >= auction.reservePrice;

  const handlePlaceBid = async () => {
    if (!user) { setError('Please log in to place a bid.'); return; }
    setError(''); setMessage('');
    try {
      await placeBid(id, bidAmount);
      setMessage('Bid placed successfully!');
      getAuctionDetail(id).then(r => setAuction(r.data)).catch(() => {});
      getAuctionBids(id).then(r => setBids(r.data.bids ?? [])).catch(() => {});
    } catch (err) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Failed to place bid.');
    }
  };

  const handleAutoBid = async () => {
    if (!user) { setError('Please log in to enable auto-bid.'); return; }
    setError(''); setMessage('');
    try {
      await setAutoBid(id, autoBidMax, autoBidIncrement);
      setMessage('Auto-bid enabled!');
    } catch (err) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Failed to enable auto-bid.');
    }
  };

  const handleAskQuestion = async (e) => {
    e.preventDefault();
    if (!user) { setError('Please log in to ask a question.'); return; }
    try {
      await askQuestion(id, question);
      setQuestion('');
      setMessage('Question submitted!');
      getAuctionQuestions(id).then(r => setQuestions(r.data ?? [])).catch(() => {});
    } catch {
      setError('Failed to submit question.');
    }
  };

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
                <span>Seller: {auction.seller}</span>
                <span>Condition: {auction.condition}</span>
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
              ? <img src={auction.images[selectedImage]} alt={auction.title} className="w-full h-full object-contain rounded-xl" />
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
                  <img src={img} alt="" className="w-full h-full object-cover" />
                </button>
              ))}
            </div>
          )}

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
                {bids.map((bid, i) => (
                  <div key={i} className={`flex items-center justify-between p-3 rounded-lg ${i === 0 ? 'bg-green-50 border border-green-200' : 'bg-gray-50'}`}>
                    <div>
                      {i === 0 && <span className="text-xs bg-green-500 text-white px-2 py-0.5 rounded mr-2">CURRENT</span>}
                      <span className="font-medium text-sm">{bid.maskedBidderName}</span>
                      <p className="text-xs text-gray-400 mt-0.5">{new Date(bid.bidTime).toLocaleString()}</p>
                    </div>
                    <span className="font-bold text-green-600">{formatCurrency(bid.bidAmount)}</span>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Q&A */}
          <div className="card p-5 mt-4">
            <h3 className="font-bold text-gray-900 mb-4">Questions & Answers</h3>
            {questions.length === 0 && (
              <p className="text-sm text-gray-400 mb-4">No questions yet.</p>
            )}
            {questions.map((q, i) => (
              <div key={i} className="mb-3 p-3 bg-gray-50 rounded-lg">
                <p className="text-sm font-medium text-gray-700">{q.buyerUsername ?? 'Buyer'}: {q.questionText}</p>
                {q.replyText && <p className="text-sm text-blue-600 mt-1">Seller: {q.replyText}</p>}
              </div>
            ))}
            {user && (
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
          {/* Current Bid */}
          <div className="card p-5">
            <div className="flex items-center justify-between mb-1">
              <span className="text-sm text-gray-500">Current Bid</span>
              <span className="text-xs text-gray-400">👤 {auction.numBids} bids</span>
            </div>
            <div className="text-4xl font-bold text-green-500 mb-2">{formatCurrency(auction.currentBid)}</div>
            <CountdownTimer endTime={auction.endTime} />
            {!reserveMet && (
              <div className="flex items-center gap-1 text-orange-500 text-xs mt-2">
                <AlertCircle size={14} />
                Reserve not met ({formatCurrency(auction.reservePrice)})
              </div>
            )}
          </div>

          {/* Place Bid */}
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
            <p className="text-xs text-gray-500 mb-3">Set a maximum bid and let the system automatically bid for you</p>
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
            <button
              onClick={handleAutoBid}
              className="w-full bg-purple-600 hover:bg-purple-700 text-white font-medium py-3 rounded-lg transition-colors"
            >
              Enable Auto-Bid
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
