import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { resetPassword, forgotPassword } from '../api/auth';

export default function ResetPassword() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [form, setForm] = useState({
    identifier:         searchParams.get('email') || '',
    newPassword:        '',
    confirmNewPassword: '',
    otp:                '',
  });
  const [error, setError]         = useState('');
  const [loading, setLoading]     = useState(false);
  const [sending, setSending]     = useState(false);
  const [codeSent, setCodeSent]   = useState(false);

  const handleSendCode = async () => {
    if (!form.identifier) { setError('Please enter your email first.'); return; }
    setError(''); setSending(true);
    try {
      await forgotPassword(form.identifier);
      setCodeSent(true);
    } catch (err) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Could not send code. Please try again.');
    } finally {
      setSending(false);
    }
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (form.newPassword !== form.confirmNewPassword) { setError('Passwords do not match.'); return; }
    setError(''); setLoading(true);
    try {
      await resetPassword({
        identifier:         form.identifier,
        otp:                form.otp,
        newPassword:        form.newPassword,
        confirmNewPassword: form.confirmNewPassword,
      });
      navigate('/login?reset=1');
    } catch (err) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Reset failed. The code may have expired.');
    } finally {
      setLoading(false);
    }
  };

  const set = (k) => (e) => setForm(f => ({ ...f, [k]: e.target.value }));

  return (
    <div className="min-h-screen bg-gray-100 flex items-center justify-center px-4">
      <div className="bg-white rounded-2xl shadow-md p-10 w-full max-w-md">
        <h1 className="text-3xl font-bold text-gray-900 mb-8">Reset Password</h1>

        {error && (
          <div className="bg-red-50 text-red-600 text-sm px-4 py-2 rounded-lg mb-4">{error}</div>
        )}
        {codeSent && !error && (
          <div className="bg-green-50 text-green-600 text-sm px-4 py-2 rounded-lg mb-4">
            Verification code sent to your email.
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-5">
          {/* Email */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Email</label>
            <input
              type="email"
              value={form.identifier}
              onChange={set('identifier')}
              required
              className="w-full border border-gray-300 rounded-xl px-4 py-3 text-sm focus:outline-none focus:ring-2 focus:ring-blue-300"
            />
          </div>

          {/* New Password */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">New Password</label>
            <input
              type="password"
              value={form.newPassword}
              onChange={set('newPassword')}
              required
              className="w-full border border-gray-300 rounded-xl px-4 py-3 text-sm focus:outline-none focus:ring-2 focus:ring-blue-300"
            />
          </div>

          {/* Confirm Password */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Confirm Password</label>
            <input
              type="password"
              value={form.confirmNewPassword}
              onChange={set('confirmNewPassword')}
              required
              className="w-full border border-gray-300 rounded-xl px-4 py-3 text-sm focus:outline-none focus:ring-2 focus:ring-blue-300"
            />
          </div>

          {/* Verification Code + Send button */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Enter Verification Code</label>
            <div className="flex gap-2">
              <input
                type="text"
                value={form.otp}
                onChange={set('otp')}
                required
                className="flex-1 border border-gray-300 rounded-xl px-4 py-3 text-sm focus:outline-none focus:ring-2 focus:ring-blue-300"
              />
              <button
                type="button"
                onClick={handleSendCode}
                disabled={sending}
                className="bg-blue-300 hover:bg-blue-400 text-white font-medium px-5 py-3 rounded-xl transition-colors disabled:opacity-50 whitespace-nowrap"
              >
                {sending ? 'Sending…' : 'Send code'}
              </button>
            </div>
          </div>

          <button
            type="submit"
            disabled={loading}
            className="w-full bg-blue-300 hover:bg-blue-400 text-white font-bold py-4 rounded-2xl text-base transition-colors disabled:opacity-50 mt-2"
          >
            {loading ? 'Resetting…' : 'Reset Password'}
          </button>
        </form>
      </div>
    </div>
  );
}
