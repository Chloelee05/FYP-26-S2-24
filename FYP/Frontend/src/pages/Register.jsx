/*
 * Registration at "/register". Public, no navbar, rendered inside AuthLayout.
 * Posts to /api/auth/register. There is one account type: a new account can bid straight away and
 * turns selling on later from Account settings or the My listings gate, so the form never
 * asks the visitor to choose a role.
 * Accepting the terms is required and links to the /terms and /privacy pages. On success the
 * page shows a confirmation dialog and then sends the user to /login?registered=1 rather
 * than signing them in automatically.
 */
import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { AlertCircle, DollarSign, Zap, ShieldCheck } from 'lucide-react';
import { register } from '../api/auth';
import AuthLayout from '../components/AuthLayout';
import PasswordField from '../components/PasswordField';
import SuccessModal from '../components/SuccessModal';

// There is only one account type, so the brand panel explains what the single
// account can do instead of asking the user to pick a role up front.
const HIGHLIGHTS = [
  { icon: DollarSign, title: 'Sell items', text: 'Switch on later in Settings.' },
  { icon: Zap, title: 'Bid instantly', text: 'Full access to every auction type.' },
  { icon: ShieldCheck, title: 'Protected accounts', text: 'Two-factor authentication available.' },
];

export default function Register() {
  const navigate = useNavigate();
  const [form, setForm] = useState({ username: '', email: '', password: '', confirmPassword: '', termsAccept: false });
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [showSuccess, setShowSuccess] = useState(false);

  // Checks the terms box and the password match locally, then registers. The termsAccept
  // boolean is sent as the string 'on' because the servlet reads it as a form checkbox value.
  // A missing err.response means the request never reached Tomcat, which during development
  // usually means the backend is not running, so that case gets its own message.
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
    <AuthLayout
      heading={<>Ready the moment<br />you sign in.</>}
      blurb="One account for buying and selling — no second sign-up, no role to choose now."
      highlights={HIGHLIGHTS}
    >
      {showSuccess && (
        <SuccessModal
          title="Account created successfully!"
          message="You can now sign in. Buying is ready straight away, and My listings in the navigation bar switches selling on whenever you want it — there is no second account to create."
          buttonLabel="Go to Sign In"
          onClose={() => navigate('/login?registered=1')}
        />
      )}

      <h1 className="font-display text-3xl font-bold text-ink-900">Create an account</h1>
      <p className="text-sm text-ink-500 mt-2 mb-8">
        Already registered?{' '}
        <Link to="/login" className="link-subtle">Sign in</Link>
      </p>

      {error && (
        <div className="alert-error mb-4">
          <AlertCircle size={16} className="mt-0.5 shrink-0" />
          <span>{error}</span>
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-4">
        <div>
          <label className="field-label" htmlFor="reg-name">Username</label>
          <input
            id="reg-name"
            placeholder="username"
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
            placeholder="email"
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
            Password should be at least least 8 characters including uppercase, lowercase, a number, and a special character.
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
          {loading ? 'Creating account…' : 'Create account'}
        </button>
      </form>
    </AuthLayout>
  );
}
