import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { Eye, EyeOff, CheckCircle2, AlertCircle, ShieldCheck, Sparkles, TrendingUp } from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import AuthLayout from '../components/AuthLayout';
import GoogleSignInButton from '../components/GoogleSignInButton';
import { oauthLogin } from '../api/auth';
import { apiErrorMessage } from '../utils/apiError';

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
      setError(apiErrorMessage(err, 'Google sign-in failed.'));
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
      setError(apiErrorMessage(err, 'Invalid email or password.'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <AuthLayout
      heading={<>Welcome back to the<br />bidding floor.</>}
      blurb="Pick up where you left off — your watchlist, active bids and orders are waiting."
      highlights={HIGHLIGHTS}
    >
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
          <Link to="/forgot-password" className="text-sm font-medium text-ink-600 hover:text-primary-600 transition-colors">
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
    </AuthLayout>
  );
}
