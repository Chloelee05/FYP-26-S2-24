import { useState, useEffect } from 'react';
import { getAdminUsers, banUser, unbanUser, approveUser, rejectUser } from '../../api/admin';

// Backend AdminUserSummary fields: id, username, email, role (BUYER/SELLER/ADMIN),
//   statusId (1=active, 2=suspended, 4=pending, 5=rejected), joined, bidCount, listingCount

const STATUS = {
  1: { label: 'active',   className: 'bg-green-100 text-green-600' },
  2: { label: 'banned',   className: 'bg-red-100 text-red-600' },
  4: { label: 'pending',  className: 'bg-yellow-100 text-yellow-700' },
  5: { label: 'rejected', className: 'bg-gray-200 text-gray-600' },
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
      <h1 className="text-2xl font-bold text-gray-900 mb-1">User Moderation</h1>
      <p className="text-gray-400 text-sm mb-6">
        Manage users and enforce platform policies
        {pendingCount > 0 && (
          <span className="ml-2 px-2 py-0.5 rounded-full bg-yellow-100 text-yellow-700 text-xs font-medium">
            {pendingCount} awaiting approval
          </span>
        )}
      </p>

      <div className="card overflow-hidden">
        <div className="p-4 border-b border-gray-100">
          <input
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="Search users…"
            className="border border-gray-200 rounded-lg px-3 py-2 text-sm w-64 focus:outline-none focus:ring-2 focus:ring-blue-200"
          />
        </div>
        <table className="w-full text-sm">
          <thead className="text-xs text-gray-400 uppercase tracking-wide bg-gray-50">
            <tr>
              {['User', 'Email', 'Role', 'Activity', 'Status', 'Actions'].map(h => (
                <th key={h} className="px-4 py-3 text-left font-semibold">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-50">
            {filtered.map(user => {
              const active = isActive(user);
              const pending = isPending(user);
              const roleLower = (user.role ?? '').toLowerCase();
              const status = STATUS[user.statusId] ?? { label: 'unknown', className: 'bg-gray-100 text-gray-500' };
              const isAdmin = roleLower === 'admin';
              return (
                <tr key={user.id} className="hover:bg-gray-50 transition-colors">
                  <td className="px-4 py-4">
                    <p className="font-medium text-gray-900">{user.username}</p>
                    <p className="text-xs text-gray-400">Joined {user.joined}</p>
                  </td>
                  <td className="px-4 py-4 text-gray-600">{user.email}</td>
                  <td className="px-4 py-4">
                    <span className={`px-2 py-0.5 rounded text-xs font-medium ${roleLower === 'buyer' ? 'bg-blue-100 text-blue-600' : 'bg-purple-100 text-purple-600'}`}>
                      {roleLower}
                    </span>
                  </td>
                  <td className="px-4 py-4 text-gray-600">
                    <p>Bids: {user.bidCount ?? 0}</p>
                    <p>Listings: {user.listingCount ?? 0}</p>
                  </td>
                  <td className="px-4 py-4">
                    <span className={`px-2 py-0.5 rounded text-xs font-medium ${status.className}`}>
                      {status.label}
                    </span>
                  </td>
                  <td className="px-4 py-4">
                    {isAdmin ? (
                      <span className="text-xs text-gray-400">—</span>
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
                          className="px-3 py-1.5 rounded text-sm font-medium text-white bg-gray-500 hover:bg-gray-600 transition-colors"
                        >
                          Reject
                        </button>
                      </div>
                    ) : user.statusId === 5 ? (
                      <span className="text-xs text-gray-400">Rejected</span>
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
          <div className="text-center py-10 text-gray-400">No users found.</div>
        )}
      </div>
    </div>
  );
}
