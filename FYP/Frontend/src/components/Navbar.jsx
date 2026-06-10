import { Link, useNavigate } from 'react-router-dom';
import { Search, User, LogOut, LayoutDashboard, Heart, MessageCircle, MessageSquare } from 'lucide-react';
import { useState } from 'react';
import { useAuth } from '../context/AuthContext';
import NotificationBell from './NotificationBell';

export default function Navbar() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const [menuOpen, setMenuOpen] = useState(false);

  const handleSearch = (e) => {
    e.preventDefault();
    if (search.trim()) navigate(`/search?q=${encodeURIComponent(search.trim())}`);
  };

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  return (
    <nav className="bg-white border-b border-gray-200 sticky top-0 z-50">
      <div className="max-w-7xl mx-auto px-4 flex items-center justify-between h-16 gap-4">
        <Link to="/" className="flex items-center gap-2 text-blue-500 font-bold text-xl shrink-0">
          <span className="text-2xl">⚒</span> AuctionHub
        </Link>

        <div className="hidden md:flex items-center gap-6 text-sm text-gray-600">
          <Link to="/search" className="hover:text-blue-500 transition-colors">Explore</Link>
          {user?.role === 'SELLER' && (
            <Link to="/seller/dashboard" className="hover:text-blue-500 transition-colors">Sell Items</Link>
          )}
          <Link to="/bidding-history" className="hover:text-blue-500 transition-colors">Bidding History</Link>
        </div>

        <form onSubmit={handleSearch} className="flex-1 max-w-xs hidden md:flex">
          <div className="relative w-full">
            <input
              value={search}
              onChange={e => setSearch(e.target.value)}
              placeholder="Search"
              className="w-full border border-gray-200 rounded-full px-4 py-2 pr-10 text-sm focus:outline-none focus:ring-2 focus:ring-blue-200"
            />
            <button type="submit" className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400">
              <Search size={16} />
            </button>
          </div>
        </form>

        <div className="flex items-center gap-2">
          {user && <NotificationBell />}
          {user ? (
            <div className="relative">
              <button
                onClick={() => setMenuOpen(v => !v)}
                className="flex items-center gap-2 px-3 py-2 rounded-full hover:bg-gray-100 transition-colors"
              >
                <div className="w-8 h-8 bg-blue-500 rounded-full flex items-center justify-center text-white text-sm font-medium">
                  {user.username?.[0]?.toUpperCase() ?? 'U'}
                </div>
                <span className="hidden md:block text-sm font-medium text-gray-700">{user.username}</span>
              </button>
              {menuOpen && (
                <div className="absolute right-0 top-12 bg-white border border-gray-200 rounded-xl shadow-lg py-1 w-48 z-50">
                  <Link to="/profile" className="flex items-center gap-2 px-4 py-2 text-sm hover:bg-gray-50" onClick={() => setMenuOpen(false)}>
                    <User size={14} /> Profile
                  </Link>
                  {user.role === 'BUYER' && (
                    <Link to="/watchlist" className="flex items-center gap-2 px-4 py-2 text-sm hover:bg-gray-50" onClick={() => setMenuOpen(false)}>
                      <Heart size={14} /> Watchlist
                    </Link>
                  )}
                  {user.role === 'SELLER' && (
                    <Link to="/seller/dashboard" className="flex items-center gap-2 px-4 py-2 text-sm hover:bg-gray-50" onClick={() => setMenuOpen(false)}>
                      <LayoutDashboard size={14} /> Seller Dashboard
                    </Link>
                  )}
                  {user.role === 'ADMIN' && (
                    <Link to="/admin" className="flex items-center gap-2 px-4 py-2 text-sm hover:bg-gray-50" onClick={() => setMenuOpen(false)}>
                      <LayoutDashboard size={14} /> Admin Panel
                    </Link>
                  )}
                  {user.role !== 'ADMIN' && (
                    <Link to="/messages" className="flex items-center gap-2 px-4 py-2 text-sm hover:bg-gray-50" onClick={() => setMenuOpen(false)}>
                      <MessageSquare size={14} /> Messages
                    </Link>
                  )}
                  {user.role !== 'ADMIN' && (
                    <Link to="/support" className="flex items-center gap-2 px-4 py-2 text-sm hover:bg-gray-50" onClick={() => setMenuOpen(false)}>
                      <MessageCircle size={14} /> Contact Admin
                    </Link>
                  )}
                  <hr className="my-1" />
                  <button onClick={handleLogout} className="flex items-center gap-2 w-full px-4 py-2 text-sm text-red-500 hover:bg-red-50">
                    <LogOut size={14} /> Logout
                  </button>
                </div>
              )}
            </div>
          ) : (
            <Link to="/login" className="bg-gray-900 text-white px-4 py-2 rounded-full text-sm font-medium hover:bg-gray-700 transition-colors">
              Sign in
            </Link>
          )}
        </div>
      </div>
    </nav>
  );
}
