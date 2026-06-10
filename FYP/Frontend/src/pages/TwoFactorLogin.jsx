import { useState } from 'react';
import { Link, useNavigate, useLocation } from 'react-router-dom';
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
      setUser(res.data);
      const { role } = res.data;
      if (role === 'ADMIN') navigate('/admin');
      else if (role === 'SELLER') navigate('/seller/dashboard');
      else navigate('/');
    } catch (err) {
      setError(err.response?.data?.error || 'Invalid code. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-gray-100 flex items-center justify-center px-4">
      <div className="bg-white rounded-3xl shadow-sm p-10 w-full max-w-md">
        <div className="flex justify-center mb-2">
          <Link to="/" className="flex items-center gap-2 text-blue-500 font-bold text-xl">
            <span>⚒</span> AuctionHub
          </Link>
        </div>

        <h1 className="text-2xl font-bold text-center text-gray-900 mt-8 mb-2">Check your email</h1>
        <p className="text-center text-gray-500 text-sm mb-2">
          We sent a 6-digit verification code to
        </p>
        <p className="text-center text-gray-800 font-medium text-sm mb-6">
          {maskedEmail || 'your registered email'}
        </p>

        {devOtp && (
          <div className="bg-yellow-50 border border-yellow-200 text-yellow-800 text-xs px-4 py-2 rounded-lg mb-4">
            Dev mode — OTP: <span className="font-mono font-bold">{devOtp}</span>
          </div>
        )}

        {error && (
          <div className="bg-red-50 text-red-600 text-sm px-4 py-2 rounded-lg mb-4">{error}</div>
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
            className="w-full bg-blue-400 hover:bg-blue-500 text-white font-medium py-3 rounded-full transition-colors disabled:opacity-50"
          >
            {loading ? 'Verifying…' : 'Verify'}
          </button>
        </form>

        <p className="text-center text-sm text-gray-400 mt-6">
          <Link to="/login" className="text-blue-500 hover:underline">Back to login</Link>
        </p>
      </div>
    </div>
  );
}
