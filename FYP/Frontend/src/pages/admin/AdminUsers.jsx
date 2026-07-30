import { useState, useEffect } from 'react';
import { getAdminUsers, banUser, unbanUser, approveUser, rejectUser } from '../../api/admin';

// Backend AdminUserSummary fields: id, username, email, role (BUYER/SELLER/ADMIN),
//   canSell, statusId (1=active, 2=suspended, 4=pending, 5=rejected), joined, bidCount, listingCount
// Buying and selling are one account type, so the table shows member vs admin and
// flags the selling capability separately rather than presenting role as the type.

const STATUS = {
  1: { label: 'active',   className: 'bg-emerald-50 text-emerald-700 ring-emerald-200' },
  2: { label: 'banned',   className: 'bg-red-50 text-red-700 ring-red-200' },
  4: { label: 'pending',  className: 'bg-amber-50 text-amber-700 ring-amber-200' },
  5: { label: 'rejected', className: 'bg-ink-100 text-ink-600 ring-ink-200' },
};

const isActive = (user) => user.statusId === 1;
const isPending = (user) => user.statusId === 4;

export default function AdminUsers() {
  const [users, setUsers] = useState([]);
  const [search, setSearch] = useState('');

  useEffect(() => {
    getAdminUsers().then(r => setUsers(r.data ?? [])).catch(() => {});
  }, []);

  const setStatus = (id, statusId) =>
    setUsers(prev => prev.map(u => u.id === id ? { ...u, statusId } : u));

  const handleBan = async (user) => {
    try {
      if (isActive(user)) { await banUser(user.id); setStatus(user.id, 2); }
      else { await unbanUser(user.id); setStatus(user.id, 1); }
    } catch { /* ignore */ }
  };

  const handleApprove = async (user) => {
    try { await approveUser(user.id); setStatus(user.id, 1); } catch { /* ignore */ }
  };

  const handleReject = async (user) => {
    try { await rejectUser(user.id); setStatus(user.id, 5); } catch { /* ignore */ }
  };

  const pendingCount = users.filter(isPending).length;

  const filtered = users.filter(u =>
    u.username?.toLowerCase().includes(search.toLowerCase()) ||
    u.email?.toLowerCase().includes(search.toLowerCase())
  );

  return (
    <div className="p-8">
      <h1 className="page-title">User Moderation</h1>
      <p className="page-subtitle mb-6">
        Manage users and enforce platform policies
        {pendingCount > 0 && (
          <span className="ml-2 px-2 py-0.5 rounded-full bg-amber-50 text-amber-700 ring-amber-200 text-xs font-medium">
            {pendingCount} awaiting approval
          </span>
        )}
      </p>

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
                    ) : (
                      <button
                        onClick={() => handleBan(user)}
                        className={`px-4 py-1.5 rounded text-sm font-medium text-white transition-colors ${active ? 'bg-red-500 hover:bg-red-600' : 'bg-green-500 hover:bg-green-600'}`}
                      >
                        {active ? 'Ban User' : 'Unban User'}
                      </button>
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
