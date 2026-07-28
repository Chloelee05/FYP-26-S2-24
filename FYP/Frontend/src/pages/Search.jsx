import { useState, useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Filter, SlidersHorizontal, X, SearchX, AlertCircle, MapPin } from 'lucide-react';
import AuctionCard from '../components/AuctionCard';
import { searchAuctions, getCategories } from '../api/auction';
import { apiErrorMessage } from '../utils/apiError';

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
// `endWithin` is sent to the backend in hours (SearchFilter.endWithinHours).
const END_WITHIN = [
  { value: '1',  label: 'Next hour' },
  { value: '6',  label: 'Next 6 hours' },
  { value: '24', label: 'Next 24 hours' },
  { value: '72', label: 'Next 3 days' },
];

const PAGE_SIZE = 12;

function CardSkeleton() {
  return (
    <div className="card overflow-hidden">
      <div className="aspect-square skeleton rounded-none" />
      <div className="p-4 space-y-2.5">
        <div className="skeleton h-3.5 w-4/5" />
        <div className="skeleton h-3 w-1/2" />
        <div className="skeleton h-6 w-2/3 mt-3" />
        <div className="skeleton h-9 w-full rounded-xl" />
      </div>
    </div>
  );
}

export default function Search() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [results, setResults] = useState([]);
  const [categories, setCategories] = useState([]);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);
  const [showFilters, setShowFilters] = useState(false);
  const [error, setError] = useState('');
  const [filters, setFilters] = useState({
    q: searchParams.get('q') || '',
    category: searchParams.get('category') || '',
    minPrice: '',
    maxPrice: '',
    condition: '',
    location: '',
    endWithin: '',
    sortBy: 'endingSoon',
  });

  useEffect(() => {
    getCategories().then(r => setCategories(r.data)).catch(() => {});
  }, []);

  useEffect(() => {
    const q = searchParams.get('q') || '';
    const category = searchParams.get('category') || '';
    setFilters(f => (f.q === q && f.category === category ? f : { ...f, q, category }));
  }, [searchParams]);

  // Filters changed → reset to page 1 and replace results
  useEffect(() => {
    setLoading(true);
    setPage(1);
    setError('');
    const params = Object.fromEntries(Object.entries(filters).filter(([, v]) => v));
    searchAuctions({ ...params, page: 1, size: PAGE_SIZE })
      .then(r => {
        setResults(r.data.results ?? r.data);
        setTotalPages(r.data.totalPages ?? 1);
      })
      .catch(err => {
        setResults([]);
        setError(apiErrorMessage(err, 'Search failed. Please try again.'));
      })
      .finally(() => setLoading(false));
  }, [filters]);

  const handleLoadMore = () => {
    const nextPage = page + 1;
    setLoadingMore(true);
    setError('');
    const params = Object.fromEntries(Object.entries(filters).filter(([, v]) => v));
    searchAuctions({ ...params, page: nextPage, size: PAGE_SIZE })
      .then(r => {
        setResults(prev => [...prev, ...(r.data.results ?? r.data)]);
        setPage(nextPage);
        setTotalPages(r.data.totalPages ?? totalPages);
      })
      .catch(err => setError(apiErrorMessage(err, 'Could not load more results.')))
      .finally(() => setLoadingMore(false));
  };

  const update = (key, val) => setFilters(f => ({ ...f, [key]: val }));

  const clearAll = () => {
    setSearchParams({});
    setFilters({
      q: '', category: '', minPrice: '', maxPrice: '', condition: '',
      location: '', endWithin: '', sortBy: filters.sortBy,
    });
  };

  const activeCount = ['category', 'minPrice', 'maxPrice', 'condition', 'location', 'endWithin']
    .filter(k => filters[k]).length;

  const filterPanel = (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h3 className="font-bold text-ink-900 flex items-center gap-2">
          <Filter size={16} className="text-primary-600" /> Filters
        </h3>
        {activeCount > 0 && (
          <button onClick={clearAll} className="text-xs font-semibold text-primary-600 hover:text-primary-700">
            Clear all
          </button>
        )}
      </div>

      <div>
        <label className="field-label" htmlFor="filter-category">Category</label>
        <select
          id="filter-category"
          value={filters.category}
          onChange={e => update('category', e.target.value)}
          className="select-field"
        >
          <option value="">All categories</option>
          {categories.map(c => <option key={c.name} value={c.name}>{c.name}</option>)}
        </select>
      </div>

      <div>
        <label className="field-label">Price range</label>
        <div className="flex items-center gap-2">
          <input
            placeholder="Min"
            type="number"
            value={filters.minPrice}
            onChange={e => update('minPrice', e.target.value)}
            className="input-field px-3"
          />
          <span className="text-ink-300">–</span>
          <input
            placeholder="Max"
            type="number"
            value={filters.maxPrice}
            onChange={e => update('maxPrice', e.target.value)}
            className="input-field px-3"
          />
        </div>
      </div>

      <div>
        <label className="field-label" htmlFor="filter-end-within">Ending within</label>
        <select
          id="filter-end-within"
          value={filters.endWithin}
          onChange={e => update('endWithin', e.target.value)}
          className="select-field"
        >
          <option value="">Any time</option>
          {END_WITHIN.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
      </div>

      <div>
        <label className="field-label" htmlFor="filter-location">Location</label>
        <div className="relative">
          <MapPin size={15} className="absolute left-3.5 top-1/2 -translate-y-1/2 text-ink-400 pointer-events-none" />
          <input
            id="filter-location"
            value={filters.location}
            onChange={e => update('location', e.target.value)}
            placeholder="e.g. Singapore"
            className="input-field pl-9"
          />
        </div>
        <p className="field-hint">Matches location mentioned in the title or description.</p>
      </div>

      <div>
        <div className="flex items-center justify-between mb-2">
          <span className="field-label mb-0">Condition</span>
          {filters.condition && (
            <button onClick={() => update('condition', '')} className="text-xs font-semibold text-primary-600 hover:text-primary-700">
              Clear
            </button>
          )}
        </div>
        <div className="space-y-1">
          {CONDITIONS.map(c => {
            const selected = filters.condition === c.value;
            return (
              <label
                key={c.value}
                className={`flex items-center gap-2.5 text-sm rounded-lg px-2.5 py-2 cursor-pointer transition-colors ${
                  selected ? 'bg-primary-50 text-primary-700 font-semibold' : 'text-ink-600 hover:bg-ink-50'
                }`}
              >
                <input
                  type="radio"
                  name="condition"
                  value={c.value}
                  checked={selected}
                  onChange={() => update('condition', c.value)}
                  className="w-4 h-4 border-ink-300 text-primary-600 focus:ring-primary-500/40"
                />
                {c.label}
              </label>
            );
          })}
        </div>
      </div>
    </div>
  );

  return (
    <div className="max-w-7xl mx-auto px-4 py-8">
      <div className="flex gap-6">
        {/* Sidebar filters (desktop) */}
        <aside className="w-60 shrink-0 hidden md:block">
          <div className="card p-5 sticky top-24">{filterPanel}</div>
        </aside>

        {/* Results */}
        <div className="flex-1 min-w-0">
          <div className="flex flex-wrap items-center justify-between gap-3 mb-5">
            <div>
              <h1 className="page-title text-xl sm:text-2xl">
                {filters.q ? <>Results for “{filters.q}”</> : 'All Auctions'}
              </h1>
              <p className="text-sm text-ink-500 mt-0.5">
                {loading ? 'Searching…' : `${results.length} auction${results.length === 1 ? '' : 's'} shown`}
              </p>
            </div>
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => setShowFilters(true)}
                className="btn-secondary btn-sm md:hidden"
              >
                <SlidersHorizontal size={14} /> Filters
                {activeCount > 0 && (
                  <span className="ml-0.5 grid place-items-center min-w-[18px] h-[18px] rounded-full bg-primary-600 text-white text-[10px] font-bold">
                    {activeCount}
                  </span>
                )}
              </button>
              <select
                value={filters.sortBy}
                onChange={e => update('sortBy', e.target.value)}
                aria-label="Sort results"
                className="select-field w-auto py-2 text-sm"
              >
                {SORTS.map(s => <option key={s.value} value={s.value}>{s.label}</option>)}
              </select>
            </div>
          </div>

          {/* Active filter chips */}
          {activeCount > 0 && (
            <div className="flex flex-wrap gap-2 mb-5">
              {filters.category && (
                <button onClick={() => update('category', '')} className="badge-info hover:bg-primary-100 transition-colors">
                  {filters.category} <X size={12} />
                </button>
              )}
              {filters.condition && (
                <button onClick={() => update('condition', '')} className="badge-info hover:bg-primary-100 transition-colors">
                  {CONDITIONS.find(c => c.value === filters.condition)?.label} <X size={12} />
                </button>
              )}
              {(filters.minPrice || filters.maxPrice) && (
                <button
                  onClick={() => setFilters(f => ({ ...f, minPrice: '', maxPrice: '' }))}
                  className="badge-info hover:bg-primary-100 transition-colors"
                >
                  ${filters.minPrice || '0'} – ${filters.maxPrice || '∞'} <X size={12} />
                </button>
              )}
              {filters.endWithin && (
                <button onClick={() => update('endWithin', '')} className="badge-info hover:bg-primary-100 transition-colors">
                  {END_WITHIN.find(o => o.value === filters.endWithin)?.label} <X size={12} />
                </button>
              )}
              {filters.location && (
                <button onClick={() => update('location', '')} className="badge-info hover:bg-primary-100 transition-colors">
                  {filters.location} <X size={12} />
                </button>
              )}
            </div>
          )}

          {error && (
            <div className="alert-error mb-5">
              <AlertCircle size={16} className="mt-0.5 shrink-0" />
              <span>{error}</span>
            </div>
          )}

          {loading ? (
            <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4 sm:gap-5">
              {Array.from({ length: 8 }, (_, i) => <CardSkeleton key={i} />)}
            </div>
          ) : results.length === 0 ? (
            <div className="card p-14 text-center">
              <span className="grid place-items-center w-14 h-14 rounded-2xl bg-ink-100 text-ink-400 mx-auto mb-4">
                <SearchX size={26} />
              </span>
              <p className="font-semibold text-ink-800">No auctions found</p>
              <p className="text-sm text-ink-500 mt-1">Try a different keyword or loosen your filters.</p>
              {activeCount > 0 && (
                <button onClick={clearAll} className="btn-secondary mt-5">Clear all filters</button>
              )}
            </div>
          ) : (
            <>
              <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4 sm:gap-5">
                {results.map(a => <AuctionCard key={a.auctionId ?? a.id} auction={a} />)}
              </div>
              {page < totalPages ? (
                <div className="flex justify-center mt-10">
                  <button onClick={handleLoadMore} disabled={loadingMore} className="btn-secondary btn-lg">
                    {loadingMore ? 'Loading…' : 'Load more auctions'}
                  </button>
                </div>
              ) : (
                <p className="text-center text-xs text-ink-400 mt-8">You’ve reached the end of the results.</p>
              )}
            </>
          )}
        </div>
      </div>

      {/* Filter drawer (mobile) */}
      {showFilters && (
        <div className="fixed inset-0 z-50 md:hidden">
          <div className="absolute inset-0 bg-ink-900/50 backdrop-blur-sm animate-fade-in" onClick={() => setShowFilters(false)} />
          <div className="absolute inset-y-0 left-0 w-[85%] max-w-xs bg-white shadow-pop p-5 overflow-y-auto animate-fade-in">
            <div className="flex justify-end mb-2">
              <button onClick={() => setShowFilters(false)} className="p-2 rounded-lg text-ink-500 hover:bg-ink-100">
                <X size={18} />
              </button>
            </div>
            {filterPanel}
            <button onClick={() => setShowFilters(false)} className="btn-primary btn-block mt-6">
              Show results
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
