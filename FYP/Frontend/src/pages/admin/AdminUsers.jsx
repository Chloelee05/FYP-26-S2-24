/*
 * User moderation at "/admin/users". ADMIN only, guarded on the parent route in App.jsx.
 * Lists every account from GET /api/admin/users and drives five actions against the admin
 * user endpoints: approve and reject a pending registration, ban and unban, and deactivate.
 * Ban is a status flip and reverses cleanly. Deactivate is the soft delete: the row stays so
 * the account's bids, orders and reviews keep an author, and it asks for a reason that goes
 * into the audit log. Neither action ever deletes the record.
 * Admin accounts have no action buttons at all, so one admin cannot lock out another.
 * Search filters the loaded rows in memory; the list is small enough not to need paging.
 */
import { useState, useEffect } from 'react';
import {
  getAdminUsers, banUser, unbanUser, approveUser, rejectUser, deactivateUser,
} from '../../api/admin';
import { apiErrorMessage } from '../../utils/apiError';

// Backend AdminUserSummary fields: id, username, email, role (BUYER/SELLER/ADMIN),
//   canSell, statusId (1=active, 2=suspended, 4=pending, 5=rejected), joined, bidCount, listingCount
// Buying and selling are one account type, so the table shows member vs admin and
// flags the selling capability separately rather than presenting role as the type.

const STATUS = {
  1: { label: 'active',   className: 'bg-emerald-50 text-emerald-700 ring-emerald-200' },
  2: { label: 'banned',   className: 'bg-red-50 text-red-700 ring-red-200' },
  3: { label: 'deactivated', className: 'bg-ink-200 text-ink-700 ring-ink-300' },
  4: { label: 'pending',  className: 'bg-amber-50 text-amber-700 ring-amber-200' },
  5: { label: 'rejected', className: 'bg-ink-100 text-ink-600 ring-ink-200' },
};

const isActive = (user) => user.statusId === 1;
const isPending = (user) => user.statusId === 4;

export default function AdminUsers() {
  const [users, setUsers] = useState([]);
  const [search, setSearch] = useState('');
  const [msg, setMsg] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    getAdminUsers()
      .then(r => setUsers(r.data ?? []))
      .catch(err => setError(apiErrorMessage(err, 'Could not load users.')));
  }, []);

  // Patches one row's status locally after a successful call, which avoids refetching the
  // whole table for a single change.
  const setStatus = (id, statusId) =>
    setUsers(prev => prev.map(u => u.id === id ? { ...u, statusId } : u));

  // Shared wrapper for all five moderation actions: clears the previous message, runs the
  // call, applies the local status change and reports what happened.
  // Every action used to be wrapped in `catch { /* ignore */ }` with no message area on the
  // page at all, so approving an already-approved account returned 400 and the button just
  // looked dead. The failure is now shown instead of being swallowed.
  const run = async (call, { onDone, success }) => {
    setMsg('');
    setError('');
    try {
      const r = await call();
      onDone?.();
      setMsg(r?.data?.message ?? success);
    } catch (err) {
      setError(apiErrorMessage(err, 'The action could not be completed.'));
    }
  };

  // One button for both directions: the current status decides whether this bans or unbans.
  const handleBan = (user) => isActive(user)
    ? run(() => banUser(user.id), {
        onDone: () => setStatus(user.id, 2), success: 'Account banned.' })
    : run(() => unbanUser(user.id), {
        onDone: () => setStatus(user.id, 1), success: 'Account unbanned.' });

  const handleApprove = (user) => run(() => approveUser(user.id), {
    onDone: () => setStatus(user.id, 1), success: 'Account approved.' });

  const handleReject = (user) => run(() => rejectUser(user.id), {
    onDone: () => setStatus(user.id, 5), success: 'Account rejected.' });

  // Soft delete. A reason is required, not optional, because the deactivation is recorded in
  // the audit log and "no reason given" is not much of an audit trail. Cancelling the prompt
  // or leaving it blank aborts without calling the server.
  const handleDeactivate = (user) => {
    const reason = window.prompt(
      `Deactivate ${user.username}? The account is kept so their bids, orders and reviews `
      + `keep an author, and the change is reversible.\n\nReason for the audit log:`);
    if (reason == null || reason.trim() === '') return;
    return run(() => deactivateUser(user.id, reason), {
      onDone: () => setStatus(user.id, 3), success: 'Account deactivated.' });
  };

  const pendingCount = users.filter(isPending).length;

  const filtered = users.filter(u =>
    u.username?.toLowerCase().includes(search.toLowerCase()) ||
    u.email?.toLowerCase().includes(search.toLowerCase())
  );

  return (
    <div className="p-8">
      <h1 className="page-title">Customer Management</h1>
      <p className="page-subtitle mb-6">
        Approve, ban, unban or deactivate customer accounts
        {pendingCount > 0 && (
          <span className="ml-2 px-2 py-0.5 rounded-full bg-amber-50 text-amber-700 ring-amber-200 text-xs font-medium">
            {pendingCount} awaiting approval
          </span>
        )}
      </p>

      {error && (
        <div className="text-sm text-red-600 bg-red-50 ring-1 ring-red-200 rounded-lg px-3 py-2 mb-4">
          {error}
        </div>
      )}
      {msg && <div className="text-sm text-primary-600 mb-4">{msg}</div>}

      <div className="card overflow-hidden">
        <div className="p-4 border-b border-ink-100">
          <input
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="Search users…"
            className="input-field w-64"
          />
        </div>
        <table className="w-full text-sm">
          <thead className="text-xs text-ink-500 uppercase tracking-wider bg-ink-50 border-b border-ink-200">
            <tr>
              {['User', 'Email', 'Role', 'Activity', 'Status', 'Actions'].map(h => (
                <th key={h} className="px-4 py-3 text-left font-bold whitespace-nowrap">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-ink-100">
            {filtered.map(user => {
              const active = isActive(user);
              const pending = isPending(user);
              const roleLower = (user.role ?? '').toLowerCase();
              const status = STATUS[user.statusId] ?? { label: 'unknown', className: 'bg-ink-100 text-ink-600 ring-ink-200' };
              const isAdmin = roleLower === 'admin';
              return (
                <tr key={user.id} className="hover:bg-ink-50 transition-colors">
                  <td className="px-4 py-4">
                    <p className="font-medium text-ink-900">{user.username}</p>
                    <p className="text-xs text-ink-400">Joined {user.joined}</p>
                  </td>
                  <td className="px-4 py-4 text-ink-600">{user.email}</td>
                  <td className="px-4 py-4">
                    <span className={`badge ${isAdmin ? 'bg-red-50 text-red-700 ring-red-200' : 'bg-primary-50 text-primary-700 ring-primary-200'}`}>
                      {isAdmin ? 'admin' : 'member'}
                    </span>
                    {!isAdmin && user.canSell && (
                      <span className="badge bg-purple-50 text-purple-700 ring-purple-200 ml-1.5">selling</span>
                    )}
                  </td>
                  <td className="px-4 py-4 text-ink-600">
                    <p>Bids: {user.bidCount ?? 0}</p>
                    <p>Listings: {user.listingCount ?? 0}</p>
                  </td>
                  <td className="px-4 py-4">
                    <span className={`badge ${status.className}`}>
                      {status.label}
                    </span>
                  </td>
                  <td className="px-4 py-4">
                    {/* Which controls appear is decided by status. An admin row gets none,
                        a pending registration gets approve and reject, a rejected or already
                        deactivated account gets a label, and an ordinary member gets ban and
                        deactivate. */}
                    {isAdmin ? (
                      <span className="text-xs text-ink-400">—</span>
                    ) : pending ? (
                      <div className="flex gap-2">
                        <button
                          onClick={() => handleApprove(user)}
                          className="px-3 py-1.5 rounded text-sm font-medium text-white bg-green-500 hover:bg-green-600 transition-colors"
                        >
                          Approve
                        </button>
                        <button
                          onClick={() => handleReject(user)}
                          className="px-3 py-1.5 rounded text-sm font-medium text-white bg-ink-500 hover:bg-ink-600 transition-colors"
                        >
                          Reject
                        </button>
                      </div>
                    ) : user.statusId === 5 ? (
                      <span className="text-xs text-ink-400">Rejected</span>
                    ) : user.statusId === 3 ? (
                      <span className="text-xs text-ink-400">Deactivated</span>
                    ) : (
                      <div className="flex gap-2">
                        <button
                          onClick={() => handleBan(user)}
                          className={`px-4 py-1.5 rounded text-sm font-medium text-white transition-colors ${active ? 'bg-red-500 hover:bg-red-600' : 'bg-green-500 hover:bg-green-600'}`}
                        >
                          {active ? 'Ban User' : 'Unban User'}
                        </button>
                        <button
                          onClick={() => handleDeactivate(user)}
                          title="Soft delete: keeps the row so their bids, orders and reviews keep an author"
                          className="px-3 py-1.5 rounded text-sm font-medium border border-ink-300 text-ink-600 hover:bg-ink-100 transition-colors"
                        >
                          Deactivate
                        </button>
                      </div>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {filtered.length === 0 && (
          <div className="text-center py-10 text-ink-400">No users found.</div>
        )}
      </div>
    </div>
  );
}
