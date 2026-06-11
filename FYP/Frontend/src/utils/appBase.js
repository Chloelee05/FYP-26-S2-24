/** Application base path (empty in Vite dev, /online-auction in Docker/production). */
export function appBase() {
  const base = import.meta.env.BASE_URL || '/';
  return base.endsWith('/') ? base.slice(0, -1) : base;
}

/** Prefixes root-relative asset/API paths with the Vite base path. */
export function publicPath(path) {
  if (!path) return path;
  if (/^https?:\/\//i.test(path)) return path;
  const prefix = appBase();
  if (path.startsWith('/')) return `${prefix}${path}`;
  return `${prefix}/${path}`;
}
