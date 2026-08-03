import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { createAuction } from '../../api/seller';
import { getCategories, getTags } from '../../api/auction';
import { normalizeCategories } from '../../utils/helpers';
import { publicPath } from '../../utils/appBase';
import ImageUploader from '../../components/ImageUploader';
import Modal from '../../components/Modal';
import { Eye } from 'lucide-react';

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
    // An omitted starting price used to be stored as 0, which means an item could be won for
    // any amount at all. There is no such thing as a listing with no floor.
    const start = Number(form.startPrice);
    if (form.startPrice === '' || !(start > 0)) {
      setError('Starting price is required and must be greater than 0.');
      return;
    }
    if (form.auctionType === '2') {
      const floor = Number(form.dutchFloorPrice);
      if (form.dutchFloorPrice === '' || floor < 0) { setError('Dutch auctions need a floor (low) price.'); return; }
      if (floor >= start) { setError('Floor price must be below the starting price.'); return; }
    }
    // Buy It Now at or below the starting bid is an instant-loss trap: the first buyer to
    // notice buys the item for less than anyone is allowed to bid.
    if (form.auctionType === '1' && form.buyItNowPrice !== '') {
      const bin = Number(form.buyItNowPrice);
      if (!(bin > start)) {
        setError('Buy It Now price must be above the starting price.');
        return;
      }
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
      navigate('/seller/listings');
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
        <Modal
          title="Listing preview"
          subtitle="How buyers will see this auction — nothing has been published yet."
          icon={Eye}
          size="lg"
          onClose={() => setShowPreview(false)}
        >
          <div className="p-6">
            <div className="bg-ink-100 rounded-xl aspect-video flex items-center justify-center mb-3 overflow-hidden">
              {imageUrls[0]
                ? <img src={publicPath(imageUrls[0])} alt={form.auctionName || 'Preview'} className="w-full h-full object-contain" />
                : <span className="text-sm text-ink-400">No photos yet</span>}
            </div>
            {imageUrls.length > 1 && (
              <div className="flex gap-2 mb-4 overflow-x-auto">
                {imageUrls.map((url, i) => (
                  <img key={i} src={publicPath(url)} alt="" className="w-16 h-12 object-contain bg-ink-50 rounded-lg border border-ink-200 shrink-0" />
                ))}
              </div>
            )}

            <h4 className="text-xl font-bold text-ink-900 mb-1">{form.auctionName || 'Untitled listing'}</h4>
            <div className="flex flex-wrap gap-2 text-xs text-ink-500 mb-3">
              {form.category && <span className="bg-ink-100 px-2 py-0.5 rounded">{form.category}</span>}
              <span>Condition: {conditionLabel}</span>
              <span>{strategyLabel}</span>
            </div>
            {selectedTagNames.length > 0 && (
              <div className="flex flex-wrap gap-1.5 mb-3">
                {selectedTagNames.map(name => (
                  <span key={name} className="bg-primary-50 text-primary-600 border border-primary-100 px-2 py-0.5 rounded-full text-xs">{name}</span>
                ))}
              </div>
            )}

            <div className="grid grid-cols-2 gap-3 text-sm mb-4">
              <div>
                <p className="text-xs text-ink-400">Starting bid</p>
                <p className="font-semibold text-ink-900">{form.startPrice ? `$${Number(form.startPrice).toFixed(2)}` : '—'}</p>
              </div>
              {form.auctionType === '2' ? (
                <div>
                  <p className="text-xs text-ink-400">Floor price</p>
                  <p className="font-semibold text-ink-900">{form.dutchFloorPrice ? `$${Number(form.dutchFloorPrice).toFixed(2)}` : '—'}</p>
                </div>
              ) : (
                <div>
                  <p className="text-xs text-ink-400">Reserve / max</p>
                  <p className="font-semibold text-ink-900">{form.maxPrice ? `$${Number(form.maxPrice).toFixed(2)}` : '—'}</p>
                </div>
              )}
              {form.auctionType === '1' && form.buyItNowPrice && (
                <div>
                  <p className="text-xs text-ink-400">Buy It Now</p>
                  <p className="font-semibold text-green-600">${Number(form.buyItNowPrice).toFixed(2)}</p>
                </div>
              )}
              <div>
                <p className="text-xs text-ink-400">Quantity</p>
                <p className="font-semibold text-ink-900">{form.quantity || '1'}</p>
              </div>
              <div>
                <p className="text-xs text-ink-400">Starts</p>
                <p className="font-semibold text-ink-900">{formatPreviewDate(form.startDate)}</p>
              </div>
              <div>
                <p className="text-xs text-ink-400">Ends</p>
                <p className="font-semibold text-ink-900">{formatPreviewDate(form.endDate)}</p>
              </div>
            </div>

            <div className="mb-5">
              <p className="text-xs font-semibold text-ink-500 mb-1">Description</p>
              <p className="text-sm text-ink-600 whitespace-pre-wrap">{form.auctionDetails || 'No description yet.'}</p>
            </div>

            <button type="button" onClick={() => setShowPreview(false)} className="btn-secondary btn-block">
              Close preview
            </button>
          </div>
        </Modal>
      )}

      <h1 className="page-title">Create New Auction</h1>
      <p className="page-subtitle mb-6">Set your price, timing and photos — buyers see this exactly as previewed.</p>
      <div className="card p-6 sm:p-8">
        {error && <div className="alert-error mb-5">{error}</div>}
        <form onSubmit={handleSubmit} className="space-y-5">

          <div>
            <label className="field-label">Title *</label>
            <input value={form.auctionName} onChange={e => update('auctionName', e.target.value)}
              required className="input-field" placeholder="Item title" />
          </div>

          <div>
            <label className="field-label">Description *</label>
            <textarea value={form.auctionDetails} onChange={e => update('auctionDetails', e.target.value)}
              required rows={4}
              className="textarea-field"
              placeholder="Describe your item in detail…" />
          </div>

          <div>
            <label className="field-label">Category *</label>
            <select value={form.category} onChange={e => update('category', e.target.value)}
              required
              disabled={categories.length === 0}
              className="select-field disabled:bg-ink-50">
              <option value="">-- Select a category --</option>
              {categories.map(c => <option key={c.id ?? c.name} value={c.name}>{c.name}</option>)}
            </select>
            {categoryError && <p className="text-xs text-amber-600 mt-1">{categoryError}</p>}
          </div>

          <div>
            <label className="field-label">Auction Strategy *</label>
            <select value={form.auctionType} onChange={e => update('auctionType', e.target.value)}
              className="select-field">
              {STRATEGIES.map(s => <option key={s.id} value={s.id}>{s.label}</option>)}
            </select>
            <p className="field-hint">
              {STRATEGIES.find(s => s.id === form.auctionType)?.help}
            </p>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="field-label">Condition *</label>
              <select value={form.itemCondition} onChange={e => update('itemCondition', e.target.value)}
                className="select-field">
                {CONDITIONS.map(c => <option key={c.id} value={c.id}>{c.label}</option>)}
              </select>
            </div>
            <div>
              <label className="field-label">Quantity *</label>
              <input type="number" min="1" step="1" value={form.quantity}
                onChange={e => update('quantity', e.target.value)}
                className="input-field" placeholder="1" />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="field-label">
                {form.auctionType === '2' ? 'Starting (High) Price ($) *' : 'Starting Bid ($) *'}
              </label>
              <input type="number" min="0.01" step="0.01" value={form.startPrice}
                onChange={e => update('startPrice', e.target.value)}
                required
                className="input-field" placeholder="0.00" />
            </div>
            <div>
              <label className="field-label">
                Cost Price ($) <span className="text-ink-400 font-normal">(private)</span>
              </label>
              <input type="number" min="0" step="0.01" value={form.costPrice}
                onChange={e => update('costPrice', e.target.value)}
                className="input-field" placeholder="Your cost (not shown to buyers)" />
            </div>
          </div>

          {form.auctionType === '2' ? (
            <div>
              <label className="field-label">Floor (Low) Price ($) *</label>
              <input type="number" min="0" step="0.01" value={form.dutchFloorPrice}
                onChange={e => update('dutchFloorPrice', e.target.value)}
                className="input-field" placeholder="Lowest price the clock may reach" />
              <p className="field-hint">The price falls from the starting price to this floor by the end time.</p>
            </div>
          ) : (
            <div>
              <label className="field-label">Reserve / Max Price ($) <span className="text-ink-400 font-normal">(optional)</span></label>
              <input type="number" min="0.01" step="0.01" value={form.maxPrice}
                onChange={e => update('maxPrice', e.target.value)}
                className="input-field" placeholder="Max value" />
            </div>
          )}

          {form.auctionType === '1' && (
            <div>
              <label className="field-label">
                Buy It Now ($) <span className="text-ink-400 font-normal">(optional)</span>
              </label>
              <input type="number" min="0.01" step="0.01" value={form.buyItNowPrice}
                onChange={e => update('buyItNowPrice', e.target.value)}
                className="input-field" placeholder="Instant purchase price" />
              <p className="field-hint">
                Buyers can purchase immediately at this price instead of bidding. It must be
                above your starting bid.
              </p>
            </div>
          )}

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="field-label">Start Time <span className="text-ink-400 font-normal">(optional)</span></label>
              <input type="datetime-local" value={form.startDate} onChange={e => update('startDate', e.target.value)}
                className="input-field" />
            </div>
            <div>
              <label className="field-label">End Time *</label>
              <input type="datetime-local" value={form.endDate} onChange={e => update('endDate', e.target.value)}
                required className="input-field" />
            </div>
          </div>

          {availableTags.length > 0 && (
            <div>
              <label className="field-label">Tags <span className="text-ink-400 font-normal">(optional)</span></label>
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
                      className={`px-3 py-1.5 rounded-full text-xs font-semibold border transition-all ${
                        active
                          ? 'bg-primary-600 text-white border-primary-600 shadow-sm'
                          : 'bg-white text-ink-600 border-ink-200 hover:border-primary-300 hover:text-primary-600'
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
            <label className="field-label">Photos *</label>
            <ImageUploader onChange={(urls) => setImageUrls(urls)} />
          </div>

          <div className="flex flex-wrap gap-3 pt-3 border-t border-ink-100">
            <button type="submit" disabled={loading} className="btn-primary btn-lg flex-1">
              {loading ? 'Creating…' : 'Create Auction'}
            </button>
            <button
              type="button"
              onClick={() => setShowPreview(true)}
              className="btn btn-lg flex-1 border border-primary-200 text-primary-700 bg-primary-50 hover:bg-primary-100"
            >
              Preview
            </button>
            <button type="button" onClick={() => navigate('/seller/listings')} className="btn-secondary btn-lg flex-1">
              Cancel
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
