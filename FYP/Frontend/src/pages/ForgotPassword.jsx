import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { AlertCircle } from 'lucide-react';
import { forgotPassword } from '../api/auth';
import AuthLayout from '../components/AuthLayout';
import { apiErrorMessage } from '../utils/apiError';

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
      setError(apiErrorMessage(err, 'Something went wrong. Please try again.'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <AuthLayout
      heading={<>Forgot your<br />password?</>}
      blurb="We’ll email you a secure 6-digit code to set a new one."
    >
      <h1 className="font-display text-3xl font-bold text-ink-900">Reset password</h1>
      <p className="text-sm text-ink-500 mt-2 mb-8">
        Enter the email linked to your account.
      </p>

      {error && (
        <div className="alert-error mb-4">
          <AlertCircle size={16} className="mt-0.5 shrink-0" />
          <span>{error}</span>
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-5">
        <div>
          <label className="field-label" htmlFor="forgot-email">Email address</label>
          <input
            id="forgot-email"
            type="email"
            placeholder="Enter your email"
            value={email}
            onChange={e => setEmail(e.target.value)}
            autoComplete="email"
            required
            className="input-field"
          />
        </div>

        <button type="submit" disabled={loading} className="btn-primary btn-block btn-lg">
          {loading ? 'Sending…' : 'Send reset code'}
        </button>
      </form>

      <p className="text-center text-sm mt-6">
        <Link to="/login" className="link-subtle">Back to sign in</Link>
      </p>
    </AuthLayout>
  );
}
