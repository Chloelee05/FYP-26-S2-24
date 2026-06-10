import { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { QRCodeSVG } from 'qrcode.react';
import { getProfile, updateProfile, uploadProfilePhoto, deleteAccount } from '../api/user';
import { changePassword } from '../api/auth';
import { setup2FA, confirm2FA, disable2FA } from '../api/twoFactor';
import { useAuth } from '../context/AuthContext';

const TABS = [
  { key: 'profile', label: 'Edit Profile' },
  { key: 'password', label: 'Change Password' },
  { key: '2fa', label: 'Two-Factor Auth' },
];

// ── Edit Profile section ──────────────────────────────────────────────────────
function EditProfileSection() {
  const { setUser } = useAuth();
  const navigate = useNavigate();
  const fileInputRef = useRef(null);

  const [form, setForm] = useState({ username: '', email: '', phone: '', address: '' });
  const [currentImageUrl, setCurrentImageUrl] = useState('');
  const [previewUrl, setPreviewUrl] = useState('');
  const [selectedFile, setSelectedFile] = useState(null);
  const [uploading, setUploading] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    getProfile().then(r => {
      const d = r.data;
      setForm({
        username: d.username || '',
        email:    d.email    || '',
        phone:    d.phone    || '',
        address:  d.address  || '',
      });
      setCurrentImageUrl(d.profileImageUrl || '');
    }).catch(() => {});
  }, []);

  const handleFileChange = (e) => {
    const file = e.target.files[0];
    if (!file) return;
    setSelectedFile(file);
    setPreviewUrl(URL.createObjectURL(file));
    setError('');
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError(''); setMessage('');

    try {
      // Upload photo first if a new file was selected
      if (selectedFile) {
        setUploading(true);
        try {
          const res = await uploadProfilePhoto(selectedFile);
          setCurrentImageUrl(res.data.profileImageUrl);
        } catch (err) {
          setError(err.response?.data?.error || 'Photo upload failed.');
          setUploading(false);
          return;
        } finally {
          setUploading(false);
        }
      }

      await updateProfile(form);
      const updated = await getProfile();
      setUser(updated.data);
      setMessage('Profile updated successfully!');
      setTimeout(() => navigate('/profile'), 1500);
    } catch (err) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Update failed.');
    }
  };

  const displayImage = previewUrl || currentImageUrl;
  const initials = (form.username?.[0] ?? 'U').toUpperCase();

  return (
    <div className="card p-8">
      {message && <div className="bg-green-50 text-green-600 text-sm px-4 py-2 rounded-lg mb-4">{message}</div>}
      {error   && <div className="bg-red-50 text-red-600 text-sm px-4 py-2 rounded-lg mb-4">{error}</div>}

      {/* Photo upload */}
      <div className="flex items-center gap-5 mb-6">
        <div className="relative shrink-0">
          {displayImage ? (
            <img
              src={displayImage}
              alt="Profile"
              className="w-20 h-20 rounded-full object-cover border border-gray-200"
            />
          ) : (
            <div className="w-20 h-20 rounded-full bg-gradient-to-br from-purple-400 to-blue-500 flex items-center justify-center text-white text-2xl font-bold">
              {initials}
            </div>
          )}
          <button
            type="button"
            onClick={() => fileInputRef.current?.click()}
            className="absolute -bottom-1 -right-1 bg-blue-500 hover:bg-blue-600 text-white rounded-full w-7 h-7 flex items-center justify-center shadow transition-colors"
            title="Change photo"
          >
            <svg xmlns="http://www.w3.org/2000/svg" className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/>
            </svg>
          </button>
        </div>
        <div>
          <button
            type="button"
            onClick={() => fileInputRef.current?.click()}
            className="text-sm font-medium text-blue-500 hover:underline"
          >
            {uploading ? 'Uploading…' : 'Upload new photo'}
          </button>
          <p className="text-xs text-gray-400 mt-0.5">JPEG, PNG, GIF or WebP · Max 5 MB</p>
          {selectedFile && (
            <p className="text-xs text-gray-500 mt-0.5 truncate max-w-[200px]">{selectedFile.name}</p>
          )}
        </div>
        <input
          ref={fileInputRef}
          type="file"
          accept="image/jpeg,image/png,image/gif,image/webp"
          onChange={handleFileChange}
          className="hidden"
        />
      </div>

      <form onSubmit={handleSubmit} className="space-y-4">
        {[
          { key: 'username', label: 'Display Name', type: 'text',  placeholder: 'Your name' },
          { key: 'email',    label: 'Email',         type: 'email', placeholder: 'email@example.com' },
          { key: 'phone',    label: 'Phone',         type: 'tel',   placeholder: '+65 XXXX XXXX' },
          { key: 'address',  label: 'Address',       type: 'text',  placeholder: 'Street, City, Country' },
        ].map(({ key, label, type, placeholder }) => (
          <div key={key}>
            <label className="block text-sm font-medium text-gray-700 mb-1">{label}</label>
            <input
              type={type}
              value={form[key]}
              onChange={e => setForm(f => ({ ...f, [key]: e.target.value }))}
              placeholder={placeholder}
              className="input-field"
            />
          </div>
        ))}
        <div className="flex gap-3 pt-2">
          <button
            type="submit"
            disabled={uploading}
            className="flex-1 bg-blue-500 hover:bg-blue-600 text-white font-medium py-3 rounded-lg transition-colors disabled:opacity-50"
          >
            {uploading ? 'Uploading photo…' : 'Save Changes'}
          </button>
          <button type="button" onClick={() => navigate('/profile')}
            className="flex-1 border border-gray-200 text-gray-700 font-medium py-3 rounded-lg hover:bg-gray-50 transition-colors">
            Cancel
          </button>
        </div>
      </form>
    </div>
  );
}

// ── Change Password section ───────────────────────────────────────────────────
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
      setMessage('Password changed successfully!');
      setTimeout(() => navigate('/profile'), 1500);
    } catch (err) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Failed to change password.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="card p-8 max-w-md">
      {message && <div className="bg-green-50 text-green-600 text-sm px-4 py-2 rounded-lg mb-4">{message}</div>}
      {error   && <div className="bg-red-50 text-red-600 text-sm px-4 py-2 rounded-lg mb-4">{error}</div>}
      <form onSubmit={handleSubmit} className="space-y-4">
        {[
          { key: 'currentPassword', label: 'Current Password' },
          { key: 'newPassword',     label: 'New Password' },
          { key: 'confirmPassword', label: 'Confirm New Password' },
        ].map(({ key, label }) => (
          <div key={key}>
            <label className="block text-sm font-medium text-gray-700 mb-1">{label}</label>
            <input
              type="password"
              value={form[key]}
              onChange={e => setForm(f => ({ ...f, [key]: e.target.value }))}
              required
              className="input-field"
            />
          </div>
        ))}
        <button
          type="submit"
          disabled={loading}
          className="w-full bg-blue-500 hover:bg-blue-600 text-white font-medium py-3 rounded-lg transition-colors disabled:opacity-50"
        >
          {loading ? 'Changing…' : 'Change Password'}
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
      <p className="text-gray-500 text-sm">
        Add an extra layer of security by requiring a code from your authenticator app when signing in.
      </p>

      {message && <div className="bg-green-50 text-green-700 text-sm px-4 py-3 rounded-lg">{message}</div>}
      {error   && <div className="bg-red-50 text-red-600 text-sm px-4 py-3 rounded-lg">{error}</div>}

      <div className="card p-5 flex items-center justify-between">
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

      {!is2FAEnabled && step === 'idle' && (
        <button onClick={handleStartSetup} disabled={loading}
          className="w-full bg-blue-500 hover:bg-blue-600 text-white font-medium py-3 rounded-lg transition-colors disabled:opacity-50">
          {loading ? 'Setting up…' : 'Enable Two-Factor Authentication'}
        </button>
      )}

      {!is2FAEnabled && step === 'setup' && (
        <div className="card p-6 space-y-5">
          <h2 className="font-bold text-gray-900">Step 1 — Scan the QR code</h2>
          <p className="text-sm text-gray-500">Open Google Authenticator, Authy, or any TOTP app and scan the QR code below.</p>
          <div className="flex justify-center p-4 bg-white border border-gray-200 rounded-xl">
            <QRCodeSVG value={totpUri} size={180} />
          </div>
          <div>
            <p className="text-xs text-gray-400 mb-1">Can't scan? Enter this key manually:</p>
            <div className="bg-gray-50 border border-gray-200 rounded-lg px-4 py-2 text-sm font-mono text-gray-700 break-all flex items-center justify-between gap-2">
              <span>{totpSecret}</span>
              <button type="button"
                onClick={() => navigator.clipboard.writeText(totpSecret).then(() => setMessage('Key copied!'))}
                className="text-blue-500 text-xs whitespace-nowrap hover:underline">
                Copy
              </button>
            </div>
          </div>
          <h2 className="font-bold text-gray-900">Step 2 — Enter the verification code</h2>
          <form onSubmit={handleConfirm} className="space-y-3">
            <input type="text" inputMode="numeric" placeholder="000000"
              value={confirmCode}
              onChange={e => setConfirmCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
              maxLength={6} required
              className="input-field text-center text-xl tracking-[0.4em] font-mono"
            />
            <button type="submit" disabled={loading || confirmCode.length !== 6}
              className="w-full bg-green-600 hover:bg-green-700 text-white font-medium py-3 rounded-lg transition-colors disabled:opacity-50">
              {loading ? 'Verifying…' : 'Confirm & Enable'}
            </button>
            <button type="button" onClick={() => { setStep('idle'); clearMessages(); }}
              className="w-full border border-gray-200 text-gray-600 font-medium py-3 rounded-lg hover:bg-gray-50 transition-colors">
              Cancel
            </button>
          </form>
        </div>
      )}

      {is2FAEnabled && (
        <div className="space-y-3">
          {!showDisable ? (
            <button onClick={() => { setShowDisable(true); clearMessages(); }}
              className="w-full border border-red-300 text-red-600 font-medium py-3 rounded-lg hover:bg-red-50 transition-colors">
              Disable Two-Factor Authentication
            </button>
          ) : (
            <div className="card p-6 border-red-200 space-y-4">
              <h2 className="font-bold text-gray-900">Disable 2FA</h2>
              <p className="text-sm text-gray-500">Enter your current password to confirm.</p>
              <form onSubmit={handleDisable} className="space-y-3">
                <input type="password" placeholder="Current password"
                  value={disableCode}
                  onChange={e => setDisableCode(e.target.value)}
                  required
                  className="input-field"
                />
                <button type="submit" disabled={loading || !disableCode}
                  className="w-full bg-red-600 hover:bg-red-700 text-white font-medium py-3 rounded-lg transition-colors disabled:opacity-50">
                  {loading ? 'Disabling…' : 'Confirm Disable'}
                </button>
                <button type="button" onClick={() => { setShowDisable(false); setDisableCode(''); clearMessages(); }}
                  className="w-full border border-gray-200 text-gray-600 font-medium py-3 rounded-lg hover:bg-gray-50 transition-colors">
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

// ── Delete Account section ────────────────────────────────────────────────────
function DeleteAccountSection() {
  const { logout } = useAuth();
  const navigate = useNavigate();
  const [expanded, setExpanded] = useState(false);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleDelete = async () => {
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
    <div className="mt-8 pt-6 border-t border-gray-200">
      <h3 className="text-sm font-medium text-gray-700 mb-3">Delete Account</h3>
      {!expanded ? (
        <button
          onClick={() => setExpanded(true)}
          className="text-sm text-red-500 hover:underline"
        >
          Delete my account
        </button>
      ) : (
        <div className="card p-5 space-y-3 max-w-md">
          <p className="text-sm text-gray-600">Are you sure? This will permanently delete your account and cannot be undone.</p>
          {error && <div className="bg-red-50 text-red-600 text-sm px-3 py-2 rounded-lg">{error}</div>}
          <div className="flex gap-3">
            <button
              onClick={handleDelete}
              disabled={loading}
              className="flex-1 bg-red-500 hover:bg-red-600 text-white text-sm font-medium py-2 rounded-lg transition-colors disabled:opacity-50"
            >
              {loading ? 'Deleting…' : 'Yes, delete'}
            </button>
            <button
              onClick={() => { setExpanded(false); setError(''); }}
              className="flex-1 border border-gray-200 text-sm text-gray-600 py-2 rounded-lg hover:bg-gray-50 transition-colors"
            >
              Cancel
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

// ── Main settings page ────────────────────────────────────────────────────────
export default function AccountSettings() {
  const [tab, setTab] = useState('profile');

  return (
    <div className="max-w-3xl mx-auto px-4 py-8">
      <h1 className="text-2xl font-bold text-gray-900 mb-6">Account Settings</h1>

      <div className="flex gap-1 border-b border-gray-200 mb-6">
        {TABS.map(t => (
          <button
            key={t.key}
            onClick={() => setTab(t.key)}
            className={`px-4 py-2.5 text-sm font-medium transition-colors border-b-2 -mb-px ${
              tab === t.key
                ? 'border-blue-500 text-blue-600'
                : 'border-transparent text-gray-500 hover:text-gray-700'
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {tab === 'profile'  && <EditProfileSection />}
      {tab === 'password' && <ChangePasswordSection />}
      {tab === '2fa'      && <TwoFactorSection />}

      <DeleteAccountSection />
    </div>
  );
}
