import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Gavel, AlertCircle, ShoppingCart, DollarSign } from 'lucide-react';
import { register } from '../api/auth';
import PasswordField from '../components/PasswordField';
import SuccessModal from '../components/SuccessModal';

export default function Register() {
  const navigate = useNavigate();
  const [form, setForm] = useState({ username: '', email: '', password: '', confirmPassword: '', termsAccept: false });
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [showSuccess, setShowSuccess] = useState(false);

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!form.termsAccept) { setError('You must accept the terms to continue.'); return; }
    if (form.password !== form.confirmPassword) { setError('Passwords do not match.'); return; }
    setError(''); setLoading(true);
    try {
      // One account type: everyone starts as a buyer and can enable selling later.
      await register({ ...form, termsAccept: 'on' });
      setShowSuccess(true);
    } catch (err) {
      if (!err.response) {
        setError('Cannot reach the server. Start Tomcat (port 8080) and run this page via npm run dev (port 3000).');
        return;
      }
      const data = err.response?.data;
      const msg = (typeof data === 'object' && data)
        ? (data.error || data.message)
        : null;
      setError(msg || `Registration failed (HTTP ${err.response.status}). Please try again.`);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center px-4 py-10">
      {showSuccess && (
        <SuccessModal
          title="Account created successfully!"
          message="You can now sign in. Buying is ready straight away, and Sell Items in the navigation bar switches selling on whenever you want it — there is no second account to create."
          buttonLabel="Go to Sign In"
          onClose={() => navigate('/login?registered=1')}
        />
      )}

      <div className="w-full max-w-lg animate-fade-up">
        <div className="flex items-center justify-between mb-6">
          <Link to="/" className="flex items-center gap-2.5">
            <span className="grid place-items-center w-10 h-10 rounded-xl bg-gradient-to-br from-primary-500 to-primary-700 text-white shadow-sm">
              <Gavel size={19} />
            </span>
            <span className="font-display font-extrabold text-lg text-ink-900">
              Auction<span className="text-primary-600">Hub</span>
            </span>
          </Link>
          <p className="text-sm text-ink-500">
            Have an account?{' '}
            <Link to="/login" className="link-subtle">Sign in</Link>
          </p>
        </div>

        <div className="card p-7 sm:p-9">
          <h1 className="font-display text-2xl sm:text-3xl font-bold text-ink-900 text-center">
            Create your account
          </h1>
          <p className="text-center text-sm text-ink-500 mt-2 mb-6">
            One account for buying and selling.
          </p>

          {/* One account type. Selling is an opt-in capability, not a second account. */}
          <div className="grid grid-cols-2 gap-3 mb-6">
            <div className="flex flex-col items-center gap-1.5 p-4 rounded-2xl bg-primary-50/70 ring-1 ring-inset ring-primary-100">
              <ShoppingCart size={22} className="text-primary-600" />
              <span className="font-bold text-sm text-ink-900">Bid &amp; buy</span>
              <span className="text-xs text-ink-500 text-center">Ready the moment you sign in</span>
            </div>
            <div className="flex flex-col items-center gap-1.5 p-4 rounded-2xl bg-ink-50 ring-1 ring-inset ring-ink-200">
              <DollarSign size={22} className="text-ink-400" />
              <span className="font-bold text-sm text-ink-900">Sell items</span>
              <span className="text-xs text-ink-500 text-center">Switch on in one click, any time</span>
            </div>
          </div>

          {error && (
            <div className="alert-error mb-4">
              <AlertCircle size={16} className="mt-0.5 shrink-0" />
              <span>{error}</span>
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="field-label" htmlFor="reg-name">Full name</label>
              <input
                id="reg-name"
                placeholder="Jane Tan"
                value={form.username}
                onChange={e => setForm(f => ({ ...f, username: e.target.value }))}
                required
                className="input-field"
              />
            </div>
            <div>
              <label className="field-label" htmlFor="reg-email">Email address</label>
              <input
                id="reg-email"
                type="email"
                placeholder="you@example.com"
                value={form.email}
                onChange={e => setForm(f => ({ ...f, email: e.target.value }))}
                required
                className="input-field"
              />
            </div>
            <div>
              <label className="field-label" htmlFor="reg-password">Password</label>
              <PasswordField
                id="reg-password"
                placeholder="Create a password"
                value={form.password}
                onChange={e => setForm(f => ({ ...f, password: e.target.value }))}
                required
                autoComplete="new-password"
              />
              <p className="field-hint">
                8–128 characters with uppercase, lowercase, a number, and a special character (!@#$%^&amp;* etc.)
              </p>
            </div>
            <div>
              <label className="field-label" htmlFor="reg-confirm">Confirm password</label>
              <PasswordField
                id="reg-confirm"
                placeholder="Re-enter your password"
                value={form.confirmPassword}
                onChange={e => setForm(f => ({ ...f, confirmPassword: e.target.value }))}
                required
                autoComplete="new-password"
              />
            </div>

            <label className="flex items-start gap-2.5 text-sm text-ink-600 cursor-pointer select-none pt-1">
              <input
                type="checkbox"
                checked={form.termsAccept}
                onChange={e => setForm(f => ({ ...f, termsAccept: e.target.checked }))}
                className="mt-0.5 w-4 h-4 rounded border-ink-300 text-primary-600 focus:ring-primary-500/40"
              />
              <span>
                I agree to the{' '}
                <Link to="/terms" className="link-subtle">User Agreement</Link>{' '}
                and{' '}
                <Link to="/privacy" className="link-subtle">Privacy Notices</Link>
              </span>
            </label>

            <button type="submit" disabled={loading} className="btn-primary btn-block btn-lg">
              {loading ? 'Creating account…' : 'Create Account'}
            </button>
          </form>
        </div>
      </div>
    </div>
  );
}
