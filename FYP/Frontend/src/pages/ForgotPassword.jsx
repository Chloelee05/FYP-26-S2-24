import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
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
    <div className="min-h-screen bg-gray-100 flex items-center justify-center px-4">
      <div className="bg-white rounded-3xl shadow-sm p-10 w-full max-w-md">
        <Link to="/" className="flex items-center justify-center gap-2 text-blue-500 font-bold text-xl mb-8">
          <span>⚒</span> AuctionHub
        </Link>
        <h1 className="text-2xl font-bold text-center text-gray-900 mb-2">Forgot Password</h1>
        <p className="text-center text-gray-500 text-sm mb-6">
          Enter your email and we'll send you a one-time code.
        </p>

        {error && <div className="bg-red-50 text-red-600 text-sm px-4 py-2 rounded-lg mb-4">{error}</div>}

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
            className="w-full bg-blue-400 hover:bg-blue-500 text-white font-medium py-3 rounded-full transition-colors disabled:opacity-50"
          >
            {loading ? 'Sending…' : 'Send Reset Code'}
          </button>
        </form>
        <p className="text-center mt-4 text-sm">
          <Link to="/login" className="text-blue-500 underline">Back to login</Link>
        </p>
      </div>
    </div>
  );
}
