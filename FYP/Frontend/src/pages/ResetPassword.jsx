import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { AlertCircle, CheckCircle2, ArrowLeft } from 'lucide-react';
import { resetPassword, forgotPassword } from '../api/auth';
import AuthLayout from '../components/AuthLayout';
import CodeInput from '../components/CodeInput';
import PasswordField from '../components/PasswordField';
import { apiErrorMessage } from '../utils/apiError';

/** Server-side rejections that mean "the code is the problem", not the password. */
const CODE_ERROR = /code|otp|expire/i;

export default function ResetPassword() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const emailFromLink = searchParams.get('email') || '';

  // 'code' collects the emailed OTP, 'password' the new password. Both are sent
  // together in the single /auth/reset-password call the backend expects.
  const [step, setStep] = useState('code');
  const [form, setForm] = useState({
    identifier:         emailFromLink,
    newPassword:        '',
    confirmNewPassword: '',
    otp:                '',
  });
  const [error, setError]       = useState('');
  const [loading, setLoading]   = useState(false);
  const [sending, setSending]   = useState(false);
  const [codeSent, setCodeSent] = useState(false);

  const set = (k) => (e) => setForm(f => ({ ...f, [k]: e.target.value }));

  const handleSendCode = async () => {
    if (!form.identifier) { setError('Please enter your email first.'); return; }
    setError(''); setCodeSent(false); setSending(true);
    try {
      await forgotPassword(form.identifier);
      setCodeSent(true);
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not send code. Please try again.'));
    } finally {
      setSending(false);
    }
  };

  const handleContinue = (e) => {
    e.preventDefault();
    if (!form.identifier) { setError('Please enter your email first.'); return; }
    if (form.otp.length !== 6) { setError('Enter the 6-digit code from your email.'); return; }
    setError(''); setCodeSent(false); setStep('password');
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
      const msg = apiErrorMessage(err, 'Reset failed. The code may have expired.');
      setError(msg);
      // A bad or expired code is fixed on the previous step, so go back to it.
      if (CODE_ERROR.test(msg)) { setForm(f => ({ ...f, otp: '' })); setStep('code'); }
    } finally {
      setLoading(false);
    }
  };

  const alerts = (
    <>
      {error && (
        <div className="alert-error mb-4">
          <AlertCircle size={16} className="mt-0.5 shrink-0" />
          <span>{error}</span>
        </div>
      )}
      {codeSent && !error && (
        <div className="alert-success mb-4">
          <CheckCircle2 size={16} className="mt-0.5 shrink-0" />
          <span>We sent a new code to your email.</span>
        </div>
      )}
    </>
  );

  return (
    <AuthLayout
      heading={<>One more step.</>}
      blurb="Enter the code we emailed you, then choose a new password. Codes expire, so use the newest one."
    >
      {step === 'code' ? (
        <>
          <h1 className="font-display text-3xl font-bold text-ink-900">Enter your code</h1>
          <p className="text-sm text-ink-500 mt-2 mb-8">
            We sent a 6-digit code to {emailFromLink ? <span className="font-medium text-ink-700">{emailFromLink}</span> : 'your email'}.
          </p>

          {alerts}

          <form onSubmit={handleContinue} className="space-y-5">
            {!emailFromLink && (
              <div>
                <label className="field-label" htmlFor="reset-email">Email address</label>
                <input
                  id="reset-email"
                  type="email"
                  placeholder="you@example.com"
                  value={form.identifier}
                  onChange={set('identifier')}
                  autoComplete="email"
                  required
                  className="input-field"
                />
              </div>
            )}

            <CodeInput
              id="reset-otp"
              value={form.otp}
              onChange={otp => { setForm(f => ({ ...f, otp })); setError(''); }}
              autoFocus={Boolean(emailFromLink)}
              invalid={Boolean(error) && CODE_ERROR.test(error)}
            />

            <button
              type="submit"
              disabled={form.otp.length !== 6}
              className="btn-primary btn-block btn-lg"
            >
              Verify and continue
            </button>
          </form>

          <button
            type="button"
            onClick={handleSendCode}
            disabled={sending}
            className="mx-auto mt-6 block text-sm font-semibold text-primary-600 hover:text-primary-700 hover:underline underline-offset-2 disabled:opacity-50"
          >
            {sending ? 'Sending…' : 'Resend code'}
          </button>

          <p className="text-center text-sm mt-3">
            <Link to="/login" className="text-ink-400 hover:text-ink-600 transition-colors">Back to sign in</Link>
          </p>
        </>
      ) : (
        <>
          <h1 className="font-display text-3xl font-bold text-ink-900">Choose a new password</h1>
          <p className="text-sm text-ink-500 mt-2 mb-8">
            Last step — pick a password you haven’t used here before.
          </p>

          {alerts}

          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="field-label" htmlFor="reset-new">New password</label>
              <PasswordField
                id="reset-new"
                placeholder="Create a password"
                value={form.newPassword}
                onChange={set('newPassword')}
                autoComplete="new-password"
                required
              />
              <p className="field-hint">
                Password should be at least least 8 characters including uppercase, lowercase, a number, and a special character.
              </p>
            </div>

            <div>
              <label className="field-label" htmlFor="reset-confirm">Confirm password</label>
              <PasswordField
                id="reset-confirm"
                placeholder="Re-enter your password"
                value={form.confirmNewPassword}
                onChange={set('confirmNewPassword')}
                autoComplete="new-password"
                required
              />
            </div>

            <button type="submit" disabled={loading} className="btn-primary btn-block btn-lg">
              {loading ? 'Resetting…' : 'Reset password'}
            </button>
          </form>

          <button
            type="button"
            onClick={() => { setStep('code'); setError(''); }}
            className="mx-auto mt-6 flex items-center gap-1.5 text-sm font-medium text-ink-500 hover:text-ink-800 transition-colors"
          >
            <ArrowLeft size={14} /> Back to the code
          </button>
        </>
      )}
    </AuthLayout>
  );
}
