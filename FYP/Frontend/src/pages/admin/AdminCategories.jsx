import { useState, useEffect } from 'react';
import { Plus, Trash2, RotateCcw } from 'lucide-react';
import { getAdminCategories, createCategory, deleteCategory, restoreCategory } from '../../api/admin';

// Backend Category fields: id, name, description, displayOrder, slug, deleted, createdAt, auctionCount

export default function AdminCategories() {
  const [categories, setCategories] = useState([]);
  const [newName, setNewName] = useState('');
  const [adding, setAdding] = useState(false);

  useEffect(() => {
    getAdminCategories().then(r => setCategories(r.data ?? [])).catch(() => {});
  }, []);

  const handleAdd = async (e) => {
    e.preventDefault();
    if (!newName.trim()) return;
    setAdding(true);
    try {
      const res = await createCategory({ name: newName.trim() });
      setCategories(prev => [...prev, res.data]);
      setNewName('');
    } catch {
      alert('Failed to create category.');
    } finally {
      setAdding(false);
    }
  };

  const handleDelete = async (id) => {
    if (!window.confirm('Delete this category?')) return;
    try {
      await deleteCategory(id);
      setCategories(prev => prev.map(c => c.id === id ? { ...c, deleted: true } : c));
    } catch {}
  };

  const handleRestore = async (id) => {
    try {
      await restoreCategory(id);
      setCategories(prev => prev.map(c => c.id === id ? { ...c, deleted: false } : c));
    } catch {
      alert('Failed to restore category.');
    }
  };

  const visible  = categories.filter(c => !c.deleted);
  const deleted  = categories.filter(c => c.deleted);

  return (
    <div className="p-8">
      <h1 className="text-2xl font-bold text-gray-900 mb-1">Categories</h1>
      <p className="text-gray-400 text-sm mb-6">Manage auction categories</p>

      <form onSubmit={handleAdd} className="flex gap-3 mb-6">
        <input
          value={newName}
          onChange={e => setNewName(e.target.value)}
          placeholder="New category name"
          className="border border-gray-200 rounded-lg px-4 py-2.5 text-sm flex-1 max-w-xs focus:outline-none focus:ring-2 focus:ring-blue-200"
        />
        <button type="submit" disabled={adding} className="flex items-center gap-2 bg-blue-500 text-white px-4 py-2.5 rounded-lg text-sm font-medium hover:bg-blue-600 disabled:opacity-50">
          <Plus size={16} /> Add Category
        </button>
      </form>

      <div className="card overflow-hidden">
        <table className="w-full text-sm">
          <thead className="text-xs text-gray-400 uppercase tracking-wide bg-gray-50">
            <tr>
              <th className="px-4 py-3 text-left font-semibold">Category</th>
              <th className="px-4 py-3 text-left font-semibold">Listings</th>
              <th className="px-4 py-3 text-left font-semibold">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-50">
            {visible.map(cat => (
              <tr key={cat.id} className="hover:bg-gray-50">
                <td className="px-4 py-3 font-medium text-gray-900">{cat.name}</td>
                <td className="px-4 py-3 text-gray-500">{cat.auctionCount ?? 0} listings</td>
                <td className="px-4 py-3">
                  <button onClick={() => handleDelete(cat.id)} className="text-gray-400 hover:text-red-500 transition-colors p-1">
                    <Trash2 size={14} />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {visible.length === 0 && (
          <div className="text-center py-8 text-gray-400">No categories yet.</div>
        )}
      </div>

      {deleted.length > 0 && (
        <div className="mt-8">
          <h2 className="text-base font-semibold text-gray-500 mb-3">Deactivated Categories</h2>
          <div className="card overflow-hidden">
            <table className="w-full text-sm">
              <thead className="text-xs text-gray-400 uppercase tracking-wide bg-gray-50">
                <tr>
                  <th className="px-4 py-3 text-left font-semibold">Category</th>
                  <th className="px-4 py-3 text-left font-semibold">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-50">
                {deleted.map(cat => (
                  <tr key={cat.id} className="hover:bg-gray-50">
                    <td className="px-4 py-3 text-gray-400 line-through">{cat.name}</td>
                    <td className="px-4 py-3">
                      <button
                        onClick={() => handleRestore(cat.id)}
                        className="flex items-center gap-1 text-xs text-blue-500 hover:text-blue-700 font-medium"
                        title="Restore category"
                      >
                        <RotateCcw size={13} /> Restore
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
