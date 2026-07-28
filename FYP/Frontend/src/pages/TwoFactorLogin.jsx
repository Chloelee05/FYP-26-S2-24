import { useState } from 'react';
import { Link, useNavigate, useLocation } from 'react-router-dom';
import { Gavel, MailCheck, AlertCircle } from 'lucide-react';
import { verifyLogin } from '../api/twoFactor';
import { useAuth } from '../context/AuthContext';

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
    <div className="min-h-screen flex items-center justify-center px-4 py-10">
      <div className="w-full max-w-md animate-fade-up">
        <Link to="/" className="flex items-center justify-center gap-2 mb-7">
          <span className="grid place-items-center w-10 h-10 rounded-xl bg-gradient-to-br from-primary-500 to-primary-700 text-white shadow-sm">
            <Gavel size={19} />
          </span>
          <span className="font-display font-extrabold text-lg text-ink-900">
            Auction<span className="text-primary-600">Hub</span>
          </span>
        </Link>

        <div className="card p-7 sm:p-9 text-center">
          <div className="grid place-items-center w-14 h-14 rounded-2xl bg-primary-50 text-primary-600 mx-auto mb-5">
            <MailCheck size={24} />
          </div>
          <h1 className="font-display text-2xl font-bold text-ink-900 mb-2">Check your email</h1>
          <p className="text-ink-500 text-sm">
            We sent a 6-digit verification code to
          </p>
          <p className="text-ink-900 font-semibold text-sm mt-1 mb-6">
            {maskedEmail || 'your registered email'}
          </p>

        {devOtp && (
          <div className="alert-warning text-xs mb-4">
            <span>Dev mode — OTP: <span className="font-mono font-bold">{devOtp}</span></span>
          </div>
        )}

        {error && (
          <div className="alert-error mb-4 text-left">
            <AlertCircle size={16} className="mt-0.5 shrink-0" />
            <span>{error}</span>
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-4">
          <input
            type="text"
            inputMode="numeric"
            placeholder="000000"
            value={code}
            onChange={e => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
            maxLength={6}
            required
            className="input-field text-center text-2xl tracking-[0.4em] font-mono"
          />
          <button
            type="submit"
            disabled={loading || code.length !== 6}
            className="btn-primary btn-block btn-lg"
          >
            {loading ? 'Verifying…' : 'Verify'}
          </button>
        </form>

          <p className="text-center text-sm text-ink-400 mt-6">
            <Link to="/login" className="link-subtle">Back to sign in</Link>
          </p>
        </div>
      </div>
    </div>
  );
}
