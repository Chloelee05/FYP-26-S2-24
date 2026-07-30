export function formatCurrency(amount) {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(amount);
}

export function formatDate(dateStr) {
  return new Date(dateStr).toLocaleDateString('en-SG', { year: 'numeric', month: 'short', day: 'numeric' });
}

export function timeRemaining(endTime) {
  const diff = new Date(endTime) - new Date();
  if (diff <= 0) return 'Ended';
  const h = Math.floor(diff / 3600000);
  const m = Math.floor((diff % 3600000) / 60000);
  const s = Math.floor((diff % 60000) / 1000);
  return `${h}h ${m}m ${s}s`;
}

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

export function getRoleDisplay(role) {
  return ROLE_LABELS[role] ?? { label: role || 'User', className: 'badge bg-ink-100 text-ink-600 ring-ink-200' };
}

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
