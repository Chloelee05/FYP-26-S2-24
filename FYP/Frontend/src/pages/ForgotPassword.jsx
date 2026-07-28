import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Gavel, AlertCircle } from 'lucide-react';
import { forgotPassword } from '../api/auth';

export default function ForgotPassword() {
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError(''); setLoading(true);
    try {
      await forgotPassword(email);
      navigate(`/reset-password?email=${encodeURIComponent(email)}`);
    } catch (err) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Something went wrong. Please try again.');
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
        <div className="card p-7 sm:p-9">
        <h1 className="font-display text-2xl font-bold text-center text-ink-900 mb-2">Forgot password</h1>
        <p className="text-center text-ink-500 text-sm mb-6">
          Enter your email and we’ll send you a one-time code.
        </p>

        {error && (
          <div className="alert-error mb-4">
            <AlertCircle size={16} className="mt-0.5 shrink-0" />
            <span>{error}</span>
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-4">
          <input
            type="email"
            placeholder="Email address"
            value={email}
            onChange={e => setEmail(e.target.value)}
            required
            className="input-field"
          />
          <button
            type="submit"
            disabled={loading}
            className="btn-primary btn-block btn-lg"
          >
            {loading ? 'Sending…' : 'Send Reset Code'}
          </button>
        </form>
        <p className="text-center mt-5 text-sm">
          <Link to="/login" className="link-subtle">Back to sign in</Link>
        </p>
        </div>
      </div>
    </div>
  );
}
