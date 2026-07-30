import { useState, useEffect, useRef, useMemo } from 'react';
import { useNavigate, useSearchParams, Link } from 'react-router-dom';
import { QRCodeSVG } from 'qrcode.react';
import {
  Store, Check, AlertCircle, CheckCircle2, LayoutDashboard, User, KeyRound,
  ShieldCheck, Bell, Trash2, Upload, ArrowLeft, Copy, Loader2, Lock,
} from 'lucide-react';
import { getProfile, updateProfile, uploadProfilePhoto, deleteAccount, enableSelling } from '../api/user';
import { changePassword } from '../api/auth';
import { setup2FA, confirm2FA, disable2FA } from '../api/twoFactor';
import { getNotificationPreferences, saveNotificationPreferences } from '../api/notifications';
import PasswordField from '../components/PasswordField';
import CodeInput from '../components/CodeInput';
import { useAuth } from '../context/AuthContext';
import { apiErrorMessage } from '../utils/apiError';
import { publicPath } from '../utils/appBase';

const TABS = [
  { key: 'profile',       label: 'Profile',          icon: User,        title: 'Profile details',      desc: 'How your account appears to other people on AuctionHub.' },
  { key: 'selling',       label: 'Selling',          icon: Store,       title: 'Selling',              desc: 'One account covers buying and selling — switch selling on when you need it.' },
  { key: 'password',      label: 'Password',         icon: KeyRound,    title: 'Change password',      desc: 'Use a password you don’t reuse anywhere else.' },
  { key: '2fa',           label: 'Two-factor auth',  icon: ShieldCheck, title: 'Two-factor authentication', desc: 'Ask for a code from your authenticator app when signing in.' },
  { key: 'notifications', label: 'Notifications',    icon: Bell,        title: 'Notifications',        desc: 'Choose which events we tell you about, in-app and by email.' },
  { key: 'danger',        label: 'Delete account',   icon: Trash2,      title: 'Delete account',       desc: 'Permanently close your account and remove your personal data.', danger: true },
];

/** Inline success / error pair used by every section, so feedback always looks the same. */
function Feedback({ message, error }) {
  if (!message && !error) return null;
  return error ? (
    <div className="alert-error mb-4">
      <AlertCircle size={16} className="mt-0.5 shrink-0" />
      <span>{error}</span>
    </div>
  ) : (
    <div className="alert-success mb-4">
      <CheckCircle2 size={16} className="mt-0.5 shrink-0" />
      <span>{message}</span>
    </div>
  );
}

// ── Profile details section ───────────────────────────────────────────────────
// The email address is the sign-in identity, so it is shown read-only here and
// left out of the update payload — the server keeps whatever it already has.
const PROFILE_FIELDS = [
  { key: 'username', label: 'Display name', type: 'text',  placeholder: 'Your name',              hint: 'Shown on your bids, reviews and listings.' },
  { key: 'email',    label: 'Email address', type: 'email', placeholder: 'you@example.com',        hint: 'Used for sign-in, receipts and verification codes. This cannot be changed.', locked: true },
  { key: 'phone',    label: 'Phone',         type: 'tel',   placeholder: '+65 XXXX XXXX',          hint: 'Optional — helps sellers reach you about deliveries.' },
  { key: 'address',  label: 'Address',       type: 'text',  placeholder: 'Street, City, Country',  hint: 'Optional — used as the default shipping address.' },
];

/** Fields the user can actually edit — drives both the dirty check and the payload. */
const EDITABLE_FIELDS = PROFILE_FIELDS.filter(f => !f.locked);

function ProfileSection() {
  const { setUser } = useAuth();
  const navigate = useNavigate();
  const fileInputRef = useRef(null);

  const [form, setForm] = useState({ username: '', email: '', phone: '', address: '' });
  const [initial, setInitial] = useState({ username: '', email: '', phone: '', address: '' });
  const [currentImageUrl, setCurrentImageUrl] = useState('');
  const [previewUrl, setPreviewUrl] = useState('');
  const [selectedFile, setSelectedFile] = useState(null);
  const [uploading, setUploading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    getProfile().then(r => {
      const d = r.data;
      const loaded = {
        username: d.username || '',
        email:    d.email    || '',
        phone:    d.phone    || '',
        address:  d.address  || '',
      };
      setForm(loaded);
      setInitial(loaded);
      setCurrentImageUrl(d.profileImageUrl || '');
    }).catch(() => setError('Could not load your profile.'));
  }, []);

  const dirty = useMemo(
    () => Boolean(selectedFile) || EDITABLE_FIELDS.some(f => form[f.key] !== initial[f.key]),
    [form, initial, selectedFile],
  );

  const handleFileChange = (e) => {
    const file = e.target.files[0];
    if (!file) return;
    setSelectedFile(file);
    setPreviewUrl(URL.createObjectURL(file));
    setError('');
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError(''); setMessage(''); setSaving(true);

    try {
      // Upload photo first if a new file was selected
      if (selectedFile) {
        setUploading(true);
        try {
          const res = await uploadProfilePhoto(selectedFile);
          setCurrentImageUrl(res.data.profileImageUrl);
        } catch (err) {
          setError(err.response?.data?.error || 'Photo upload failed.');
          return;
        } finally {
          setUploading(false);
        }
      }

      // Email is deliberately absent: the server keeps the address it already has.
      await updateProfile({ username: form.username, phone: form.phone, address: form.address });
      const updated = await getProfile();
      setUser(updated.data);
      setInitial(form);
      setSelectedFile(null);
      setMessage('Profile updated. Taking you to your profile…');
      setTimeout(() => navigate('/profile'), 1500);
    } catch (err) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Update failed.');
    } finally {
      setSaving(false);
    }
  };

  const displayImage = previewUrl || publicPath(currentImageUrl);
  const initials = (form.username?.[0] ?? 'U').toUpperCase();

  return (
    <div className="card card-pad">
      <Feedback message={message} error={error} />

      <div className="flex flex-wrap items-center gap-5 pb-6 mb-6 border-b border-ink-100">
        {displayImage ? (
          <img
            src={displayImage}
            alt=""
            className="w-20 h-20 rounded-2xl object-cover border border-ink-200 shrink-0"
          />
        ) : (
          <div className="w-20 h-20 rounded-2xl bg-gradient-to-br from-purple-400 to-primary-600 grid place-items-center text-white text-2xl font-bold shrink-0">
            {initials}
          </div>
        )}
        <div className="min-w-0">
          <p className="font-semibold text-sm text-ink-900">Profile photo</p>
          <p className="field-hint">JPEG, PNG, GIF or WebP · max 5 MB</p>
          <div className="flex items-center gap-3 mt-2.5">
            <button
              type="button"
              onClick={() => fileInputRef.current?.click()}
              className="btn-secondary btn-sm"
            >
              <Upload size={13} /> Choose photo
            </button>
            {selectedFile && (
              <span className="text-xs text-ink-500 truncate max-w-[180px]">{selectedFile.name}</span>
            )}
          </div>
        </div>
        <input
          ref={fileInputRef}
          type="file"
          accept="image/jpeg,image/png,image/gif,image/webp"
          onChange={handleFileChange}
          className="hidden"
        />
      </div>

      <form onSubmit={handleSubmit} className="space-y-5">
        <div className="grid sm:grid-cols-2 gap-5">
          {PROFILE_FIELDS.map(({ key, label, type, placeholder, hint, locked }) => (
            <div key={key} className={key === 'address' ? 'sm:col-span-2' : undefined}>
              <label className="field-label" htmlFor={`profile-${key}`}>
                {label}
                {locked && (
                  <span className="ml-1.5 inline-flex items-center gap-1 font-medium text-ink-400">
                    <Lock size={11} /> Locked
                  </span>
                )}
              </label>
              <input
                id={`profile-${key}`}
                type={type}
                value={form[key]}
                onChange={locked ? undefined : e => setForm(f => ({ ...f, [key]: e.target.value }))}
                placeholder={placeholder}
                readOnly={locked}
                aria-readonly={locked || undefined}
                tabIndex={locked ? -1 : undefined}
                className={locked ? 'input-field bg-ink-50 text-ink-500 cursor-not-allowed' : 'input-field'}
              />
              <p className="field-hint">{hint}</p>
            </div>
          ))}
        </div>

        <div className="flex flex-wrap items-center gap-3 pt-1">
          <button type="submit" disabled={!dirty || saving || uploading} className="btn-primary">
            {uploading ? 'Uploading photo…' : saving ? 'Saving…' : 'Save changes'}
          </button>
          <button type="button" onClick={() => navigate('/profile')} className="btn-secondary">
            Cancel
          </button>
          <span className="text-xs text-ink-400">
            {dirty ? 'You have unsaved changes.' : 'Everything is saved.'}
          </span>
        </div>
      </form>
    </div>
  );
}

// ── Change password section ───────────────────────────────────────────────────
function ChangePasswordSection() {
  const navigate = useNavigate();
  const [form, setForm] = useState({ currentPassword: '', newPassword: '', confirmPassword: '' });
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (form.newPassword !== form.confirmPassword) { setError('New passwords do not match.'); return; }
    setError(''); setLoading(true);
    try {
      await changePassword(form);
      setMessage('Password changed. Taking you to your profile…');
      setTimeout(() => navigate('/profile'), 1500);
    } catch (err) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Failed to change password.');
    } finally {
      setLoading(false);
    }
  };

  const set = (key) => (e) => setForm(f => ({ ...f, [key]: e.target.value }));
  const mismatch = form.confirmPassword.length > 0 && form.newPassword !== form.confirmPassword;

  return (
    <div className="card card-pad max-w-lg">
      <Feedback message={message} error={error} />

      <form onSubmit={handleSubmit} className="space-y-4">
        <div>
          <label className="field-label" htmlFor="pw-current">Current password</label>
          <PasswordField
            id="pw-current"
            value={form.currentPassword}
            onChange={set('currentPassword')}
            placeholder="Enter your current password"
            autoComplete="current-password"
            required
          />
        </div>
        <div>
          <label className="field-label" htmlFor="pw-new">New password</label>
          <PasswordField
            id="pw-new"
            value={form.newPassword}
            onChange={set('newPassword')}
            placeholder="Create a password"
            autoComplete="new-password"
            required
          />
          <p className="field-hint">
            8–128 characters with uppercase, lowercase, a number, and a special character (!@#$%^&amp;* etc.)
          </p>
        </div>
        <div>
          <label className="field-label" htmlFor="pw-confirm">Confirm new password</label>
          <PasswordField
            id="pw-confirm"
            value={form.confirmPassword}
            onChange={set('confirmPassword')}
            placeholder="Re-enter your new password"
            autoComplete="new-password"
            required
          />
          {mismatch && <p className="text-xs text-red-600 mt-1">Both new passwords must match.</p>}
        </div>

        <button type="submit" disabled={loading || mismatch} className="btn-primary btn-block btn-lg">
          {loading ? 'Changing…' : 'Change password'}
        </button>
      </form>
    </div>
  );
}

// ── 2FA section ───────────────────────────────────────────────────────────────
function TwoFactorSection() {
  const { user, setUser } = useAuth();
  const is2FAEnabled = user?.twoFactorEnabled ?? false;

  const [step, setStep] = useState('idle');
  const [totpUri, setTotpUri]       = useState('');
  const [totpSecret, setTotpSecret] = useState('');
  const [confirmCode, setConfirmCode] = useState('');
  const [showDisable, setShowDisable] = useState(false);
  const [disableCode, setDisableCode] = useState('');
  const [message, setMessage] = useState('');
  const [error, setError]     = useState('');
  const [loading, setLoading] = useState(false);

  const clearMessages = () => { setMessage(''); setError(''); };

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

  const handleConfirm = async (e) => {
    e.preventDefault();
    clearMessages(); setLoading(true);
    try {
      await confirm2FA(confirmCode);
      setUser(prev => ({ ...prev, twoFactorEnabled: true }));
      setStep('idle'); setConfirmCode('');
      setMessage('Two-factor authentication is now enabled on your account.');
    } catch (err) {
      setError(err.response?.data?.error || 'Invalid code. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  const handleDisable = async (e) => {
    e.preventDefault();
    clearMessages(); setLoading(true);
    try {
      await disable2FA(disableCode);
      setUser(prev => ({ ...prev, twoFactorEnabled: false }));
      setShowDisable(false); setDisableCode('');
      setMessage('Two-factor authentication has been disabled.');
    } catch (err) {
      setError(err.response?.data?.error || 'Incorrect password. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="max-w-lg space-y-4">
      <Feedback message={message} error={error} />

      <div className="card card-pad flex items-center justify-between gap-4">
        <div className="flex items-center gap-3.5 min-w-0">
          <span className={`grid place-items-center w-11 h-11 rounded-2xl shrink-0 ${
            is2FAEnabled ? 'bg-emerald-50 text-emerald-600' : 'bg-ink-100 text-ink-400'
          }`}>
            <ShieldCheck size={20} />
          </span>
          <div className="min-w-0">
            <p className="font-semibold text-sm text-ink-900">
              {is2FAEnabled ? 'Two-factor authentication is on' : 'Two-factor authentication is off'}
            </p>
            <p className="text-sm text-ink-500 mt-0.5">
              {is2FAEnabled
                ? 'You enter a code from your authenticator app at every sign-in.'
                : 'Your password is the only thing protecting this account.'}
            </p>
          </div>
        </div>
        <span className={is2FAEnabled ? 'badge-success' : 'badge-neutral'}>
          {is2FAEnabled ? 'Enabled' : 'Disabled'}
        </span>
      </div>

      {!is2FAEnabled && step === 'idle' && (
        <button onClick={handleStartSetup} disabled={loading} className="btn-primary btn-block btn-lg">
          {loading ? 'Setting up…' : 'Set up two-factor authentication'}
        </button>
      )}

      {!is2FAEnabled && step === 'setup' && (
        <div className="card card-pad space-y-5">
          <div>
            <p className="eyebrow">Step 1 of 2</p>
            <h3 className="font-bold text-ink-900 mt-1">Scan the QR code</h3>
            <p className="text-sm text-ink-500 mt-1">
              Open Google Authenticator, Authy or any TOTP app and scan this code.
            </p>
          </div>

          <div className="flex justify-center p-4 surface-muted bg-white">
            <QRCodeSVG value={totpUri} size={180} />
          </div>

          <div>
            <p className="field-label">Can’t scan? Enter this key manually</p>
            <div className="surface-muted px-4 py-2.5 text-sm font-mono text-ink-700 break-all flex items-center justify-between gap-2">
              <span>{totpSecret}</span>
              <button
                type="button"
                onClick={() => navigator.clipboard.writeText(totpSecret).then(() => setMessage('Setup key copied.'))}
                className="btn-ghost btn-sm shrink-0"
              >
                <Copy size={13} /> Copy
              </button>
            </div>
          </div>

          <div className="divider pt-5">
            <p className="eyebrow">Step 2 of 2</p>
            <h3 className="font-bold text-ink-900 mt-1">Enter the 6-digit code</h3>
            <p className="text-sm text-ink-500 mt-1 mb-4">
              Type the code your app is showing right now to finish enabling 2FA.
            </p>

            <form onSubmit={handleConfirm} className="space-y-4">
              <CodeInput
                id="twofa-confirm"
                value={confirmCode}
                onChange={next => { setConfirmCode(next); setError(''); }}
                invalid={Boolean(error)}
                label="Authenticator code"
              />
              <div className="flex gap-3">
                <button type="submit" disabled={loading || confirmCode.length !== 6} className="btn-primary flex-1">
                  {loading ? 'Verifying…' : 'Confirm and enable'}
                </button>
                <button
                  type="button"
                  onClick={() => { setStep('idle'); setConfirmCode(''); clearMessages(); }}
                  className="btn-secondary flex-1"
                >
                  Cancel
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {is2FAEnabled && (!showDisable ? (
        <button
          onClick={() => { setShowDisable(true); clearMessages(); }}
          className="btn-secondary btn-block text-red-600 border-red-200 hover:bg-red-50 hover:border-red-300"
        >
          Turn off two-factor authentication
        </button>
      ) : (
        <div className="card card-pad border-red-200 space-y-4">
          <div>
            <h3 className="font-bold text-ink-900">Turn off two-factor authentication?</h3>
            <p className="text-sm text-ink-500 mt-1">
              Sign-in will only need your password. Confirm with your current password to continue.
            </p>
          </div>
          <form onSubmit={handleDisable} className="space-y-3">
            <PasswordField
              id="twofa-disable-password"
              placeholder="Current password"
              value={disableCode}
              onChange={e => setDisableCode(e.target.value)}
              autoComplete="current-password"
              required
            />
            <div className="flex gap-3">
              <button type="submit" disabled={loading || !disableCode} className="btn-danger flex-1">
                {loading ? 'Turning off…' : 'Turn off 2FA'}
              </button>
              <button
                type="button"
                onClick={() => { setShowDisable(false); setDisableCode(''); clearMessages(); }}
                className="btn-secondary flex-1"
              >
                Keep it on
              </button>
            </div>
          </form>
        </div>
      ))}
    </div>
  );
}

// ── Notification preferences section ──────────────────────────────────────────
const PREF_ITEMS = [
  { key: 'outbid',     label: 'Outbid alerts',         desc: 'Tell me when someone outbids me on an auction.' },
  { key: 'endingSoon', label: 'Watchlist ending soon', desc: 'Tell me when an auction on my watchlist is about to close.' },
  { key: 'wonAuction', label: 'Auction won',           desc: 'Tell me when I win an auction.' },
];

function NotificationPreferencesSection() {
  const [prefs, setPrefs] = useState({ outbid: true, endingSoon: true, wonAuction: true });
  const [loading, setLoading] = useState(true);
  const [savingKey, setSavingKey] = useState(null);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    getNotificationPreferences()
      .then(r => setPrefs({
        outbid:     r.data.outbid     ?? true,
        endingSoon: r.data.endingSoon ?? true,
        wonAuction: r.data.wonAuction ?? true,
      }))
      .catch(() => setError('Could not load notification preferences.'))
      .finally(() => setLoading(false));
  }, []);

  const handleToggle = async (key) => {
    const next = { ...prefs, [key]: !prefs[key] };
    setPrefs(next);
    setMessage(''); setError(''); setSavingKey(key);
    try {
      await saveNotificationPreferences(next);
      setMessage('Notification preferences saved.');
    } catch (err) {
      setPrefs(prefs); // revert on failure
      setError(err.response?.data?.error || 'Failed to save preferences.');
    } finally {
      setSavingKey(null);
    }
  };

  if (loading) {
    return (
      <div className="card card-pad max-w-lg space-y-3" aria-busy="true">
        {PREF_ITEMS.map(p => <div key={p.key} className="skeleton h-12" />)}
      </div>
    );
  }

  return (
    <div className="max-w-lg space-y-4">
      <Feedback message={message} error={error} />

      <div className="card divide-y divide-ink-100">
        {PREF_ITEMS.map(({ key, label, desc }) => (
          <div key={key} className="p-5 flex items-center justify-between gap-4">
            <div className="min-w-0">
              <p className="font-semibold text-sm text-ink-900">{label}</p>
              <p className="text-sm text-ink-500 mt-0.5">{desc}</p>
            </div>
            <div className="flex items-center gap-2 shrink-0">
              {savingKey === key && <Loader2 size={14} className="animate-spin text-ink-400" />}
              <button
                type="button"
                role="switch"
                aria-checked={prefs[key]}
                aria-label={label}
                disabled={savingKey !== null}
                onClick={() => handleToggle(key)}
                className={`relative inline-flex h-6 w-11 shrink-0 items-center rounded-full transition-colors disabled:opacity-60 ${
                  prefs[key] ? 'bg-primary-500' : 'bg-ink-300'
                }`}
              >
                <span
                  className={`inline-block h-4 w-4 transform rounded-full bg-white shadow transition-transform ${
                    prefs[key] ? 'translate-x-6' : 'translate-x-1'
                  }`}
                />
              </button>
            </div>
          </div>
        ))}
      </div>

      <p className="text-xs text-ink-400">
        Each switch saves on its own — there is no separate save button.
      </p>
    </div>
  );
}

// ── Delete account section ────────────────────────────────────────────────────
function DeleteAccountSection() {
  const { logout } = useAuth();
  const navigate = useNavigate();
  const [typed, setTyped] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const confirmed = typed.trim().toUpperCase() === 'DELETE';

  const handleDelete = async (e) => {
    e.preventDefault();
    if (!confirmed) return;
    setError(''); setLoading(true);
    try {
      await deleteAccount();
      await logout();
      navigate('/login');
    } catch (err) {
      setError(err.response?.data?.error || 'Failed to delete account.');
      setLoading(false);
    }
  };

  return (
    <div className="card card-pad max-w-lg border-red-200">
      <Feedback error={error} />

      <ul className="space-y-2 text-sm text-ink-600">
        {[
          'Your listings, bids and watchlist are removed.',
          'Open orders and refunds can no longer be managed.',
          'This cannot be undone — you would need to register again.',
        ].map(point => (
          <li key={point} className="flex items-start gap-2.5">
            <AlertCircle size={15} className="text-red-500 mt-0.5 shrink-0" />
            {point}
          </li>
        ))}
      </ul>

      <form onSubmit={handleDelete} className="mt-6 space-y-3">
        <div>
          <label className="field-label" htmlFor="delete-confirm">
            Type DELETE to confirm
          </label>
          <input
            id="delete-confirm"
            value={typed}
            onChange={e => { setTyped(e.target.value); setError(''); }}
            placeholder="DELETE"
            autoComplete="off"
            className="input-field"
          />
        </div>
        <button type="submit" disabled={!confirmed || loading} className="btn-danger">
          <Trash2 size={15} /> {loading ? 'Deleting…' : 'Delete my account'}
        </button>
      </form>
    </div>
  );
}

// ── Selling section ───────────────────────────────────────────────────────────
/**
 * Buying and selling live on one account. Buyers opt into selling here rather than
 * registering a second account.
 */
function SellingSection() {
  const navigate = useNavigate();
  const { user, refreshUser } = useAuth();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const alreadySelling = Boolean(user?.canSell);

  const handleEnable = async () => {
    setError(''); setLoading(true);
    try {
      await enableSelling();
      await refreshUser();
      navigate('/seller/dashboard');
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not enable selling. Please try again.'));
      setLoading(false);
    }
  };

  if (alreadySelling) {
    return (
      <div className="card card-pad">
        <div className="flex items-start gap-4">
          <span className="grid place-items-center w-12 h-12 rounded-2xl bg-emerald-50 text-emerald-600 shrink-0">
            <Store size={22} />
          </span>
          <div className="min-w-0">
            <div className="flex items-center gap-2.5">
              <h2 className="section-title text-base">Selling is switched on</h2>
              <span className="badge-success">Active</span>
            </div>
            <p className="text-sm text-ink-500 mt-1 leading-relaxed">
              You can list items and manage orders from your seller dashboard. Buying,
              bidding and your watchlist run on this same account — there is only one.
            </p>
            <div className="flex flex-wrap gap-3 mt-5">
              <button onClick={() => navigate('/seller/dashboard')} className="btn-primary">
                <LayoutDashboard size={16} /> Seller dashboard
              </button>
              <button onClick={() => navigate('/seller/create')} className="btn-secondary">
                Create a listing
              </button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="card card-pad">
      <div className="flex items-start gap-4">
        <span className="grid place-items-center w-12 h-12 rounded-2xl bg-primary-50 text-primary-600 shrink-0">
          <Store size={22} />
        </span>
        <div className="min-w-0">
          <h2 className="section-title text-base">Start selling</h2>
          <p className="text-sm text-ink-500 mt-1 leading-relaxed">
            Switch selling on to list your own items. It’s the same account either
            way — your bids, watchlist and order history are unaffected. You can also
            do this straight from My listings in the navigation bar.
          </p>

          <ul className="mt-5 space-y-2.5">
            {[
              'List items with ascending, Dutch or sealed-bid auctions',
              'Track bids, orders and shipping from a seller dashboard',
              'Answer buyer questions and rate the buyers you sell to',
            ].map(point => (
              <li key={point} className="flex items-start gap-2.5 text-sm text-ink-600">
                <Check size={16} className="text-emerald-600 mt-0.5 shrink-0" />
                {point}
              </li>
            ))}
          </ul>

          {error && (
            <div className="alert-error mt-5">
              <AlertCircle size={16} className="mt-0.5 shrink-0" />
              <span>{error}</span>
            </div>
          )}

          <button onClick={handleEnable} disabled={loading} className="btn-primary btn-lg mt-6">
            {loading ? 'Enabling…' : 'Enable selling on my account'}
          </button>
          <p className="field-hint">You can start listing straight away — nothing to pay up front.</p>
        </div>
      </div>
    </div>
  );
}

// ── Main settings page ────────────────────────────────────────────────────────
export default function AccountSettings() {
  const [searchParams, setSearchParams] = useSearchParams();
  const requested = searchParams.get('tab');
  const tab = TABS.some(t => t.key === requested) ? requested : 'profile';
  const active = TABS.find(t => t.key === tab);

  // The tab lives in the URL so a section can be linked to, bookmarked and
  // survive a refresh.
  const selectTab = (key) => setSearchParams(key === 'profile' ? {} : { tab: key }, { replace: true });

  return (
    <div className="max-w-5xl mx-auto px-4 py-8">
      <Link
        to="/profile"
        className="inline-flex items-center gap-1.5 text-sm font-medium text-ink-500 hover:text-ink-800 transition-colors mb-4"
      >
        <ArrowLeft size={14} /> Back to profile
      </Link>

      <h1 className="page-title">Account settings</h1>
      <p className="page-subtitle mb-7">
        Manage your profile, selling, security and notification preferences.
      </p>

      <div className="grid lg:grid-cols-[13.5rem_1fr] gap-6 lg:gap-8 items-start">
        {/* Section nav: a vertical list on desktop, a scrollable pill row on mobile. */}
        <nav aria-label="Settings sections" className="lg:sticky lg:top-6">
          <div className="hidden lg:flex flex-col gap-1">
            {TABS.map(({ key, label, icon: Icon, danger }) => {
              const isActive = tab === key;
              return (
                <button
                  key={key}
                  onClick={() => selectTab(key)}
                  aria-current={isActive ? 'page' : undefined}
                  className={`flex items-center gap-2.5 px-3.5 py-2.5 rounded-xl text-sm font-semibold text-left transition-colors ${
                    isActive
                      ? danger ? 'bg-red-50 text-red-700' : 'bg-ink-900 text-white shadow-sm'
                      : danger ? 'text-red-600 hover:bg-red-50' : 'text-ink-600 hover:bg-ink-100 hover:text-ink-900'
                  } ${danger ? 'mt-3' : ''}`}
                >
                  <Icon size={16} className="shrink-0" />
                  {label}
                </button>
              );
            })}
          </div>

          <div className="flex lg:hidden gap-2 overflow-x-auto pb-1 -mx-4 px-4">
            {TABS.map(({ key, label, icon: Icon, danger }) => (
              <button
                key={key}
                onClick={() => selectTab(key)}
                aria-current={tab === key ? 'page' : undefined}
                className={`tab-pill inline-flex items-center gap-1.5 shrink-0 ${
                  tab === key ? 'tab-pill-active' : danger ? 'text-red-600' : ''
                }`}
              >
                <Icon size={14} /> {label}
              </button>
            ))}
          </div>
        </nav>

        <div className="min-w-0">
          <div className="mb-5">
            <h2 className={`section-title ${active.danger ? 'text-red-700' : ''}`}>{active.title}</h2>
            <p className="page-subtitle">{active.desc}</p>
          </div>

          {tab === 'profile'       && <ProfileSection />}
          {tab === 'selling'       && <SellingSection />}
          {tab === 'password'      && <ChangePasswordSection />}
          {tab === '2fa'           && <TwoFactorSection />}
          {tab === 'notifications' && <NotificationPreferencesSection />}
          {tab === 'danger'        && <DeleteAccountSection />}
        </div>
      </div>
    </div>
  );
}
