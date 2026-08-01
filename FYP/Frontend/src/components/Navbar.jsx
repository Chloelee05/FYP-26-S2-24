import { Link, NavLink, useNavigate, useLocation } from 'react-router-dom';
import { Search, User, LogOut, LayoutDashboard, Heart, MessageCircle, MessageSquare, Gavel, Menu, X, ChevronDown, ShoppingBag, Tag } from 'lucide-react';
import { useState, useRef, useEffect } from 'react';
import { useAuth } from '../context/AuthContext';
import { publicPath } from '../utils/appBase';
import useDebouncedValue from '../hooks/useDebouncedValue';
import NotificationBell from './NotificationBell';

/** Same treatment as the notification bell, so the icon row reads as one group. */
const iconLinkClass = ({ isActive }) =>
  `relative p-2 rounded-full transition-colors ${
    isActive ? 'bg-ink-100 text-ink-900' : 'text-ink-500 hover:bg-ink-100 hover:text-ink-900'
  }`;

export default function Navbar() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const { pathname, search: locationSearch } = useLocation();
  const [search, setSearch] = useState('');
  const [menuOpen, setMenuOpen] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const menuRef = useRef(null);
  // Enter is optional, so results follow the typing. This guards the effect below
  // from running on mount, where it would otherwise wipe the q of a /search URL
  // the user arrived on (a shared link, a category tile) before they touched the box.
  const hasTyped = useRef(false);

  // Close the account dropdown when clicking anywhere outside it.
  useEffect(() => {
    const onClickOutside = (e) => {
      if (menuRef.current && !menuRef.current.contains(e.target)) setMenuOpen(false);
    };
    document.addEventListener('mousedown', onClickOutside);
    return () => document.removeEventListener('mousedown', onClickOutside);
  }, []);

  // Search as the term settles rather than on submit. Debounced so a word is one
  // navigation instead of one per keystroke.
  const settledSearch = useDebouncedValue(search);

  useEffect(() => {
    if (!hasTyped.current) return;
    const term = settledSearch.trim();
    const onSearchPage = pathname === '/search';
    // Clearing the box only means anything to someone already looking at results.
    if (!term && !onSearchPage) return;

    const target = term ? `/search?q=${encodeURIComponent(term)}` : '/search';
    // Already showing exactly this — also what stops the effect re-firing on the
    // location change it just caused.
    if (`${pathname}${locationSearch}` === target) return;
    // Replace while searching, so refining a term doesn't bury the previous page
    // under one history entry per edit.
    navigate(target, { replace: onSearchPage });
  }, [settledSearch, pathname, locationSearch, navigate]);

  const handleSearchChange = (e) => {
    hasTyped.current = true;
    setSearch(e.target.value);
  };

  // Enter still works, and skips the debounce.
  const handleSearch = (e) => {
    e.preventDefault();
    if (search.trim()) navigate(`/search?q=${encodeURIComponent(search.trim())}`);
    setMobileOpen(false);
  };

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  const navLinkClass = ({ isActive }) =>
    `relative px-1 py-1 text-sm font-medium transition-colors ${
      isActive ? 'text-primary-600' : 'text-ink-600 hover:text-ink-900'
    } after:absolute after:-bottom-0.5 after:left-0 after:h-0.5 after:rounded-full after:bg-primary-600 after:transition-all ${
      isActive ? 'after:w-full' : 'after:w-0'
    }`;

  const menuItemClass = 'flex items-center gap-2.5 px-3 py-2 text-sm text-ink-700 rounded-lg hover:bg-ink-50 hover:text-ink-900 transition-colors';

  // Buying and selling are one account type; only the admin console is separate.
  const isMember = Boolean(user) && user.role !== 'ADMIN';

  return (
    <nav className="sticky top-0 z-50 border-b border-ink-200/70 bg-white/85 backdrop-blur-lg supports-[backdrop-filter]:bg-white/70">
      <div className="max-w-7xl mx-auto px-4 flex items-center justify-between h-16 gap-3">
        <Link to="/" className="flex items-center gap-2 shrink-0 group">
          <span className="grid place-items-center w-9 h-9 rounded-xl bg-gradient-to-br from-primary-500 to-primary-700 text-white shadow-sm transition-transform group-hover:-rotate-6">
            <Gavel size={18} />
          </span>
          <span className="font-display font-extrabold text-lg text-ink-900 tracking-tight">
            Auction<span className="text-primary-600">Hub</span>
          </span>
        </Link>

        <div className="hidden md:flex items-center gap-7">
          <NavLink to="/search" className={navLinkClass}>Explore</NavLink>
          {/* One account does both, so selling is always on offer; the route itself
              turns the capability on for accounts that haven't used it yet. */}
          {isMember && (
            <NavLink to="/seller/listings" className={navLinkClass}>My listings</NavLink>
          )}
          {/* Bidding history is per-account, so it is hidden from signed-out visitors. */}
          {user && (
            <NavLink to="/bidding-history" className={navLinkClass}>Bidding History</NavLink>
          )}
        </div>

        <form onSubmit={handleSearch} className="flex-1 max-w-xs hidden md:flex">
          <div className="relative w-full group">
            <Search
              size={16}
              className="absolute left-3.5 top-1/2 -translate-y-1/2 text-ink-400 group-focus-within:text-primary-500 transition-colors pointer-events-none"
            />
            <input
              value={search}
              onChange={handleSearchChange}
              placeholder="Search auctions…"
              className="w-full bg-ink-100/70 border border-transparent rounded-full pl-10 pr-4 py-2 text-sm text-ink-900 placeholder:text-ink-400
                         transition-all focus:outline-none focus:bg-white focus:border-primary-300 focus:ring-4 focus:ring-primary-500/10"
            />
          </div>
        </form>

        <div className="flex items-center gap-1.5">
          {/* Watchlist and messages sit either side of the bell as one icon row. */}
          {isMember && (
            <NavLink to="/watchlist" className={iconLinkClass} aria-label="Watchlist" title="Watchlist">
              <Heart size={20} />
            </NavLink>
          )}
          {user && <NotificationBell />}
          {user && user.role !== 'ADMIN' && (
            <NavLink to="/messages" className={iconLinkClass} aria-label="Messages" title="Messages">
              <MessageSquare size={20} />
            </NavLink>
          )}
          {user ? (
            <div className="relative" ref={menuRef}>
              <button
                onClick={() => setMenuOpen(v => !v)}
                className="flex items-center gap-2 pl-1 pr-2 py-1 rounded-full border border-transparent hover:border-ink-200 hover:bg-ink-50 transition-colors"
              >
                {user.profileImageUrl ? (
                  <img
                    src={publicPath(user.profileImageUrl)}
                    alt=""
                    className="w-8 h-8 rounded-full object-cover border border-ink-200"
                  />
                ) : (
                  <div className="w-8 h-8 bg-gradient-to-br from-primary-500 to-primary-700 rounded-full flex items-center justify-center text-white text-sm font-bold shadow-sm">
                    {user.username?.[0]?.toUpperCase() ?? 'U'}
                  </div>
                )}
                <span className="hidden md:block text-sm font-semibold text-ink-700 max-w-[7rem] truncate">{user.username}</span>
                <ChevronDown size={14} className={`hidden md:block text-ink-400 transition-transform ${menuOpen ? 'rotate-180' : ''}`} />
              </button>
              {menuOpen && (
                <div className="absolute right-0 top-full mt-2.5 bg-white border border-ink-200 rounded-2xl shadow-pop p-1.5 w-56 z-50 animate-scale-in origin-top-right">
                  <div className="px-3 py-2 mb-1 border-b border-ink-100">
                    <p className="text-sm font-semibold text-ink-900 truncate">{user.username}</p>
                    <p className="text-xs text-ink-400">
                      {user.role === 'ADMIN' ? 'Admin' : 'Member — buy & sell'}
                    </p>
                  </div>
                  <Link to="/profile" className={menuItemClass} onClick={() => setMenuOpen(false)}>
                    <User size={15} className="text-ink-400" /> My account
                  </Link>
                  {/* Orders live on their own pages, split by which side of the deal
                      the member is on, rather than as a tab inside the profile. */}
                  {isMember && (
                    <>
                      <Link to="/purchases" className={menuItemClass} onClick={() => setMenuOpen(false)}>
                        <ShoppingBag size={15} className="text-ink-400" /> My purchases
                      </Link>
                      <Link to="/sales" className={menuItemClass} onClick={() => setMenuOpen(false)}>
                        <Tag size={15} className="text-ink-400" /> My sales
                      </Link>
                      <Link to="/seller/dashboard" className={menuItemClass} onClick={() => setMenuOpen(false)}>
                        <LayoutDashboard size={15} className="text-ink-400" /> Seller Dashboard
                      </Link>
                    </>
                  )}
                  {user.role === 'ADMIN' && (
                    <Link to="/admin" className={menuItemClass} onClick={() => setMenuOpen(false)}>
                      <LayoutDashboard size={15} className="text-ink-400" /> Admin Panel
                    </Link>
                  )}
                  {user.role !== 'ADMIN' && (
                    <Link to="/support" className={menuItemClass} onClick={() => setMenuOpen(false)}>
                      <MessageCircle size={15} className="text-ink-400" /> Contact Admin
                    </Link>
                  )}
                  <div className="my-1 border-t border-ink-100" />
                  <button
                    onClick={handleLogout}
                    className="flex items-center gap-2.5 w-full px-3 py-2 text-sm font-medium text-red-600 rounded-lg hover:bg-red-50 transition-colors"
                  >
                    <LogOut size={15} /> Logout
                  </button>
                </div>
              )}
            </div>
          ) : (
            <Link to="/login" className="btn-dark btn-sm px-4 py-2 rounded-full">
              Sign in
            </Link>
          )}

          <button
            type="button"
            onClick={() => setMobileOpen(v => !v)}
            className="md:hidden p-2 rounded-lg text-ink-600 hover:bg-ink-100 transition-colors"
            aria-label="Toggle menu"
          >
            {mobileOpen ? <X size={20} /> : <Menu size={20} />}
          </button>
        </div>
      </div>

      {/* Mobile drawer */}
      {mobileOpen && (
        <div className="md:hidden border-t border-ink-200/70 bg-white px-4 py-4 space-y-3 animate-fade-up">
          <form onSubmit={handleSearch}>
            <div className="relative">
              <Search size={16} className="absolute left-3.5 top-1/2 -translate-y-1/2 text-ink-400 pointer-events-none" />
              <input
                value={search}
                onChange={handleSearchChange}
                placeholder="Search auctions…"
                className="input-field pl-10 rounded-full"
              />
            </div>
          </form>
          <div className="flex flex-col">
            <Link to="/search" onClick={() => setMobileOpen(false)} className="py-2.5 text-sm font-medium text-ink-700 border-b border-ink-100">Explore</Link>
            {isMember && (
              <Link to="/seller/listings" onClick={() => setMobileOpen(false)} className="py-2.5 text-sm font-medium text-ink-700 border-b border-ink-100">My listings</Link>
            )}
            {user && (
              <Link to="/bidding-history" onClick={() => setMobileOpen(false)} className="py-2.5 text-sm font-medium text-ink-700">Bidding History</Link>
            )}
          </div>
        </div>
      )}
    </nav>
  );
}
