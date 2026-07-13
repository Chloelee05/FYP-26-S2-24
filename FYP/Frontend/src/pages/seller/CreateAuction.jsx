import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { createAuction } from '../../api/seller';
import { getCategories, getTags } from '../../api/auction';
import { normalizeCategories } from '../../utils/helpers';
import { publicPath } from '../../utils/appBase';
import ImageUploader from '../../components/ImageUploader';

// Maps UI condition labels to backend ItemCondition IDs (BRAND_NEW=1, SLIGHTLY_USED=2, USED=3, DAMAGED=4)
const CONDITIONS = [
  { label: 'Brand New',     id: 1 },
  { label: 'Slightly Used', id: 2 },
  { label: 'Used',          id: 3 },
  { label: 'Damaged',       id: 4 },
];

// Auction strategy ids must match the backend AuctionType enum.
const STRATEGIES = [
  { id: '1', label: 'Standard (Ascending / Price Up)', help: 'Buyers bid upward; the highest bid at close wins. Supports auto-bidding.' },
  { id: '2', label: 'Dutch (Low Start High → Descending)', help: 'Price starts high and falls over time; the first buyer to accept the current price wins immediately.' },
  { id: '3', label: 'Blind (Public / Sealed Bid)', help: 'Each buyer submits one hidden bid; amounts stay secret until the auction ends, then the highest wins.' },
];

const toIso = (datetimeLocal) =>
  datetimeLocal ? new Date(datetimeLocal).toISOString() : null;

const formatPreviewDate = (datetimeLocal) =>
  datetimeLocal ? new Date(datetimeLocal).toLocaleString() : '—';

export default function CreateAuction() {
  const navigate = useNavigate();
  const [categories, setCategories] = useState([]);
  const [categoryError, setCategoryError] = useState('');
  const [availableTags, setAvailableTags] = useState([]); // [{id, name}]
  const [selectedTags, setSelectedTags] = useState([]);   // array of tag ids (numbers)
  const [form, setForm] = useState({
    auctionName: '', auctionDetails: '', itemCondition: '1', category: '',
    auctionType: '1', quantity: '1', costPrice: '', dutchFloorPrice: '',
    startPrice: '', maxPrice: '', buyItNowPrice: '', startDate: '', endDate: '',
  });
  const [imageUrls, setImageUrls] = useState([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [showPreview, setShowPreview] = useState(false);

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
    if (form.auctionType === '2') {
      const start = Number(form.startPrice);
      const floor = Number(form.dutchFloorPrice);
      if (!start || start <= 0) { setError('Dutch auctions need a starting (high) price.'); return; }
      if (form.dutchFloorPrice === '' || floor < 0) { setError('Dutch auctions need a floor (low) price.'); return; }
      if (floor >= start) { setError('Floor price must be below the starting price.'); return; }
    }
    setError(''); setLoading(true);
    try {
      await createAuction({
        auctionName:     form.auctionName,
        auctionDetails:  form.auctionDetails,
        itemCondition:   form.itemCondition,
        auctionType:     form.auctionType,
        quantity:        form.quantity     || '1',
        costPrice:       form.costPrice    || undefined,
        dutchFloorPrice: form.auctionType === '2' ? form.dutchFloorPrice : undefined,
        category:        form.category     || undefined,
        startPrice:      form.startPrice   || undefined,
        maxPrice:        form.maxPrice     || undefined,
        buyItNowPrice:   form.auctionType === '1' && form.buyItNowPrice ? form.buyItNowPrice : undefined,
        startDate:       toIso(form.startDate) || undefined,
        endDate:         toIso(form.endDate),
        imageUrls,
        tags:            selectedTags,
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

  const conditionLabel = CONDITIONS.find(c => String(c.id) === String(form.itemCondition))?.label ?? '';
  const strategyLabel = STRATEGIES.find(s => s.id === form.auctionType)?.label ?? '';
  const selectedTagNames = availableTags.filter(t => selectedTags.includes(t.id)).map(t => t.name);

  return (
    <div className="max-w-2xl mx-auto px-4 py-8">
      {showPreview && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4">
          <div className="bg-white rounded-2xl shadow-xl max-w-lg w-full max-h-[90vh] overflow-y-auto p-6">
            <h3 className="font-bold text-gray-900 text-lg mb-1">Listing preview</h3>
            <p className="text-xs text-gray-500 mb-4">How buyers will see this auction — nothing has been published yet.</p>

            <div className="bg-gray-100 rounded-xl aspect-video flex items-center justify-center mb-3 overflow-hidden">
              {imageUrls[0]
                ? <img src={publicPath(imageUrls[0])} alt={form.auctionName || 'Preview'} className="w-full h-full object-contain" />
                : <span className="text-sm text-gray-400">No photos yet</span>}
            </div>
            {imageUrls.length > 1 && (
              <div className="flex gap-2 mb-4 overflow-x-auto">
                {imageUrls.map((url, i) => (
                  <img key={i} src={publicPath(url)} alt="" className="w-16 h-12 object-cover rounded-lg border border-gray-200 shrink-0" />
                ))}
              </div>
            )}

            <h4 className="text-xl font-bold text-gray-900 mb-1">{form.auctionName || 'Untitled listing'}</h4>
            <div className="flex flex-wrap gap-2 text-xs text-gray-500 mb-3">
              {form.category && <span className="bg-gray-100 px-2 py-0.5 rounded">{form.category}</span>}
              <span>Condition: {conditionLabel}</span>
              <span>{strategyLabel}</span>
            </div>
            {selectedTagNames.length > 0 && (
              <div className="flex flex-wrap gap-1.5 mb-3">
                {selectedTagNames.map(name => (
                  <span key={name} className="bg-blue-50 text-blue-600 border border-blue-100 px-2 py-0.5 rounded-full text-xs">{name}</span>
                ))}
              </div>
            )}

            <div className="grid grid-cols-2 gap-3 text-sm mb-4">
              <div>
                <p className="text-xs text-gray-400">Starting bid</p>
                <p className="font-semibold text-gray-900">{form.startPrice ? `$${Number(form.startPrice).toFixed(2)}` : '—'}</p>
              </div>
              {form.auctionType === '2' ? (
                <div>
                  <p className="text-xs text-gray-400">Floor price</p>
                  <p className="font-semibold text-gray-900">{form.dutchFloorPrice ? `$${Number(form.dutchFloorPrice).toFixed(2)}` : '—'}</p>
                </div>
              ) : (
                <div>
                  <p className="text-xs text-gray-400">Reserve / max</p>
                  <p className="font-semibold text-gray-900">{form.maxPrice ? `$${Number(form.maxPrice).toFixed(2)}` : '—'}</p>
                </div>
              )}
              {form.auctionType === '1' && form.buyItNowPrice && (
                <div>
                  <p className="text-xs text-gray-400">Buy It Now</p>
                  <p className="font-semibold text-green-600">${Number(form.buyItNowPrice).toFixed(2)}</p>
                </div>
              )}
              <div>
                <p className="text-xs text-gray-400">Quantity</p>
                <p className="font-semibold text-gray-900">{form.quantity || '1'}</p>
              </div>
              <div>
                <p className="text-xs text-gray-400">Starts</p>
                <p className="font-semibold text-gray-900">{formatPreviewDate(form.startDate)}</p>
              </div>
              <div>
                <p className="text-xs text-gray-400">Ends</p>
                <p className="font-semibold text-gray-900">{formatPreviewDate(form.endDate)}</p>
              </div>
            </div>

            <div className="mb-5">
              <p className="text-xs font-semibold text-gray-500 mb-1">Description</p>
              <p className="text-sm text-gray-600 whitespace-pre-wrap">{form.auctionDetails || 'No description yet.'}</p>
            </div>

            <button
              type="button"
              onClick={() => setShowPreview(false)}
              className="w-full border border-gray-200 text-gray-700 font-medium py-2.5 rounded-lg hover:bg-gray-50"
            >
              Close preview
            </button>
          </div>
        </div>
      )}

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

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Auction Strategy *</label>
            <select value={form.auctionType} onChange={e => update('auctionType', e.target.value)}
              className="w-full border border-gray-200 rounded-lg px-3 py-3 text-sm focus:outline-none focus:ring-2 focus:ring-blue-200">
              {STRATEGIES.map(s => <option key={s.id} value={s.id}>{s.label}</option>)}
            </select>
            <p className="text-xs text-gray-500 mt-1">
              {STRATEGIES.find(s => s.id === form.auctionType)?.help}
            </p>
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
              <label className="block text-sm font-medium text-gray-700 mb-1">Quantity *</label>
              <input type="number" min="1" step="1" value={form.quantity}
                onChange={e => update('quantity', e.target.value)}
                className="input-field" placeholder="1" />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                {form.auctionType === '2' ? 'Starting (High) Price ($) *' : 'Starting Bid ($)'}
              </label>
              <input type="number" min="0.01" step="0.01" value={form.startPrice}
                onChange={e => update('startPrice', e.target.value)}
                className="input-field" placeholder="0.00" />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                Cost Price ($) <span className="text-gray-400 font-normal">(private)</span>
              </label>
              <input type="number" min="0" step="0.01" value={form.costPrice}
                onChange={e => update('costPrice', e.target.value)}
                className="input-field" placeholder="Your cost (not shown to buyers)" />
            </div>
          </div>

          {form.auctionType === '2' ? (
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Floor (Low) Price ($) *</label>
              <input type="number" min="0" step="0.01" value={form.dutchFloorPrice}
                onChange={e => update('dutchFloorPrice', e.target.value)}
                className="input-field" placeholder="Lowest price the clock may reach" />
              <p className="text-xs text-gray-500 mt-1">The price falls from the starting price to this floor by the end time.</p>
            </div>
          ) : (
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Reserve / Max Price ($) <span className="text-gray-400 font-normal">(optional)</span></label>
              <input type="number" min="0.01" step="0.01" value={form.maxPrice}
                onChange={e => update('maxPrice', e.target.value)}
                className="input-field" placeholder="Max value" />
            </div>
          )}

          {form.auctionType === '1' && (
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                Buy It Now ($) <span className="text-gray-400 font-normal">(optional)</span>
              </label>
              <input type="number" min="0.01" step="0.01" value={form.buyItNowPrice}
                onChange={e => update('buyItNowPrice', e.target.value)}
                className="input-field" placeholder="Instant purchase price" />
              <p className="text-xs text-gray-500 mt-1">Buyers can purchase immediately at this price instead of bidding.</p>
            </div>
          )}

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
            <button type="button" onClick={() => setShowPreview(true)}
              className="flex-1 border border-blue-200 text-blue-600 font-medium py-3 rounded-lg hover:bg-blue-50">
              Preview
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
