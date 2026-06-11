import { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { getProfile, updateProfile, uploadProfilePhoto } from '../api/user';
import { useAuth } from '../context/AuthContext';
import { publicPath } from '../utils/appBase';

export default function EditProfile() {
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
      setForm({ username: d.username || '', email: d.email || '', phone: d.phone || '', address: d.address || '' });
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
    if (form.phone && !/^[\d\s\+\-\(\)]+$/.test(form.phone)) {
      setError('Phone number can only contain digits, spaces, +, -, and parentheses.');
      return;
    }
    try {
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
      await updateProfile(form);
      const updated = await getProfile();
      setUser(updated.data);
      setMessage('Profile updated successfully!');
      setTimeout(() => navigate('/profile'), 1500);
    } catch (err) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Update failed.');
    }
  };

  const displayImage = previewUrl || publicPath(currentImageUrl);
  const initials = (form.username?.[0] ?? 'U').toUpperCase();

  return (
    <div className="max-w-2xl mx-auto px-4 py-8">
      <h1 className="text-2xl font-bold text-gray-900 mb-6">Edit Profile</h1>
      <div className="card p-8">
        {message && <div className="bg-green-50 text-green-600 text-sm px-4 py-2 rounded-lg mb-4">{message}</div>}
        {error   && <div className="bg-red-50 text-red-600 text-sm px-4 py-2 rounded-lg mb-4">{error}</div>}

        {/* Photo upload */}
        <div className="flex items-center gap-5 mb-6">
          <div className="relative shrink-0">
            {displayImage ? (
              <img src={displayImage} alt="Profile" className="w-20 h-20 rounded-full object-cover border border-gray-200" />
            ) : (
              <div className="w-20 h-20 rounded-full bg-gradient-to-br from-purple-400 to-blue-500 flex items-center justify-center text-white text-2xl font-bold">
                {initials}
              </div>
            )}
            <button type="button" onClick={() => fileInputRef.current?.click()}
              className="absolute -bottom-1 -right-1 bg-blue-500 hover:bg-blue-600 text-white rounded-full w-7 h-7 flex items-center justify-center shadow transition-colors"
              title="Change photo">
              <svg xmlns="http://www.w3.org/2000/svg" className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/>
              </svg>
            </button>
          </div>
          <div>
            <button type="button" onClick={() => fileInputRef.current?.click()}
              className="text-sm font-medium text-blue-500 hover:underline">
              {uploading ? 'Uploading…' : 'Upload new photo'}
            </button>
            <p className="text-xs text-gray-400 mt-0.5">JPEG, PNG, GIF or WebP · Max 5 MB</p>
            {selectedFile && <p className="text-xs text-gray-500 mt-0.5 truncate max-w-[200px]">{selectedFile.name}</p>}
          </div>
          <input ref={fileInputRef} type="file" accept="image/jpeg,image/png,image/gif,image/webp"
            onChange={handleFileChange} className="hidden" />
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
              <input type={type} value={form[key]}
                onChange={e => setForm(f => ({ ...f, [key]: e.target.value }))}
                placeholder={placeholder} className="input-field" />
            </div>
          ))}
          <div className="flex gap-3 pt-2">
            <button type="submit" disabled={uploading}
              className="flex-1 bg-blue-500 hover:bg-blue-600 text-white font-medium py-3 rounded-lg transition-colors disabled:opacity-50">
              {uploading ? 'Uploading photo…' : 'Save Changes'}
            </button>
            <button type="button" onClick={() => navigate('/profile')}
              className="flex-1 border border-gray-200 text-gray-700 font-medium py-3 rounded-lg hover:bg-gray-50 transition-colors">
              Cancel
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
