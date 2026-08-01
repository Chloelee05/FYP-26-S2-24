import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { AuthContext } from '../context/AuthContext';
import ProtectedRoute from './ProtectedRoute';

/**
 * Renders the guard at /guarded with a stubbed session, plus landing pages for each
 * destination it can redirect to, so assertions read as "where did the user end up".
 */
function renderGuard({ user = null, loading = false, ...guardProps }) {
  return render(
    <AuthContext.Provider value={{ user, loading }}>
      <MemoryRouter initialEntries={['/guarded']}>
        <Routes>
          <Route path="/guarded" element={
            <ProtectedRoute {...guardProps}><h1>Secret</h1></ProtectedRoute>
          } />
          <Route path="/login" element={<h1>Login page</h1>} />
          <Route path="/" element={<h1>Home page</h1>} />
          <Route path="/admin" element={<h1>Admin console</h1>} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

const member = { role: 'BUYER', canSell: true };

describe('ProtectedRoute', () => {
  it('shows a skeleton while the session is still resolving', () => {
    renderGuard({ loading: true });
    expect(screen.getByLabelText('Loading')).toBeInTheDocument();
    expect(screen.queryByText('Secret')).not.toBeInTheDocument();
  });

  // Deciding before the session resolves would bounce signed-in users to /login.
  it('does not redirect while loading, even with no user yet', () => {
    renderGuard({ loading: true, user: null });
    expect(screen.queryByText('Login page')).not.toBeInTheDocument();
  });

  it('sends a signed-out visitor to the login page', () => {
    renderGuard({ user: null });
    expect(screen.getByText('Login page')).toBeInTheDocument();
  });

  it('renders the page for a signed-in member', () => {
    renderGuard({ user: member });
    expect(screen.getByText('Secret')).toBeInTheDocument();
  });

  it('sends the wrong role home', () => {
    renderGuard({ user: member, roles: ['ADMIN'] });
    expect(screen.getByText('Home page')).toBeInTheDocument();
  });

  it('lets a matching role through', () => {
    renderGuard({ user: { role: 'ADMIN' }, roles: ['ADMIN'] });
    expect(screen.getByText('Secret')).toBeInTheDocument();
  });

  // An admin has no seller side, so they go to their own console rather than being
  // offered the "turn on selling" gate.
  it('sends an admin to the admin console instead of the selling gate', () => {
    renderGuard({ user: { role: 'ADMIN', canSell: false }, requireSeller: true });
    expect(screen.getByText('Admin console')).toBeInTheDocument();
  });

  it('offers the selling gate to a member who has not switched selling on', () => {
    renderGuard({ user: { role: 'BUYER', canSell: false }, requireSeller: true });
    expect(screen.queryByText('Secret')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /selling/i })).toBeInTheDocument();
  });

  it('lets a seller-enabled member through', () => {
    renderGuard({ user: member, requireSeller: true });
    expect(screen.getByText('Secret')).toBeInTheDocument();
  });
});
