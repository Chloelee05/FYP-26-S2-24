import { Link } from 'react-router-dom';
import { Gavel } from 'lucide-react';

/**
 * Shared shell for the signed-out pages (sign in, register, password reset, 2FA):
 * a dark brand panel on the left and the form on the right. The brand panel is
 * decorative, so it drops out below `lg` and a compact logo takes its place.
 *
 * Props:
 *   heading    — brand-panel headline (node, so callers can control line breaks)
 *   blurb      — one supporting line under the headline
 *   highlights — optional [{ icon, title, text }] list under the blurb
 *   children   — the form column content
 */
export default function AuthLayout({ heading, blurb, highlights = [], children }) {
  return (
    <div className="min-h-screen grid lg:grid-cols-2">
      <div className="relative hidden lg:flex flex-col justify-between overflow-hidden bg-ink-900 text-white p-12">
        <div
          className="absolute inset-0"
          style={{
            backgroundImage:
              'radial-gradient(40rem 30rem at 20% 15%, #1d4dd8, transparent 62%), radial-gradient(34rem 26rem at 85% 85%, rgba(249,126,7,0.4), transparent 60%)',
          }}
        />
        <Link to="/" className="relative flex items-center gap-2.5 w-fit">
          <span className="grid place-items-center w-10 h-10 rounded-xl bg-white/15 backdrop-blur-sm border border-white/20">
            <Gavel size={20} />
          </span>
          <span className="font-display font-extrabold text-xl">AuctionHub</span>
        </Link>

        <div className="relative">
          <h2 className="font-display text-4xl font-extrabold leading-tight tracking-tight">
            {heading}
          </h2>
          {blurb && (
            <p className="text-white/60 mt-4 max-w-sm leading-relaxed">{blurb}</p>
          )}

          {highlights.length > 0 && (
            <div className="mt-10 space-y-5">
              {highlights.map(({ icon: Icon, title, text }) => (
                <div key={title} className="flex items-start gap-3">
                  <span className="grid place-items-center w-9 h-9 rounded-xl bg-white/10 border border-white/15 shrink-0">
                    <Icon size={16} className="text-accent-300" />
                  </span>
                  <div>
                    <p className="font-semibold text-sm">{title}</p>
                    <p className="text-sm text-white/55">{text}</p>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        <p className="relative text-xs text-white/40">© 2026 AuctionHub Inc.</p>
      </div>

      <div className="flex items-center justify-center px-4 py-12 sm:px-8">
        <div className="w-full max-w-md animate-fade-up">
          <Link to="/" className="lg:hidden flex items-center justify-center gap-2 mb-8">
            <span className="grid place-items-center w-9 h-9 rounded-xl bg-gradient-to-br from-primary-500 to-primary-700 text-white">
              <Gavel size={18} />
            </span>
            <span className="font-display font-extrabold text-lg text-ink-900">
              Auction<span className="text-primary-600">Hub</span>
            </span>
          </Link>

          {children}
        </div>
      </div>
    </div>
  );
}
