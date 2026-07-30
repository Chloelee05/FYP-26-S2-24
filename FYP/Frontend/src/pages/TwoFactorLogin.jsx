import { useState } from 'react';
import { Link, useNavigate, useLocation } from 'react-router-dom';
import { AlertCircle } from 'lucide-react';
import { verifyLogin } from '../api/twoFactor';
import { useAuth } from '../context/AuthContext';
import AuthLayout from '../components/AuthLayout';
import CodeInput from '../components/CodeInput';

export default function TwoFactorLogin() {
  const navigate = useNavigate();
  const location = useLocation();
  const { maskedEmail, devOtp } = location.state || {};
  const { setUser } = useAuth();
  const [code, setCode]     = useState('');
  const [error, setError]   = useState('');
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError(''); setLoading(true);
    try {
      const res = await verifyLogin(code);
      if (res.data?.token) sessionStorage.setItem('authToken', res.data.token);
      setUser(res.data);
      const { role } = res.data;
      if (role === 'ADMIN') navigate('/admin');
      else navigate('/');
    } catch (err) {
      setError(err.response?.data?.error || 'Invalid code. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <AuthLayout
      heading={<>One more step.</>}
      blurb="Two-factor authentication keeps your bids and payment details protected."
    >
      <h1 className="font-display text-3xl font-bold text-ink-900">Enter your code</h1>
      <p className="text-sm text-ink-500 mt-2 mb-8">
        We sent a 6-digit code to{' '}
        <span className="font-medium text-ink-700">{maskedEmail || 'your registered email'}</span>.
      </p>

      {devOtp && (
        <div className="alert-warning mb-4 text-xs">
          <span>Dev mode — OTP: <span className="font-mono font-bold">{devOtp}</span></span>
        </div>
      )}

      {error && (
        <div className="alert-error mb-4">
          <AlertCircle size={16} className="mt-0.5 shrink-0" />
          <span>{error}</span>
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-5">
        <CodeInput
          id="twofa-code"
          value={code}
          onChange={next => { setCode(next); setError(''); }}
          autoFocus
          invalid={Boolean(error)}
        />

        <button
          type="submit"
          disabled={loading || code.length !== 6}
          className="btn-primary btn-block btn-lg"
        >
          {loading ? 'Verifying…' : 'Verify and continue'}
        </button>
      </form>

      {/* The pending code lives with the sign-in attempt, so a fresh one means
          signing in again — there is no separate resend endpoint. */}
      <p className="text-center text-sm text-ink-400 mt-6">
        Didn’t get the code?{' '}
        <Link to="/login" className="link-subtle">Sign in again</Link> to send a new one.
      </p>
    </AuthLayout>
  );
}
