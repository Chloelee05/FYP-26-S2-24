/*
 * Holds the signed in account for the whole app. Every access decision in the front end
 * reads from here: ProtectedRoute for route guards, the navbar for which links to show,
 * and individual pages for whether the viewer is the owner of a listing.
 * Calls GET /session on mount, POST /login and POST /logout. The user object carries the
 * role (USER or ADMIN) and the canSell capability that gates the seller tools.
 * The token lives in sessionStorage rather than localStorage so it dies with the tab, and
 * the axios interceptor reads it from there for the Authorization header.
 */
import { createContext, useContext, useState, useEffect } from 'react';
import { getSession, login as apiLogin, logout as apiLogout } from '../api/auth';

// Exported so tests can render a component under a stubbed session without
// standing up the real provider and its /session request.
// eslint-disable-next-line react-refresh/only-export-components
export const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  // null means signed out. Otherwise the session payload: id, username, role, canSell.
  const [user, setUser] = useState(null);
  // True until the first /session call settles. ProtectedRoute waits on this so a page
  // reload does not flash a redirect to /login before the session has been restored.
  const [loading, setLoading] = useState(true);

  // Restore the session once on mount. A failure is the normal guest case, not an error,
  // so it just clears any stale token and leaves user as null.
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

  // Signs in. If the account has two factor enabled the server answers with requires2fa
  // and a short lived pending token instead of a session, and the caller routes to
  // /2fa-verify. user stays null until that second step succeeds.
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

  // Clears the local session in a finally block, so the user is signed out on this device
  // even if the server call fails or the network is down.
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
