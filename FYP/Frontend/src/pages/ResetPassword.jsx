import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { Gavel, KeyRound, AlertCircle, CheckCircle2 } from 'lucide-react';
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
          <div className="grid place-items-center w-12 h-12 rounded-2xl bg-primary-50 text-primary-600 mb-4">
            <KeyRound size={22} />
          </div>
          <h1 className="font-display text-2xl font-bold text-ink-900">Reset your password</h1>
          <p className="text-sm text-ink-500 mt-1.5 mb-6">
            We’ll email you a 6-digit code to confirm it’s you.
          </p>

          {error && (
            <div className="alert-error mb-4">
              <AlertCircle size={16} className="mt-0.5 shrink-0" />
              <span>{error}</span>
            </div>
          )}
          {codeSent && !error && (
            <div className="alert-success mb-4">
              <CheckCircle2 size={16} className="mt-0.5 shrink-0" />
              <span>Verification code sent to your email.</span>
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="field-label" htmlFor="reset-email">Email</label>
              <input
                id="reset-email"
                type="email"
                placeholder="you@example.com"
                value={form.identifier}
                onChange={set('identifier')}
                required
                className="input-field"
              />
            </div>

            <div>
              <label className="field-label" htmlFor="reset-new">New password</label>
              <input
                id="reset-new"
                type="password"
                value={form.newPassword}
                onChange={set('newPassword')}
                required
                className="input-field"
              />
            </div>

            <div>
              <label className="field-label" htmlFor="reset-confirm">Confirm password</label>
              <input
                id="reset-confirm"
                type="password"
                value={form.confirmNewPassword}
                onChange={set('confirmNewPassword')}
                required
                className="input-field"
              />
            </div>

            <div>
              <label className="field-label" htmlFor="reset-otp">Verification code</label>
              <div className="flex gap-2">
                <input
                  id="reset-otp"
                  type="text"
                  placeholder="000000"
                  value={form.otp}
                  onChange={set('otp')}
                  required
                  className="input-field flex-1 tracking-[0.3em] font-mono"
                />
                <button
                  type="button"
                  onClick={handleSendCode}
                  disabled={sending}
                  className="btn-secondary shrink-0 whitespace-nowrap"
                >
                  {sending ? 'Sending…' : 'Send code'}
                </button>
              </div>
            </div>

            <button type="submit" disabled={loading} className="btn-primary btn-block btn-lg">
              {loading ? 'Resetting…' : 'Reset Password'}
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
