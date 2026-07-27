import { useState, useEffect } from 'react';
import { Plus, Trash2, RotateCcw, Pencil } from 'lucide-react';
import { getAdminCategories, createCategory, editCategory, deleteCategory, restoreCategory } from '../../api/admin';

// Backend Category fields: id, name, description, displayOrder, slug, deleted, createdAt, auctionCount

export default function AdminCategories() {
  const [categories, setCategories] = useState([]);
  const [newName, setNewName] = useState('');
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState(null); // category being edited
  const [editForm, setEditForm] = useState({ name: '', description: '', displayOrder: '' });
  const [saving, setSaving] = useState(false);

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

  const openEdit = (cat) => {
    setEditing(cat);
    setEditForm({
      name: cat.name ?? '',
      description: cat.description ?? '',
      displayOrder: cat.displayOrder ?? '',
    });
  };

  const handleEditSave = async (e) => {
    e.preventDefault();
    if (!editForm.name.trim()) return;
    setSaving(true);
    try {
      await editCategory(editing.id, {
        name: editForm.name.trim(),
        description: editForm.description.trim(),
        displayOrder: editForm.displayOrder === '' ? null : editForm.displayOrder,
      });
      setCategories(prev => prev.map(c => c.id === editing.id
        ? { ...c, name: editForm.name.trim(), description: editForm.description.trim(),
            displayOrder: editForm.displayOrder === '' ? c.displayOrder : Number(editForm.displayOrder) }
        : c));
      setEditing(null);
    } catch (err) {
      alert(err.response?.data?.error || 'Failed to update category.');
    } finally {
      setSaving(false);
    }
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

      {editing && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4">
          <form onSubmit={handleEditSave} className="bg-white rounded-2xl shadow-xl w-full max-w-md p-6 space-y-4">
            <h3 className="font-bold text-gray-900">Edit Category</h3>
            <div>
              <label className="block text-xs text-gray-500 mb-1">Name</label>
              <input
                value={editForm.name}
                onChange={e => setEditForm(f => ({ ...f, name: e.target.value }))}
                required
                className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-200"
              />
            </div>
            <div>
              <label className="block text-xs text-gray-500 mb-1">Description</label>
              <textarea
                value={editForm.description}
                onChange={e => setEditForm(f => ({ ...f, description: e.target.value }))}
                rows={3}
                className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-200 resize-none"
              />
            </div>
            <div>
              <label className="block text-xs text-gray-500 mb-1">Display order</label>
              <input
                type="number"
                value={editForm.displayOrder}
                onChange={e => setEditForm(f => ({ ...f, displayOrder: e.target.value }))}
                className="w-32 border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-200"
              />
            </div>
            <div className="flex gap-3 pt-1">
              <button type="submit" disabled={saving}
                className="flex-1 bg-blue-500 hover:bg-blue-600 text-white text-sm font-medium py-2.5 rounded-lg disabled:opacity-50">
                {saving ? 'Saving…' : 'Save Changes'}
              </button>
              <button type="button" onClick={() => setEditing(null)}
                className="flex-1 border border-gray-200 text-gray-600 text-sm py-2.5 rounded-lg hover:bg-gray-50">
                Cancel
              </button>
            </div>
          </form>
        </div>
      )}

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
                  <button onClick={() => openEdit(cat)} className="text-gray-400 hover:text-blue-500 transition-colors p-1" title="Edit category">
                    <Pencil size={14} />
                  </button>
                  <button onClick={() => handleDelete(cat.id)} className="text-gray-400 hover:text-red-500 transition-colors p-1" title="Delete category">
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
