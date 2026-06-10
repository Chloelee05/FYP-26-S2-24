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

const ROLE_LABELS = {
  BUYER: { label: 'Buyer', className: 'bg-blue-100 text-blue-600' },
  SELLER: { label: 'Seller', className: 'bg-purple-100 text-purple-600' },
  ADMIN: { label: 'Admin', className: 'bg-red-100 text-red-600' },
};

export function getRoleDisplay(role) {
  return ROLE_LABELS[role] ?? { label: role || 'User', className: 'bg-gray-100 text-gray-600' };
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
