import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ShoppingCart, DollarSign } from 'lucide-react';
import { register } from '../api/auth';

export default function Register() {
  const navigate = useNavigate();
  const [role, setRole] = useState('BUYER');
  const [form, setForm] = useState({ username: '', email: '', password: '', confirmPassword: '', termsAccept: false });
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!form.termsAccept) { setError('You must accept the terms to continue.'); return; }
    if (form.password !== form.confirmPassword) { setError('Passwords do not match.'); return; }
    setError(''); setLoading(true);
    try {
      await register({ ...form, role, termsAccept: 'on' });
      navigate('/login?registered=1');
    } catch (err) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Registration failed. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-gray-100 flex items-center justify-center px-4 py-8">
      <div className="bg-white rounded-3xl shadow-sm p-8 w-full max-w-md">
        <p className="text-right text-sm text-gray-500 mb-2">
          Already have an account?{' '}
          <Link to="/login" className="text-blue-500 font-medium hover:underline">Sign in</Link>
        </p>
        <h1 className="text-3xl font-bold text-center mb-1">
          Welcome to <span className="text-blue-500">AuctionHub</span>
        </h1>
        <p className="text-center text-gray-600 mb-6">I want to:</p>

        <div className="grid grid-cols-2 gap-3 mb-4">
          {[
            { value: 'BUYER', icon: ShoppingCart, label: 'Buy Items', sub: 'Register as Buyer' },
            { value: 'SELLER', icon: DollarSign, label: 'Sell Items', sub: 'Register as Seller' },
          ].map(({ value, icon: Icon, label, sub }) => (
            <button
              key={value}
              type="button"
              onClick={() => setRole(value)}
              className={`flex flex-col items-center gap-2 p-5 rounded-2xl border-2 transition-all ${role === value ? 'border-blue-500 bg-blue-50' : 'border-gray-200 hover:border-gray-300'}`}
            >
              <Icon size={28} className={role === value ? 'text-blue-500' : 'text-gray-400'} />
              <span className="font-bold text-sm">{label}</span>
              <span className="text-xs text-gray-500">{sub}</span>
            </button>
          ))}
        </div>

        <div className="bg-blue-50 text-blue-700 text-sm text-center py-2 rounded-lg mb-5">
          Selected Account Type: <strong>{role === 'BUYER' ? 'Buyer' : 'Seller'}</strong>
        </div>

        <h2 className="font-bold text-xl mb-4">Create an account</h2>

        {error && <div className="bg-red-50 text-red-600 text-sm px-4 py-2 rounded-lg mb-4">{error}</div>}

        <form onSubmit={handleSubmit} className="space-y-3">
          <input
            placeholder="Full Name"
            value={form.username}
            onChange={e => setForm(f => ({ ...f, username: e.target.value }))}
            required
            className="input-field"
          />
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
          <p className="text-xs text-gray-500 -mt-1">
            8–128 characters with uppercase, lowercase, a number, and a special character (!@#$%^&* etc.)
          </p>
          <input
            type="password"
            placeholder="Confirm Password"
            value={form.confirmPassword}
            onChange={e => setForm(f => ({ ...f, confirmPassword: e.target.value }))}
            required
            className="input-field"
          />
          <label className="flex items-start gap-2 text-sm text-gray-600 cursor-pointer">
            <input
              type="checkbox"
              checked={form.termsAccept}
              onChange={e => setForm(f => ({ ...f, termsAccept: e.target.checked }))}
              className="mt-0.5 rounded"
            />
            <span>
              I agree to the{' '}
              <Link to="/terms" className="text-blue-500 underline">User Agreement</Link>{' '}
              and{' '}
              <Link to="/privacy" className="text-blue-500 underline">Privacy Notices</Link>
            </span>
          </label>
          <button
            type="submit"
            disabled={loading}
            className="w-full bg-blue-400 hover:bg-blue-500 text-white font-medium py-3 rounded-full transition-colors disabled:opacity-50"
          >
            {loading ? 'Creating account…' : 'Create Account'}
          </button>
        </form>
      </div>
    </div>
  );
}
