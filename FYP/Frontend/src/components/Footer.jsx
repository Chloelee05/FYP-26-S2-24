/**
 * Site footer, rendered once in App.jsx below the routed page. Takes no props and shows
 * the same links to everyone, including guests, since nothing here is account specific.
 */
import { Link } from 'react-router-dom';
import { Gavel } from 'lucide-react';

// The legal pages the assessment requires. Each is a static route under src/pages.
const LEGAL = [
  { to: '/terms', label: 'User Agreement' },
  { to: '/privacy', label: 'Privacy' },
  { to: '/payments', label: 'Payments Terms of use' },
  { to: '/cookies', label: 'Cookies' },
  { to: '/adchoice', label: 'AdChoice' },
];

export default function Footer() {
  return (
    <footer className="mt-auto bg-ink-900 text-ink-300 relative overflow-hidden">
      <div
        className="absolute inset-0 opacity-[0.16] pointer-events-none"
        style={{
          backgroundImage:
            'radial-gradient(38rem 22rem at 15% 0%, #3b76f6, transparent 60%), radial-gradient(32rem 20rem at 90% 20%, #f97e07, transparent 55%)',
        }}
      />
      <div className="relative max-w-7xl mx-auto px-4 py-14 grid gap-10 md:grid-cols-[1.6fr,1fr]">
        <div>
          <div className="flex items-center gap-2 mb-4">
            <span className="grid place-items-center w-9 h-9 rounded-xl bg-gradient-to-br from-primary-500 to-primary-700 text-white">
              <Gavel size={18} />
            </span>
            <span className="font-display font-extrabold text-lg text-white">
              Auction<span className="text-primary-400">Hub</span>
            </span>
          </div>
          <p className="text-sm text-ink-400 max-w-sm leading-relaxed">
            Bid smart, buy right. Live, sealed and Dutch auctions from verified sellers — all in one marketplace.
          </p>
        </div>

        <div>
          <p className="text-xs font-bold uppercase tracking-[0.14em] text-ink-500 mb-4">Marketplace</p>
          <ul className="space-y-2.5 text-sm">
            <li><Link to="/search" className="hover:text-white transition-colors">Browse auctions</Link></li>
            <li><Link to="/bidding-history" className="hover:text-white transition-colors">Bidding history</Link></li>
            <li><Link to="/watchlist" className="hover:text-white transition-colors">Watchlist</Link></li>
            <li><Link to="/support" className="hover:text-white transition-colors">Contact admin</Link></li>
          </ul>
        </div>

      </div>

      <div className="relative border-t border-white/10">
        <div className="max-w-7xl mx-auto px-4 py-5 flex flex-col sm:flex-row items-center justify-between gap-3 text-xs text-ink-500">
          <p>Copyright © 2026 AuctionHub Inc. All Rights Reserved.</p>
          <div className="flex flex-wrap justify-center gap-x-4 gap-y-1">
            {LEGAL.map(({ to, label }) => (
              <Link key={to} to={to} className="hover:text-ink-200 transition-colors">{label}</Link>
            ))}
          </div>
        </div>
      </div>
    </footer>
  );
}
