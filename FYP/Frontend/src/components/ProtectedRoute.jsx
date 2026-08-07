/**
 * Wraps a route element in App.jsx and decides whether the current visitor may see it.
 *
 * There are three checks, applied in order, and each has a different failure:
 *   1. No session at all: redirect to /login.
 *   2. `roles` given and the account's role is not in it: redirect to the home page.
 *      This is what keeps a member out of /admin.
 *   3. `requireSeller` and the account cannot sell: an admin is sent to /admin, and an
 *      ordinary member is shown EnableSellingGate in place, on the same URL, so one
 *      click turns selling on and they land on the page they asked for.
 *
 * This is a usability guard, not the security boundary. The servlets check the session
 * and the role again on every request, so nothing is protected by this component alone.
 */
import { Navigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import EnableSellingGate from './EnableSellingGate';

/**
 * Route guard.
 *
 * @param roles         restrict to specific account roles (used for ADMIN)
 * @param requireSeller require the selling capability. Buying and selling share one
 *                      account, so this is a capability check rather than a role: an
 *                      account that hasn't switched selling on is offered a one-click
 *                      activation in place and then continues to the page.
 */
export default function ProtectedRoute({ children, roles, requireSeller }) {
  const { user, loading } = useAuth();

  // While the session request is still in flight `user` is null but the visitor may well
  // be signed in, so show a skeleton. Redirecting here would bounce a signed-in user to
  // /login on every hard refresh.
  if (loading) {
    return (
      <div className="max-w-5xl mx-auto px-4 py-10 space-y-4" aria-busy="true" aria-label="Loading">
        <div className="skeleton h-9 w-56" />
        <div className="skeleton h-64 w-full rounded-2xl" />
      </div>
    );
  }

  if (!user) return <Navigate to="/login" replace />;
  if (roles && !roles.includes(user.role)) return <Navigate to="/" replace />;
  if (requireSeller && user.role === 'ADMIN') return <Navigate to="/admin" replace />;
  if (requireSeller && !user.canSell) return <EnableSellingGate />;

  return children;
}
