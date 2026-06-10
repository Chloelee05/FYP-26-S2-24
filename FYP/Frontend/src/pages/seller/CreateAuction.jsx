import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { createAuction } from '../../api/seller';
import { getCategories, getTags } from '../../api/auction';
import { normalizeCategories } from '../../utils/helpers';
import ImageUploader from '../../components/ImageUploader';

// Maps UI condition labels to backend ItemCondition IDs (BRAND_NEW=1, SLIGHTLY_USED=2, USED=3, DAMAGED=4)
const CONDITIONS = [
  { label: 'Brand New',     id: 1 },
  { label: 'Slightly Used', id: 2 },
  { label: 'Used',          id: 3 },
  { label: 'Damaged',       id: 4 },
];

const toIso = (datetimeLocal) =>
  datetimeLocal ? new Date(datetimeLocal).toISOString() : null;

export default function CreateAuction() {
  const navigate = useNavigate();
  const [categories, setCategories] = useState([]);
  const [categoryError, setCategoryError] = useState('');
  const [availableTags, setAvailableTags] = useState([]); // [{id, name}]
  const [selectedTags, setSelectedTags] = useState([]);   // array of tag ids (numbers)
  const [form, setForm] = useState({
    auctionName: '', auctionDetails: '', itemCondition: '1', category: '',
    startPrice: '', maxPrice: '', startDate: '', endDate: '',
  });
  const [imageUrls, setImageUrls] = useState([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    getCategories()
      .then(r => {
        const list = normalizeCategories(r.data);
        setCategories(list);
        setCategoryError(list.length === 0 ? 'No categories available. Ask an admin to add categories.' : '');
      })
      .catch(() => setCategoryError('Could not load categories. Check that Tomcat and PostgreSQL are running.'));
    getTags().then(r => {
      // backend returns a Map<Long,String> serialised as {id: name, ...}
      const raw = r.data ?? {};
      setAvailableTags(Object.entries(raw).map(([id, name]) => ({ id: Number(id), name })));
    }).catch(() => {});
  }, []);

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!form.category) {
      setError('Category is required.');
      return;
    }
    if (imageUrls.length === 0) {
      setError('At least one photo is required.');
      return;
    }
    setError(''); setLoading(true);
    try {
      await createAuction({
        auctionName:    form.auctionName,
        auctionDetails: form.auctionDetails,
        itemCondition:  form.itemCondition,
        category:       form.category     || undefined,
        startPrice:     form.startPrice   || undefined,
        maxPrice:       form.maxPrice     || undefined,
        startDate:      toIso(form.startDate) || undefined,
        endDate:        toIso(form.endDate),
        imageUrls,
        tags:           selectedTags,
      });
      navigate('/seller/dashboard');
    } catch (err) {
      const data = err.response?.data;
      const msg = (typeof data === 'object' && data)
        ? (data.error || data.message)
        : null;
      setError(msg || `Failed to create auction (HTTP ${err.response?.status ?? 'network'}).`);
    } finally {
      setLoading(false);
    }
  };

  const update = (k, v) => setForm(f => ({ ...f, [k]: v }));

  return (
    <div className="max-w-2xl mx-auto px-4 py-8">
      <h1 className="text-2xl font-bold text-gray-900 mb-6">Create New Auction</h1>
      <div className="card p-8">
        {error && <div className="bg-red-50 text-red-600 text-sm px-4 py-2 rounded-lg mb-4">{error}</div>}
        <form onSubmit={handleSubmit} className="space-y-5">

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Title *</label>
            <input value={form.auctionName} onChange={e => update('auctionName', e.target.value)}
              required className="input-field" placeholder="Item title" />
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Description *</label>
            <textarea value={form.auctionDetails} onChange={e => update('auctionDetails', e.target.value)}
              required rows={4}
              className="w-full border border-gray-200 rounded-xl px-4 py-3 text-sm focus:outline-none focus:ring-2 focus:ring-blue-200 resize-none"
              placeholder="Describe your item in detail…" />
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Category *</label>
            <select value={form.category} onChange={e => update('category', e.target.value)}
              required
              disabled={categories.length === 0}
              className="w-full border border-gray-200 rounded-lg px-3 py-3 text-sm focus:outline-none focus:ring-2 focus:ring-blue-200 disabled:bg-gray-50">
              <option value="">-- Select a category --</option>
              {categories.map(c => <option key={c.id ?? c.name} value={c.name}>{c.name}</option>)}
            </select>
            {categoryError && <p className="text-xs text-amber-600 mt-1">{categoryError}</p>}
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Condition *</label>
              <select value={form.itemCondition} onChange={e => update('itemCondition', e.target.value)}
                className="w-full border border-gray-200 rounded-lg px-3 py-3 text-sm focus:outline-none focus:ring-2 focus:ring-blue-200">
                {CONDITIONS.map(c => <option key={c.id} value={c.id}>{c.label}</option>)}
              </select>
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Starting Bid ($)</label>
              <input type="number" min="0.01" step="0.01" value={form.startPrice}
                onChange={e => update('startPrice', e.target.value)}
                className="input-field" placeholder="0.00" />
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Reserve / Max Price ($) <span className="text-gray-400 font-normal">(optional)</span></label>
            <input type="number" min="0.01" step="0.01" value={form.maxPrice}
              onChange={e => update('maxPrice', e.target.value)}
              className="input-field" placeholder="Max value" />
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Start Time <span className="text-gray-400 font-normal">(optional)</span></label>
              <input type="datetime-local" value={form.startDate} onChange={e => update('startDate', e.target.value)}
                className="input-field" />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">End Time *</label>
              <input type="datetime-local" value={form.endDate} onChange={e => update('endDate', e.target.value)}
                required className="input-field" />
            </div>
          </div>

          {availableTags.length > 0 && (
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-2">Tags <span className="text-gray-400 font-normal">(optional)</span></label>
              <div className="flex flex-wrap gap-2">
                {availableTags.map(tag => {
                  const active = selectedTags.includes(tag.id);
                  return (
                    <button
                      key={tag.id}
                      type="button"
                      onClick={() => setSelectedTags(prev =>
                        active ? prev.filter(id => id !== tag.id) : [...prev, tag.id]
                      )}
                      className={`px-3 py-1 rounded-full text-xs font-medium border transition-colors ${
                        active
                          ? 'bg-blue-500 text-white border-blue-500'
                          : 'bg-white text-gray-600 border-gray-200 hover:border-blue-300'
                      }`}
                    >
                      {tag.name}
                    </button>
                  );
                })}
              </div>
            </div>
          )}

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Photos *</label>
            <ImageUploader onChange={(urls) => setImageUrls(urls)} />
          </div>

          <div className="flex gap-3 pt-2">
            <button type="submit" disabled={loading}
              className="flex-1 bg-blue-500 hover:bg-blue-600 text-white font-medium py-3 rounded-lg transition-colors disabled:opacity-50">
              {loading ? 'Creating…' : 'Create Auction'}
            </button>
            <button type="button" onClick={() => navigate('/seller/dashboard')}
              className="flex-1 border border-gray-200 text-gray-700 font-medium py-3 rounded-lg hover:bg-gray-50">
              Cancel
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
