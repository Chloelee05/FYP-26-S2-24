/**
 * The single axios instance every other module in src/api uses. Import it as `api` and
 * call relative paths such as '/auction/12', which resolve against the Java backend.
 *
 * baseURL is `${appBase()}/api`. appBase() is empty under the Vite dev server (where the
 * proxy forwards /api to Tomcat on port 8080) and '/online-auction' in the Docker and
 * production builds, so no module has to know which one it is running under.
 *
 * Two things travel with every request: the JSESSIONID cookie, because withCredentials is
 * true and the SPA is served from the same origin as the servlets, and a bearer token if
 * the tab has one (see the interceptor below). Servlets accept either.
 *
 * The default Content-Type is JSON, but most POST helpers override it with
 * application/x-www-form-urlencoded, since the servlets read fields via
 * request.getParameter().
 */
import axios from 'axios';
import { appBase } from '../utils/appBase';

const api = axios.create({
  baseURL: `${appBase()}/api`,
  withCredentials: true,
  headers: { 'Content-Type': 'application/json' },
});

// Per-tab auth: each tab keeps its own token in sessionStorage and sends it as a
// bearer header. Because sessionStorage is scoped per tab (unlike cookies), different
// tabs can be logged in as different accounts at the same time.
api.interceptors.request.use((config) => {
  const token = sessionStorage.getItem('authToken');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

export default api;
