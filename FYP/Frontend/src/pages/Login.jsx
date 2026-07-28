import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { Eye, EyeOff, Gavel, CheckCircle2, AlertCircle, ShieldCheck, Sparkles, TrendingUp } from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import GoogleSignInButton from '../components/GoogleSignInButton';
import { oauthLogin } from '../api/auth';

const HIGHLIGHTS = [
  { icon: TrendingUp, title: 'Live bidding', text: 'Prices update in real time as buyers compete.' },
  { icon: ShieldCheck, title: 'Protected accounts', text: 'Two-factor authentication and encrypted personal data.' },
  { icon: Sparkles, title: 'Personalised picks', text: 'Recommendations from what you bid on and watch.' },
];

/**
 * Remembers only the email address so the form prefills on the next visit.
 * The session itself stays tab-scoped — nothing about sign-in is persisted.
 */
const REMEMBERED_EMAIL_KEY = 'auctionhub.rememberedEmail';

export default function Login() {
  const { login, setUser } = useAuth();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const justRegistered = searchParams.get('registered') === '1';
  const rememberedEmail = localStorage.getItem(REMEMBERED_EMAIL_KEY) ?? '';
  const [rememberEmail, setRememberEmail] = useState(Boolean(rememberedEmail));
  const [form, setForm] = useState({ email: rememberedEmail, password: '' });
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [googleAvailable, setGoogleAvailable] = useState(true);

  const blockPasswordClipboard = (e) => e.preventDefault();

  const handleGoogleCredential = async (credential) => {
    setError('');
    try {
      const res = await oauthLogin('google', credential);
      if (res.data?.token) sessionStorage.setItem('authToken', res.data.token);
      setUser(res.data);
      if (res.data?.role === 'ADMIN') navigate('/admin');
      else navigate('/');
    } catch (err) {
      setError(err.response?.data?.error || 'Google sign-in failed.');
    }
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const user = await login(form.email, form.password);
      if (rememberEmail) localStorage.setItem(REMEMBERED_EMAIL_KEY, form.email);
      else localStorage.removeItem(REMEMBERED_EMAIL_KEY);
      if (user?.requires2fa) navigate('/2fa-verify', { state: { maskedEmail: user.maskedEmail, devOtp: user.devOtp } });
      else if (user?.role === 'ADMIN') navigate('/admin');
      else navigate('/');
    } catch (err) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Invalid email or password.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen grid lg:grid-cols-2">
      {/* Brand panel */}
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
            Welcome back to the<br />bidding floor.
          </h2>
          <p className="text-white/60 mt-4 max-w-sm leading-relaxed">
            Pick up where you left off — your watchlist, active bids and orders are waiting.
          </p>

          <div className="mt-10 space-y-5">
            {HIGHLIGHTS.map(({ icon: Icon, title, text }) => (
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
        </div>

        <p className="relative text-xs text-white/40">© 2026 AuctionHub Inc.</p>
      </div>

      {/* Form panel */}
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

          <h1 className="font-display text-3xl font-bold text-ink-900">Sign in</h1>
          <p className="text-sm text-ink-500 mt-2 mb-8">
            New to AuctionHub?{' '}
            <Link to="/register" className="link-subtle">Create an account</Link>
          </p>

          {justRegistered && (
            <div className="alert-success mb-4">
              <CheckCircle2 size={16} className="mt-0.5 shrink-0" />
              <span>Account created successfully. Please sign in.</span>
            </div>
          )}

          {error && (
            <div className="alert-error mb-4">
              <AlertCircle size={16} className="mt-0.5 shrink-0" />
              <span>{error}</span>
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="field-label" htmlFor="login-email">Email address</label>
              <input
                id="login-email"
                type="email"
                placeholder="Enter your email"
                value={form.email}
                onChange={e => setForm(f => ({ ...f, email: e.target.value }))}
                required
                className="input-field"
              />
            </div>
            <div>
              <label className="field-label" htmlFor="login-password">Password</label>
              <div className="relative">
                <input
                  id="login-password"
                  type={showPassword ? 'text' : 'password'}
                  placeholder="Enter your password"
                  value={form.password}
                  onChange={e => setForm(f => ({ ...f, password: e.target.value }))}
                  onCopy={blockPasswordClipboard}
                  onCut={blockPasswordClipboard}
                  onPaste={blockPasswordClipboard}
                  autoComplete="current-password"
                  required
                  className="input-field pr-11"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword(v => !v)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-ink-400 hover:text-ink-600 p-1 rounded"
                  aria-label={showPassword ? 'Hide password' : 'Show password'}
                  tabIndex={-1}
                >
                  {showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
                </button>
              </div>
            </div>

            <div className="flex items-center justify-between pt-1">
              <label className="flex items-center gap-2 text-sm text-ink-600 cursor-pointer select-none">
                <input
                  type="checkbox"
                  checked={rememberEmail}
                  onChange={e => setRememberEmail(e.target.checked)}
                  className="w-4 h-4 rounded border-ink-300 text-primary-600 focus:ring-primary-500/40"
                />
                Remember my email
              </label>
              <Link to="/reset-password" className="text-sm font-medium text-ink-600 hover:text-primary-600 transition-colors">
                Forgot password?
              </Link>
            </div>

            <button type="submit" disabled={loading} className="btn-primary btn-block btn-lg">
              {loading ? 'Signing in…' : 'Sign in'}
            </button>
          </form>

          <div className={googleAvailable ? 'mt-8' : 'hidden'}>
            <div className="flex items-center gap-3 mb-5">
              <div className="flex-1 h-px bg-ink-200" />
              <span className="text-xs font-semibold text-ink-400 uppercase tracking-wider">or continue with</span>
              <div className="flex-1 h-px bg-ink-200" />
            </div>
            <GoogleSignInButton
              onCredential={handleGoogleCredential}
              onAvailabilityChange={setGoogleAvailable}
            />
            <p className="text-center text-xs text-ink-400 mt-3">
              Google sign-in works after linking it in Account Settings.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}
