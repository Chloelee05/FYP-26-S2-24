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
