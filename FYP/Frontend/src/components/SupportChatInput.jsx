/**
 * Message composer for the support chat: a text box plus one optional image. Used by
 * SupportChatWidget and by the /support page.
 *
 * Props: `onSend({ body, attachmentUrl })` is called with the finished message, `disabled`
 * locks the controls (a closed thread), `placeholder` sets the hint text.
 *
 * The image is uploaded as soon as it is picked, not when send is pressed, so the send
 * only ever carries the URL the server gave back. The preview is a blob: URL, which needs
 * blob: allowed under img-src in the backend Content Security Policy, and it is revoked
 * whenever the attachment is cleared or replaced.
 */
import { useRef, useState } from 'react';
import { ImagePlus, Send, X } from 'lucide-react';
import { uploadSupportImage } from '../api/support';
import { apiErrorMessage } from '../utils/apiError';

export default function SupportChatInput({ onSend, disabled, placeholder = 'Type a message…' }) {
  const fileRef = useRef(null);
  const [draft, setDraft] = useState('');
  // Two URLs for the same picture: `preview` is the blob: URL shown as a thumbnail,
  // `pendingUrl` is the server URL that actually travels with the message.
  const [preview, setPreview] = useState(null);
  const [pendingUrl, setPendingUrl] = useState(null);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');

  // Releases the blob: URL and resets the file input, so picking the same file again
  // still fires a change event.
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
      setError(apiErrorMessage(err, 'Upload failed.'));
      clearAttachment();
    } finally {
      setUploading(false);
    }
  };

  // Either text or an image is enough to send. Blocked while an upload is still running,
  // so a message cannot go out without the attachment it was written for.
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
    <div className="border-t border-ink-100 shrink-0">
      {error && <p className="px-3 pt-2 text-xs text-red-500">{error}</p>}
      {preview && (
        <div className="px-3 pt-2 flex items-start gap-2">
          <div className="relative">
            <img src={preview} alt="" className="h-16 w-16 object-cover rounded-lg border border-ink-200" />
            <button
              type="button"
              onClick={clearAttachment}
              className="absolute -top-1.5 -right-1.5 bg-ink-800 text-white rounded-full p-0.5"
            >
              <X size={12} />
            </button>
          </div>
          {uploading && <span className="text-xs text-ink-400 self-center">Uploading…</span>}
        </div>
      )}
      <form onSubmit={handleSubmit} className="p-3 flex gap-2 items-end">
        <input ref={fileRef} type="file" accept="image/*" className="hidden" onChange={handleFile} />
        <button
          type="button"
          disabled={disabled || uploading}
          onClick={() => fileRef.current?.click()}
          className="p-2 text-ink-400 hover:text-primary-500 hover:bg-ink-50 rounded-lg disabled:opacity-40"
          title="Attach photo"
        >
          <ImagePlus size={18} />
        </button>
        <input
          value={draft}
          onChange={e => setDraft(e.target.value)}
          placeholder={placeholder}
          disabled={disabled}
          className="flex-1 border border-ink-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-100 disabled:opacity-50"
        />
        <button
          type="submit"
          disabled={disabled || uploading || (!draft.trim() && !pendingUrl)}
          className="btn-primary p-2"
        >
          <Send size={16} />
        </button>
      </form>
    </div>
  );
}
