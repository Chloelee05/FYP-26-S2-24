/*
 * Catch-all page for the "*" route in App.jsx. Public, and rendered inside MainLayout so the
 * navbar and footer stay available. Shows the path that failed to match, which usually means
 * a removed auction or an out of date link, and offers a way back or into search.
 */
import { Link, useNavigate, useLocation } from 'react-router-dom';
import { Compass, ChevronLeft, Search } from 'lucide-react';

export default function NotFound() {
  const navigate = useNavigate();
  const { pathname } = useLocation();

  return (
    <div className="max-w-xl mx-auto px-4 py-20 text-center">
      <span className="grid place-items-center w-16 h-16 rounded-2xl bg-ink-100 text-ink-400 mx-auto mb-6">
        <Compass size={30} />
      </span>

      <p className="font-display text-6xl font-extrabold text-ink-200 tabular-nums leading-none">404</p>
      <h1 className="page-title mt-4">This page doesn’t exist</h1>
      <p className="page-subtitle">
        We couldn’t find anything at <code className="font-mono text-ink-600">{pathname}</code>.
        The auction may have been removed, or the link may be out of date.
      </p>

      <div className="flex flex-wrap gap-3 justify-center mt-8">
        <button type="button" onClick={() => navigate(-1)} className="btn-secondary">
          <ChevronLeft size={16} /> Go back
        </button>
        <Link to="/search" className="btn-primary">
          <Search size={16} /> Browse auctions
        </Link>
      </div>
    </div>
  );
}
