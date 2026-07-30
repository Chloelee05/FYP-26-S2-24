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
