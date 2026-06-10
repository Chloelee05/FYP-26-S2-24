import { createContext, useContext, useState, useEffect } from 'react';
import { getSession, login as apiLogin, logout as apiLogout } from '../api/auth';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    getSession()
      .then(res => setUser(res.data))
      .catch(() => setUser(null))
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

  const logout = async () => {
    try {
      await apiLogout();
    } finally {
      sessionStorage.removeItem('authToken');
      setUser(null);
    }
  };

  return (
    <AuthContext.Provider value={{ user, setUser, login, logout, loading }}>
      {children}
    </AuthContext.Provider>
  );
}

export const useAuth = () => useContext(AuthContext);
