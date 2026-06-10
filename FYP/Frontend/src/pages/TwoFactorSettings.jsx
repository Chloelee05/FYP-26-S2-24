import { useState } from 'react';
import { Link } from 'react-router-dom';
import { QRCodeSVG } from 'qrcode.react';
import { setup2FA, confirm2FA, disable2FA } from '../api/twoFactor';
import { useAuth } from '../context/AuthContext';

export default function TwoFactorSettings() {
  const { user, setUser } = useAuth();
  const is2FAEnabled = user?.twoFactorEnabled ?? false;

  // setup flow state
  const [step, setStep] = useState('idle'); // idle | setup | confirm
  const [totpUri, setTotpUri]       = useState('');
  const [totpSecret, setTotpSecret] = useState('');
  const [confirmCode, setConfirmCode] = useState('');

  // disable flow state
  const [showDisable, setShowDisable] = useState(false);
  const [disableCode, setDisableCode] = useState('');

  const [message, setMessage] = useState('');
  const [error, setError]     = useState('');
  const [loading, setLoading] = useState(false);

  const clearMessages = () => { setMessage(''); setError(''); };

  // ── Start setup ───────────────────────────────────────────────────────────
  const handleStartSetup = async () => {
    clearMessages(); setLoading(true);
    try {
      const res = await setup2FA();
      setTotpUri(res.data.totpUri);
      setTotpSecret(res.data.totpSecret);
      setStep('setup');
    } catch (err) {
      setError(err.response?.data?.error || 'Could not start 2FA setup.');
    } finally {
      setLoading(false);
    }
  };

  // ── Confirm setup ─────────────────────────────────────────────────────────
  const handleConfirm = async (e) => {
    e.preventDefault();
    clearMessages(); setLoading(true);
    try {
      await confirm2FA(confirmCode);
      setUser(prev => ({ ...prev, twoFactorEnabled: true }));
      setStep('idle');
      setConfirmCode('');
      setMessage('Two-factor authentication is now enabled on your account.');
    } catch (err) {
      setError(err.response?.data?.error || 'Invalid code. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  // ── Disable 2FA ───────────────────────────────────────────────────────────
  const handleDisable = async (e) => {
    e.preventDefault();
    clearMessages(); setLoading(true);
    try {
      await disable2FA(disableCode);
      setUser(prev => ({ ...prev, twoFactorEnabled: false }));
      setShowDisable(false);
      setDisableCode('');
      setMessage('Two-factor authentication has been disabled.');
    } catch (err) {
      setError(err.response?.data?.error || 'Incorrect password. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="max-w-lg mx-auto px-4 py-8">
      <div className="flex items-center gap-3 mb-6">
        <Link to="/profile" className="text-gray-400 hover:text-gray-600 text-sm">← Back to Profile</Link>
      </div>

      <h1 className="text-2xl font-bold text-gray-900 mb-2">Two-Factor Authentication</h1>
      <p className="text-gray-500 text-sm mb-6">
        Add an extra layer of security to your account by requiring a code from your authenticator app when signing in.
      </p>

      {message && (
        <div className="bg-green-50 text-green-700 text-sm px-4 py-3 rounded-lg mb-4">{message}</div>
      )}
      {error && (
        <div className="bg-red-50 text-red-600 text-sm px-4 py-3 rounded-lg mb-4">{error}</div>
      )}

      {/* ── Current status ── */}
      <div className="card p-5 mb-6 flex items-center justify-between">
        <div>
          <p className="font-medium text-gray-900">Status</p>
          <p className="text-sm text-gray-500 mt-0.5">
            {is2FAEnabled ? 'Two-factor authentication is enabled.' : 'Two-factor authentication is disabled.'}
          </p>
        </div>
        <span className={`text-xs font-semibold px-3 py-1 rounded-full ${is2FAEnabled ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'}`}>
          {is2FAEnabled ? 'Enabled' : 'Disabled'}
        </span>
      </div>

      {/* ── Enable flow ── */}
      {!is2FAEnabled && step === 'idle' && (
        <button
          onClick={handleStartSetup}
          disabled={loading}
          className="w-full bg-blue-500 hover:bg-blue-600 text-white font-medium py-3 rounded-lg transition-colors disabled:opacity-50"
        >
          {loading ? 'Setting up…' : 'Enable Two-Factor Authentication'}
        </button>
      )}

      {!is2FAEnabled && step === 'setup' && (
        <div className="card p-6 space-y-5">
          <h2 className="font-bold text-gray-900">Step 1 — Scan the QR code</h2>
          <p className="text-sm text-gray-500">
            Open Google Authenticator, Authy, or any TOTP app and scan the QR code below.
          </p>

          <div className="flex justify-center p-4 bg-white border border-gray-200 rounded-xl">
            <QRCodeSVG value={totpUri} size={180} />
          </div>

          <div>
            <p className="text-xs text-gray-400 mb-1">Can't scan? Enter this key manually:</p>
            <div className="bg-gray-50 border border-gray-200 rounded-lg px-4 py-2 text-sm font-mono text-gray-700 break-all flex items-center justify-between gap-2">
              <span>{totpSecret}</span>
              <button
                type="button"
                onClick={() => navigator.clipboard.writeText(totpSecret).then(() => setMessage('Key copied!'))}
                className="text-blue-500 text-xs whitespace-nowrap hover:underline"
              >
                Copy
              </button>
            </div>
          </div>

          <h2 className="font-bold text-gray-900">Step 2 — Enter the verification code</h2>
          <form onSubmit={handleConfirm} className="space-y-3">
            <input
              type="text"
              inputMode="numeric"
              placeholder="000000"
              value={confirmCode}
              onChange={e => setConfirmCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
              maxLength={6}
              required
              className="input-field text-center text-xl tracking-[0.4em] font-mono"
            />
            <button
              type="submit"
              disabled={loading || confirmCode.length !== 6}
              className="w-full bg-green-600 hover:bg-green-700 text-white font-medium py-3 rounded-lg transition-colors disabled:opacity-50"
            >
              {loading ? 'Verifying…' : 'Confirm & Enable'}
            </button>
            <button
              type="button"
              onClick={() => { setStep('idle'); clearMessages(); }}
              className="w-full border border-gray-200 text-gray-600 font-medium py-3 rounded-lg hover:bg-gray-50 transition-colors"
            >
              Cancel
            </button>
          </form>
        </div>
      )}

      {/* ── Disable flow ── */}
      {is2FAEnabled && (
        <div className="space-y-3">
          {!showDisable ? (
            <button
              onClick={() => { setShowDisable(true); clearMessages(); }}
              className="w-full border border-red-300 text-red-600 font-medium py-3 rounded-lg hover:bg-red-50 transition-colors"
            >
              Disable Two-Factor Authentication
            </button>
          ) : (
            <div className="card p-6 border-red-200 space-y-4">
              <h2 className="font-bold text-gray-900">Disable 2FA</h2>
              <p className="text-sm text-gray-500">Enter your current password to confirm.</p>
              <form onSubmit={handleDisable} className="space-y-3">
                <input
                  type="password"
                  placeholder="Current password"
                  value={disableCode}
                  onChange={e => setDisableCode(e.target.value)}
                  required
                  className="input-field"
                />
                <button
                  type="submit"
                  disabled={loading || !disableCode}
                  className="w-full bg-red-600 hover:bg-red-700 text-white font-medium py-3 rounded-lg transition-colors disabled:opacity-50"
                >
                  {loading ? 'Disabling…' : 'Confirm Disable'}
                </button>
                <button
                  type="button"
                  onClick={() => { setShowDisable(false); setDisableCode(''); clearMessages(); }}
                  className="w-full border border-gray-200 text-gray-600 font-medium py-3 rounded-lg hover:bg-gray-50 transition-colors"
                >
                  Cancel
                </button>
              </form>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
