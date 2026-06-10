import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

export default function Login() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const justRegistered = searchParams.get('registered') === '1';
  const [form, setForm] = useState({ email: '', password: '' });
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const user = await login(form.email, form.password);
      if (user?.requires2fa) navigate('/2fa-verify', { state: { maskedEmail: user.maskedEmail, devOtp: user.devOtp } });
      else if (user?.role === 'ADMIN') navigate('/admin');
      else if (user?.role === 'SELLER') navigate('/seller/dashboard');
      else navigate('/');
    } catch (err) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Invalid email or password.');
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
        <h1 className="text-2xl font-bold text-center text-gray-900 mt-8 mb-2">Sign in your account</h1>
        <p className="text-center text-gray-500 text-sm mb-6">
          New to AuctionHub?{' '}
          <Link to="/register" className="border border-gray-300 rounded-full px-3 py-1 text-gray-700 text-xs hover:bg-gray-50">
            Create Account
          </Link>
        </p>

        {justRegistered && (
          <div className="bg-green-50 text-green-700 text-sm px-4 py-2 rounded-lg mb-4">
            Account created successfully. Please sign in.
          </div>
        )}

        {error && <div className="bg-red-50 text-red-600 text-sm px-4 py-2 rounded-lg mb-4">{error}</div>}

        <form onSubmit={handleSubmit} className="space-y-4">
          <input
            type="email"
            placeholder="Email address"
            value={form.email}
            onChange={e => setForm(f => ({ ...f, email: e.target.value }))}
            required
            className="input-field"
          />
          <input
            type="password"
            placeholder="Password"
            value={form.password}
            onChange={e => setForm(f => ({ ...f, password: e.target.value }))}
            required
            className="input-field"
          />
          <button
            type="submit"
            disabled={loading}
            className="w-full bg-blue-400 hover:bg-blue-500 text-white font-medium py-3 rounded-full transition-colors disabled:opacity-50"
          >
            {loading ? 'Signing in…' : 'Login'}
          </button>
        </form>

        <div className="flex items-center justify-between mt-4">
          <label className="flex items-center gap-2 text-sm text-gray-500 cursor-pointer">
            <input type="checkbox" className="rounded" /> Remember me
          </label>
          <Link to="/reset-password" className="text-sm text-gray-700 underline hover:text-blue-500">
            Forgot password?
          </Link>
        </div>
      </div>
    </div>
  );
}
