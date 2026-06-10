import { useState, useEffect } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { getAuctionForEdit, editAuction } from '../../api/seller';
import { getCategories } from '../../api/auction';
import { normalizeCategories } from '../../utils/helpers';
import ImageUploader from '../../components/ImageUploader';

const CONDITIONS = [
  { label: 'Brand New',     id: 1 },
  { label: 'Slightly Used', id: 2 },
  { label: 'Used',          id: 3 },
  { label: 'Damaged',       id: 4 },
];

const toLocalDatetime = (isoString) => {
  if (!isoString) return '';
  const d = new Date(isoString);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
};

const toIso = (datetimeLocal) =>
  datetimeLocal ? new Date(datetimeLocal).toISOString() : null;

export default function EditAuction() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [categories, setCategories] = useState([]);
  const [categoryError, setCategoryError] = useState('');
  const [bidCount, setBidCount] = useState(0);
  const [form, setForm] = useState({
    title: '', description: '', category: '', itemCondition: '1', endDate: '',
  });
  const [existingImages, setExistingImages] = useState([]);
  const [newImageUrls, setNewImageUrls] = useState([]);
  const [deleteImageIds, setDeleteImageIds] = useState([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [dataLoaded, setDataLoaded] = useState(false);

  useEffect(() => {
    getCategories()
      .then(r => {
        const list = normalizeCategories(r.data);
        setCategories(list);
        setCategoryError(list.length === 0 ? 'No categories available.' : '');
      })
      .catch(() => setCategoryError('Could not load categories.'));
    getAuctionForEdit(id)
      .then(r => {
        const d = r.data;
        setBidCount(d.bidCount ?? 0);
        setForm({
          title:         d.title        || '',
          description:   d.description  || '',
          category:      d.category     || '',
          itemCondition: String(d.conditionId || 1),
          endDate:       toLocalDatetime(d.endDate),
        });
        setExistingImages(d.images ?? []);
      })
      .catch(() => {})
      .finally(() => setDataLoaded(true));
  }, [id]);

  const hasBids = bidCount > 0;

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError(''); setLoading(true);
    try {
      await editAuction({
        auctionId:      id,
        title:          form.title,
        description:    form.description,
        category:       form.category     || undefined,
        itemCondition:  form.itemCondition,
        endDate:        toIso(form.endDate) || undefined,
        newImageUrls,
        deleteImageIds,
      });
      navigate('/seller/dashboard');
    } catch (err) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Failed to update auction.');
    } finally {
      setLoading(false);
    }
  };

  const update = (k, v) => setForm(f => ({ ...f, [k]: v }));

  return (
    <div className="max-w-2xl mx-auto px-4 py-8">
      <h1 className="text-2xl font-bold text-gray-900 mb-6">Edit Auction</h1>
      <div className="card p-8">
        {error && <div className="bg-red-50 text-red-600 text-sm px-4 py-2 rounded-lg mb-4">{error}</div>}
        {hasBids && (
          <div className="bg-yellow-50 border border-yellow-200 text-yellow-700 text-sm px-4 py-2 rounded-lg mb-4">
            Bids have been placed. Only the end date can be updated.
          </div>
        )}
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Title</label>
            <input
              value={form.title}
              onChange={e => update('title', e.target.value)}
              disabled={hasBids}
              className={`input-field ${hasBids ? 'bg-gray-100 cursor-not-allowed' : ''}`}
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Description</label>
            <textarea
              value={form.description}
              onChange={e => update('description', e.target.value)}
              disabled={hasBids}
              rows={5}
              className={`w-full border border-gray-200 rounded-xl px-4 py-3 text-sm focus:outline-none focus:ring-2 focus:ring-blue-200 resize-none ${hasBids ? 'bg-gray-100 cursor-not-allowed' : ''}`}
            />
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Category</label>
              <select
                value={form.category}
                onChange={e => update('category', e.target.value)}
                disabled={hasBids}
                className={`w-full border border-gray-200 rounded-lg px-3 py-3 text-sm focus:outline-none focus:ring-2 focus:ring-blue-200 ${hasBids ? 'bg-gray-100 cursor-not-allowed' : ''}`}
              >
                <option value="">-- Select a category --</option>
                {categories.map(c => <option key={c.id ?? c.name} value={c.name}>{c.name}</option>)}
              </select>
              {categoryError && <p className="text-xs text-amber-600 mt-1">{categoryError}</p>}
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Condition</label>
              <select
                value={form.itemCondition}
                onChange={e => update('itemCondition', e.target.value)}
                disabled={hasBids}
                className={`w-full border border-gray-200 rounded-lg px-3 py-3 text-sm focus:outline-none focus:ring-2 focus:ring-blue-200 ${hasBids ? 'bg-gray-100 cursor-not-allowed' : ''}`}
              >
                {CONDITIONS.map(c => <option key={c.id} value={c.id}>{c.label}</option>)}
              </select>
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">End Time</label>
            <input
              type="datetime-local"
              value={form.endDate}
              onChange={e => update('endDate', e.target.value)}
              className="input-field"
            />
          </div>

          {!hasBids && (
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Photos</label>
              {dataLoaded && (
                <ImageUploader
                  existingImages={existingImages}
                  onChange={(urls, delIds) => { setNewImageUrls(urls); setDeleteImageIds(delIds); }}
                />
              )}
            </div>
          )}

          <div className="flex gap-3 pt-2">
            <button type="submit" disabled={loading}
              className="flex-1 bg-blue-500 hover:bg-blue-600 text-white font-medium py-3 rounded-lg disabled:opacity-50">
              {loading ? 'Saving…' : 'Save Changes'}
            </button>
            <button type="button" onClick={() => navigate('/seller/dashboard')}
              className="flex-1 border border-gray-200 text-gray-700 py-3 rounded-lg hover:bg-gray-50">
              Cancel
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
