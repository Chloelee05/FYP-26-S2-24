/**
 * Small formatting helpers shared across the whole SPA: money, dates, countdown labels,
 * avatar initials, role badges and entity decoding.
 *
 * They live here so that a price on a card, on the detail page and in an order list are
 * all produced by the same function. Money is always USD, which is what the backend
 * stores, while dates render in the en-SG format the project uses.
 */

/** Every amount in the app goes through this, so prices read the same on every surface. */
export function formatCurrency(amount) {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(amount);
}

/** Date only, no time. For a live auction use timeRemaining below instead. */
export function formatDate(dateStr) {
  return new Date(dateStr).toLocaleDateString('en-SG', { year: 'numeric', month: 'short', day: 'numeric' });
}

/**
 * @param endTime auction end date
 * @param now     reference time — pass a ticking value (see useNow) to keep the
 *                label counting down without a page refresh
 */
export function timeRemaining(endTime, now = Date.now()) {
  const diff = new Date(endTime) - now;
  if (diff <= 0) return 'Ended';
  const h = Math.floor(diff / 3600000);
  const m = Math.floor((diff % 3600000) / 60000);
  const s = Math.floor((diff % 60000) / 1000);
  return `${h}h ${m}m ${s}s`;
}

/**
 * Like timeRemaining, but rolls whole days up rather than showing 3-digit hours —
 * "3d 4h 5m" instead of "76h 5m 3s". Used where a long-running listing would otherwise
 * read as an unhelpably large hour count. Seconds are dropped once days are shown,
 * since they are noise at that range.
 */
export function timeRemainingWithDays(endTime, now = Date.now()) {
  const diff = new Date(endTime) - now;
  if (diff <= 0) return 'Ended';
  const d = Math.floor(diff / 86400000);
  const h = Math.floor((diff % 86400000) / 3600000);
  const m = Math.floor((diff % 3600000) / 60000);
  const s = Math.floor((diff % 60000) / 1000);
  return d > 0 ? `${d}d ${h}h ${m}m` : `${h}h ${m}m ${s}s`;
}

/** Up to two letters for the avatar fallback shown when a member has no profile photo. */
export function getInitials(name = '') {
  return name.split(' ').map(w => w[0]).join('').toUpperCase().slice(0, 2);
}

// Buying and selling are one account type, so BUYER and the legacy SELLER role both
// display as a single "Member". Only ADMIN is a genuinely separate kind of account.
const ROLE_LABELS = {
  BUYER: { label: 'Member', className: 'badge bg-primary-50 text-primary-700 ring-primary-200' },
  SELLER: { label: 'Member', className: 'badge bg-primary-50 text-primary-700 ring-primary-200' },
  ADMIN: { label: 'Admin', className: 'badge bg-red-50 text-red-700 ring-red-200' },
};

/** Label and badge classes for a role, falling back to the raw value for anything unmapped. */
export function getRoleDisplay(role) {
  return ROLE_LABELS[role] ?? { label: role || 'User', className: 'badge bg-ink-100 text-ink-600 ring-ink-200' };
}

// Category endpoints have returned both an array and an error object over the life of the
// project, so callers get a safe array either way, with unnamed rows dropped.
export function normalizeCategories(data) {
  return Array.isArray(data) ? data.filter(c => c?.name) : [];
}

/** Decode HTML entities returned by server-side SecurityUtil.sanitize (e.g. &gt; → >). */
export function decodeHtmlEntities(text) {
  if (!text) return '';
  const el = document.createElement('textarea');
  el.innerHTML = text;
  return el.value;
}
