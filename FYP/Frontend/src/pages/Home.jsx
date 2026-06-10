import { useState, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import AuctionCard from '../components/AuctionCard';
import { searchAuctions, getCategories, getRecommendations } from '../api/auction';
import { useAuth } from '../context/AuthContext';

export default function Home() {
  const navigate = useNavigate();
  const { user } = useAuth();
  const [auctions, setAuctions] = useState([]);
  const [categories, setCategories] = useState([]);
  const [recommended, setRecommended] = useState([]);
  const [personalised, setPersonalised] = useState(false);

  useEffect(() => {
    getCategories().then(r => setCategories(r.data)).catch(() => {});
    searchAuctions({ trending: true }).then(r => setAuctions(r.data.results ?? r.data)).catch(() => {});
  }, []);

  useEffect(() => {
    getRecommendations(8)
      .then(r => {
        setRecommended(r.data.results ?? []);
        setPersonalised(Boolean(r.data.personalised));
      })
      .catch(() => { setRecommended([]); setPersonalised(false); });
  }, [user]);

  return (
    <div className="min-h-screen">
      {/* Hero */}
      <section className="bg-gradient-to-r from-blue-700 to-blue-500 text-white py-16 px-4">
        <div className="max-w-7xl mx-auto flex flex-col md:flex-row items-center gap-8">
          <div className="flex-1">
            <h1 className="text-4xl md:text-5xl font-bold italic mb-4 leading-tight">
              Bid Smart, Buy Right
            </h1>
            <p className="text-blue-100 text-lg italic mb-6">
              List your items, bid on your favorites,<br />and find the perfect deal with ease.
            </p>
            <button
              onClick={() => navigate('/search')}
              className="bg-gray-900 text-white px-6 py-3 rounded-full font-medium hover:bg-gray-800 transition-colors"
            >
              Explore more
            </button>
          </div>
          <div className="flex-1 grid grid-cols-3 gap-2 max-w-sm">
            {[...Array(6)].map((_, i) => (
              <div key={i} className="aspect-square bg-blue-600/40 rounded-lg flex items-center justify-center text-4xl">
                {['⌚','🎧','🚗','📱','🏠','📷'][i]}
              </div>
            ))}
          </div>
        </div>
      </section>

      <div className="max-w-7xl mx-auto px-4 py-10">
        {/* Categories */}
        <section className="mb-12">
          <h2 className="text-xl font-bold text-gray-900 mb-6">Popular Categories</h2>
          <div className="flex gap-6 flex-wrap">
            {categories.map((cat) => (
              <Link
                key={cat.name}
                to={`/search?category=${encodeURIComponent(cat.name)}`}
                className="flex flex-col items-center gap-2 group"
              >
                <div className="w-24 h-24 rounded-full bg-gray-100 flex items-center justify-center text-4xl group-hover:bg-blue-50 transition-colors border-2 border-transparent group-hover:border-blue-200">
                  {cat.emoji || '🏷'}
                </div>
                <span className="text-sm font-medium text-gray-700">{cat.name}</span>
              </Link>
            ))}
          </div>
        </section>

        {/* Recommendations */}
        {recommended.length > 0 && (
          <section className="mb-12">
            <h2 className="text-xl font-bold text-gray-900 mb-1">
              {personalised ? 'Recommended for You' : 'Popular Right Now'}
            </h2>
            <p className="text-sm text-gray-500 mb-6">
              {personalised
                ? 'Based on items you and similar buyers have bid on or watched.'
                : 'Trending auctions across the marketplace. Sign in for personalised picks.'}
            </p>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
              {recommended.map(a => <AuctionCard key={a.auctionId} auction={a} />)}
            </div>
          </section>
        )}

        {/* Trending Auctions */}
        <section>
          <h2 className="text-xl font-bold text-gray-900 mb-6">Trending Auction</h2>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
            {auctions.map(a => <AuctionCard key={a.auctionId ?? a.id} auction={a} />)}
          </div>
        </section>
      </div>
    </div>
  );
}
