import { useEffect, useRef, useState } from 'react';
import { UploadCloud, X } from 'lucide-react';
import { uploadAuctionImage } from '../api/seller';
import { publicPath } from '../utils/appBase';
import { apiErrorMessage } from '../utils/apiError';

/**
 * Props:
 *   existingImages  — [{ imageId, imageUrl }] from the server (edit mode)
 *   onChange(urls, deleteIds) — called whenever images change
 *                  urls      = new uploaded URL strings
 *                  deleteIds = existing imageId numbers marked for removal
 */
export default function ImageUploader({ existingImages = [], onChange }) {
  const fileInputRef = useRef(null);

  // existing images still shown (not yet marked for deletion)
  const [kept, setKept] = useState(existingImages);
  // newly uploaded: [{ localUrl, serverUrl }]
  const [uploaded, setUploaded] = useState([]);
  const [deletedIds, setDeletedIds] = useState([]);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');

  // Preview thumbnails are blob: URLs, which the browser holds until they are
  // revoked. Mirror the list into a ref so unmount can release whatever is left
  // without the cleanup re-running on every add or remove.
  const uploadedRef = useRef(uploaded);
  useEffect(() => { uploadedRef.current = uploaded; }, [uploaded]);
  useEffect(() => () => {
    uploadedRef.current.forEach(u => URL.revokeObjectURL(u.localUrl));
  }, []);

  const notify = (nextUploaded, nextDeletedIds) => {
    onChange(
      nextUploaded.map(u => u.serverUrl),
      nextDeletedIds
    );
  };

  const handleFiles = async (files) => {
    setError('');
    const accepted = Array.from(files).filter(f => f.type.startsWith('image/'));
    if (accepted.length === 0) return;

    setUploading(true);
    const results = [];
    for (const file of accepted) {
      try {
        const res = await uploadAuctionImage(file);
        results.push({ localUrl: URL.createObjectURL(file), serverUrl: res.data.imageUrl });
      } catch (err) {
        setError(apiErrorMessage(err, `Upload failed (HTTP ${err.response?.status ?? 'network error'})`));
      }
    }
    const nextUploaded = [...uploaded, ...results];
    setUploaded(nextUploaded);
    setUploading(false);
    notify(nextUploaded, deletedIds);
  };

  const removeExisting = (imageId) => {
    const nextKept = kept.filter(img => img.imageId !== imageId);
    const nextDeletedIds = [...deletedIds, imageId];
    setKept(nextKept);
    setDeletedIds(nextDeletedIds);
    notify(uploaded, nextDeletedIds);
  };

  const removeUploaded = (serverUrl) => {
    const removed = uploaded.find(u => u.serverUrl === serverUrl);
    if (removed) URL.revokeObjectURL(removed.localUrl);
    const nextUploaded = uploaded.filter(u => u.serverUrl !== serverUrl);
    setUploaded(nextUploaded);
    notify(nextUploaded, deletedIds);
  };

  const handleDrop = (e) => {
    e.preventDefault();
    handleFiles(e.dataTransfer.files);
  };

  const allImages = [
    ...kept.map(img => ({ key: img.imageId, src: publicPath(img.imageUrl), onRemove: () => removeExisting(img.imageId) })),
    ...uploaded.map(u => ({ key: u.serverUrl, src: u.localUrl, onRemove: () => removeUploaded(u.serverUrl) })),
  ];

  return (
    <div className="space-y-3">
      {/* Thumbnails */}
      {allImages.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {allImages.map((img, i) => (
            <div key={img.key} className="relative w-20 h-20 shrink-0 group">
              <img src={img.src} alt="" className="w-full h-full object-contain bg-ink-50 p-1 rounded-xl border border-ink-200" />
              {i === 0 && (
                <span className="absolute bottom-1 left-1 rounded bg-ink-900/80 px-1.5 py-0.5 text-[10px] font-semibold text-white">
                  Cover
                </span>
              )}
              <button
                type="button"
                onClick={img.onRemove}
                aria-label="Remove image"
                className="absolute -top-1.5 -right-1.5 bg-white border border-ink-200 rounded-full w-6 h-6 flex items-center justify-center
                           text-ink-500 hover:text-red-500 hover:border-red-300 hover:scale-110 transition-all shadow-sm"
              >
                <X size={13} />
              </button>
            </div>
          ))}
        </div>
      )}

      {/* Drop zone */}
      <div
        onDrop={handleDrop}
        onDragOver={e => e.preventDefault()}
        onClick={() => fileInputRef.current?.click()}
        className="border-2 border-dashed border-ink-300 rounded-2xl px-4 py-8 text-center cursor-pointer
                   hover:border-primary-400 hover:bg-primary-50/60 transition-colors group"
      >
        {uploading ? (
          <p className="text-sm font-semibold text-primary-600">Uploading…</p>
        ) : (
          <>
            <span className="grid place-items-center w-11 h-11 rounded-xl bg-ink-100 text-ink-400 mx-auto mb-3 transition-colors group-hover:bg-primary-100 group-hover:text-primary-600">
              <UploadCloud size={20} />
            </span>
            <p className="text-sm font-semibold text-ink-700">Click or drag images here</p>
            <p className="text-xs text-ink-400 mt-1">JPEG, PNG or WebP · Max 5 MB each</p>
          </>
        )}
      </div>

      {error && <p className="text-xs font-medium text-red-600">{error}</p>}

      <input
        ref={fileInputRef}
        type="file"
        accept="image/jpeg,image/png,image/webp"
        multiple
        onChange={e => handleFiles(e.target.files)}
        className="hidden"
      />
    </div>
  );
}
