/**
 * Where the app is mounted. The same build runs at the site root under the Vite dev
 * server and under the /online-auction context path once it is deployed to Tomcat, so
 * nothing in the code may hardcode either.
 *
 * appBase() is used by src/api/config.js to build the axios baseURL; publicPath() is used
 * anywhere a stored path such as an uploaded image URL is put into a src or href.
 */

/** Application base path (empty in Vite dev, /online-auction in Docker/production). */
export function appBase() {
  const base = import.meta.env.BASE_URL || '/';
  return base.endsWith('/') ? base.slice(0, -1) : base;
}

// An absolute http(s) URL is returned untouched, since it already points somewhere
// specific; only a relative or root-relative path gets the prefix.
/** Prefixes root-relative asset/API paths with the Vite base path. */
export function publicPath(path) {
  if (!path) return path;
  if (/^https?:\/\//i.test(path)) return path;
  const prefix = appBase();
  if (path.startsWith('/')) return `${prefix}${path}`;
  return `${prefix}/${path}`;
}
