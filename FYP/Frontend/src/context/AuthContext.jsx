import { createContext, useContext, useState, useEffect } from 'react';
import { getSession, login as apiLogin, logout as apiLogout } from '../api/auth';

// Exported so tests can render a component under a stubbed session without
// standing up the real provider and its /session request.
// eslint-disable-next-line react-refresh/only-export-components
export const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    getSession()
      .then(res => {
        if (res.data?.token) sessionStorage.setItem('authToken', res.data.token);
        setUser(res.data);
      })
      .catch(() => {
        sessionStorage.removeItem('authToken');
        setUser(null);
      })
      .finally(() => setLoading(false));
  }, []);

  const login = async (email, password) => {
    const res = await apiLogin(email, password);
    if (res.data?.requires2fa) {
      // Store the pending token so the interceptor sends it to /2fa/verify-login.
      if (res.data.pendingToken) sessionStorage.setItem('authToken', res.data.pendingToken);
      return res.data; // Don't set user yet — 2FA verification pending
    }
    if (res.data?.token) sessionStorage.setItem('authToken', res.data.token);
    setUser(res.data);
    return res.data;
  };

  /** Re-reads the session, e.g. after enabling selling, so capability gates update. */
  const refreshUser = async () => {
    try {
      const res = await getSession();
      setUser(res.data);
      return res.data;
    } catch {
      return null;
    }
  };

  const logout = async () => {
    try {
      await apiLogout();
    } finally {
      sessionStorage.removeItem('authToken');
      setUser(null);
    }
  };

  return (
    <AuthContext.Provider value={{ user, setUser, login, logout, refreshUser, loading }}>
      {children}
    </AuthContext.Provider>
  );
}

// Kept beside the provider on purpose: ~40 modules import useAuth from here, and moving
// it to its own file to satisfy fast refresh would churn all of them for no runtime gain.
// eslint-disable-next-line react-refresh/only-export-components
export const useAuth = () => useContext(AuthContext);
