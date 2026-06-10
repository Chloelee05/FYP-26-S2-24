import { useRef, useState } from 'react';
import { uploadAuctionImage } from '../api/seller';

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
        const msg = err.response?.data?.error || err.response?.data?.message;
        setError(msg || `Upload failed (HTTP ${err.response?.status ?? 'network error'})`);
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
    const nextUploaded = uploaded.filter(u => u.serverUrl !== serverUrl);
    setUploaded(nextUploaded);
    notify(nextUploaded, deletedIds);
  };

  const handleDrop = (e) => {
    e.preventDefault();
    handleFiles(e.dataTransfer.files);
  };

  const allImages = [
    ...kept.map(img => ({ key: img.imageId, src: img.imageUrl, onRemove: () => removeExisting(img.imageId) })),
    ...uploaded.map(u => ({ key: u.serverUrl, src: u.localUrl, onRemove: () => removeUploaded(u.serverUrl) })),
  ];

  return (
    <div className="space-y-3">
      {/* Thumbnails */}
      {allImages.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {allImages.map(img => (
            <div key={img.key} className="relative w-20 h-20 shrink-0">
              <img src={img.src} alt="" className="w-full h-full object-cover rounded-lg border border-gray-200" />
              <button
                type="button"
                onClick={img.onRemove}
                className="absolute -top-1.5 -right-1.5 bg-white border border-gray-200 rounded-full w-5 h-5 flex items-center justify-center text-gray-500 hover:text-red-500 hover:border-red-300 transition-colors shadow-sm text-xs"
              >
                ✕
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
        className="border-2 border-dashed border-gray-200 rounded-xl px-4 py-6 text-center cursor-pointer hover:border-blue-300 hover:bg-blue-50 transition-colors"
      >
        {uploading ? (
          <p className="text-sm text-blue-500">Uploading…</p>
        ) : (
          <>
            <p className="text-sm text-gray-500">Click or drag images here</p>
            <p className="text-xs text-gray-400 mt-0.5">JPEG, PNG or WebP · Max 5 MB each</p>
          </>
        )}
      </div>

      {error && <p className="text-xs text-red-500">{error}</p>}

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
