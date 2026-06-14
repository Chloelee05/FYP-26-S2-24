import { useState, useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Filter, ChevronDown } from 'lucide-react';
import AuctionCard from '../components/AuctionCard';
import { searchAuctions, getCategories } from '../api/auction';

// Condition labels must match ItemCondition.displayName on the backend.
const CONDITIONS = [
  { label: 'Brand New',     value: 'BRAND_NEW' },
  { label: 'Slightly Used', value: 'SLIGHTLY_USED' },
  { label: 'Used',          value: 'USED' },
  { label: 'Damaged',       value: 'DAMAGED' },
];
// Sort values must match SearchSort.paramValue on the backend.
const SORTS = [
  { value: 'endingSoon', label: 'Ending Soonest' },
  { value: 'newest',     label: 'Newly Listed' },
  { value: 'priceLow',   label: 'Price: Low to High' },
  { value: 'priceHigh',  label: 'Price: High to Low' },
];

export default function Search() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [results, setResults] = useState([]);
  const [categories, setCategories] = useState([]);
  const [loading, setLoading] = useState(false);
  const [filters, setFilters] = useState({
    q: searchParams.get('q') || '',
    category: searchParams.get('category') || '',
    minPrice: '',
    maxPrice: '',
    condition: '',
    sortBy: 'ending_soon',
  });

  useEffect(() => {
    getCategories().then(r => setCategories(r.data)).catch(() => {});
  }, []);

  useEffect(() => {
    const q = searchParams.get('q') || '';
    const category = searchParams.get('category') || '';
    setFilters(f => (f.q === q && f.category === category ? f : { ...f, q, category }));
  }, [searchParams]);

  useEffect(() => {
    setLoading(true);
    const params = Object.fromEntries(Object.entries(filters).filter(([, v]) => v));
    searchAuctions(params)
      .then(r => setResults(r.data.results ?? r.data))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [filters]);

  const update = (key, val) => setFilters(f => ({ ...f, [key]: val }));

  return (
    <div className="max-w-7xl mx-auto px-4 py-8 flex gap-6">
      {/* Sidebar Filters */}
      <aside className="w-56 shrink-0 hidden md:block">
        <div className="card p-4 space-y-5">
          <h3 className="font-bold text-gray-900 flex items-center gap-2"><Filter size={16} /> Filters</h3>

          <div>
            <label className="text-xs font-semibold text-gray-500 uppercase tracking-wide block mb-2">Category</label>
            <select
              value={filters.category}
              onChange={e => update('category', e.target.value)}
              className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-200"
            >
              <option value="">All</option>
              {categories.map(c => <option key={c.name} value={c.name}>{c.name}</option>)}
            </select>
          </div>

          <div>
            <label className="text-xs font-semibold text-gray-500 uppercase tracking-wide block mb-2">Price Range</label>
            <div className="flex gap-2">
              <input
                placeholder="Min"
                type="number"
                value={filters.minPrice}
                onChange={e => update('minPrice', e.target.value)}
                className="w-full border border-gray-200 rounded-lg px-2 py-2 text-sm focus:outline-none"
              />
              <input
                placeholder="Max"
                type="number"
                value={filters.maxPrice}
                onChange={e => update('maxPrice', e.target.value)}
                className="w-full border border-gray-200 rounded-lg px-2 py-2 text-sm focus:outline-none"
              />
            </div>
          </div>

          <div>
            <label className="text-xs font-semibold text-gray-500 uppercase tracking-wide block mb-2">Condition</label>
            {CONDITIONS.map(c => (
              <label key={c.value} className="flex items-center gap-2 text-sm text-gray-600 mb-1 cursor-pointer">
                <input
                  type="radio"
                  name="condition"
                  value={c.value}
                  checked={filters.condition === c.value}
                  onChange={() => update('condition', c.value)}
                />
                {c.label}
              </label>
            ))}
            {filters.condition && (
              <button onClick={() => update('condition', '')} className="text-xs text-blue-500 underline mt-1">Clear</button>
            )}
          </div>
        </div>
      </aside>

      {/* Results */}
      <div className="flex-1">
        <div className="flex items-center justify-between mb-4">
          <h2 className="font-bold text-gray-900">
            {filters.q ? `Results for "${filters.q}"` : 'All Auctions'}
          </h2>
          <div className="flex items-center gap-2">
            <select
              value={filters.sortBy}
              onChange={e => update('sortBy', e.target.value)}
              className="border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none"
            >
              {SORTS.map(s => <option key={s.value} value={s.value}>{s.label}</option>)}
            </select>
          </div>
        </div>

        {loading ? (
          <div className="flex items-center justify-center h-40 text-gray-400">Searching…</div>
        ) : results.length === 0 ? (
          <div className="text-center py-16 text-gray-400">No auctions found. Try different filters.</div>
        ) : (
          <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
            {results.map(a => <AuctionCard key={a.id} auction={a} />)}
          </div>
        )}
      </div>
    </div>
  );
}
