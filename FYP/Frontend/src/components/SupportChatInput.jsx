import { useRef, useState } from 'react';
import { ImagePlus, Send, X } from 'lucide-react';
import { uploadSupportImage } from '../api/support';

export default function SupportChatInput({ onSend, disabled, placeholder = 'Type a message…' }) {
  const fileRef = useRef(null);
  const [draft, setDraft] = useState('');
  const [preview, setPreview] = useState(null);
  const [pendingUrl, setPendingUrl] = useState(null);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');

  const clearAttachment = () => {
    if (preview) URL.revokeObjectURL(preview);
    setPreview(null);
    setPendingUrl(null);
    if (fileRef.current) fileRef.current.value = '';
  };

  const handleFile = async (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (!file.type.startsWith('image/')) {
      setError('Only image files are allowed.');
      return;
    }
    setError('');
    clearAttachment();
    setPreview(URL.createObjectURL(file));
    setUploading(true);
    try {
      const url = await uploadSupportImage(file);
      setPendingUrl(url);
    } catch (err) {
      setError(err.response?.data?.error || 'Upload failed.');
      clearAttachment();
    } finally {
      setUploading(false);
    }
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (disabled || uploading) return;
    const body = draft.trim();
    if (!body && !pendingUrl) return;
    await onSend({ body, attachmentUrl: pendingUrl });
    setDraft('');
    clearAttachment();
  };

  return (
    <div className="border-t border-gray-100 shrink-0">
      {error && <p className="px-3 pt-2 text-xs text-red-500">{error}</p>}
      {preview && (
        <div className="px-3 pt-2 flex items-start gap-2">
          <div className="relative">
            <img src={preview} alt="" className="h-16 w-16 object-cover rounded-lg border border-gray-200" />
            <button
              type="button"
              onClick={clearAttachment}
              className="absolute -top-1.5 -right-1.5 bg-gray-800 text-white rounded-full p-0.5"
            >
              <X size={12} />
            </button>
          </div>
          {uploading && <span className="text-xs text-gray-400 self-center">Uploading…</span>}
        </div>
      )}
      <form onSubmit={handleSubmit} className="p-3 flex gap-2 items-end">
        <input ref={fileRef} type="file" accept="image/*" className="hidden" onChange={handleFile} />
        <button
          type="button"
          disabled={disabled || uploading}
          onClick={() => fileRef.current?.click()}
          className="p-2 text-gray-400 hover:text-blue-500 hover:bg-gray-50 rounded-lg disabled:opacity-40"
          title="Attach photo"
        >
          <ImagePlus size={18} />
        </button>
        <input
          value={draft}
          onChange={e => setDraft(e.target.value)}
          placeholder={placeholder}
          disabled={disabled}
          className="flex-1 border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-100 disabled:opacity-50"
        />
        <button
          type="submit"
          disabled={disabled || uploading || (!draft.trim() && !pendingUrl)}
          className="p-2 bg-blue-500 text-white rounded-lg hover:bg-blue-600 disabled:opacity-40"
        >
          <Send size={16} />
        </button>
      </form>
    </div>
  );
}
