/*
 * Category management at "/admin/categories". ADMIN only.
 * Reads GET /api/admin/categories and calls the create, edit, delete and restore admin
 * endpoints. These are the categories sellers pick from when listing and shoppers filter by
 * on Search, so a change here shows up on both sides at once.
 * Delete is a soft delete: the row is flagged rather than removed, which keeps the category
 * attached to auctions that already use it. Deactivated categories are listed separately and
 * can be restored, and the server refuses a delete that would strand live listings.
 */
import { useState, useEffect } from 'react';
import { Plus, Trash2, RotateCcw, Pencil, Tag, AlertCircle } from 'lucide-react';
import { getAdminCategories, createCategory, editCategory, deleteCategory, restoreCategory } from '../../api/admin';
import { apiErrorMessage } from '../../utils/apiError';
import Modal from '../../components/Modal';

// Backend Category fields: id, name, description, displayOrder, slug, deleted, createdAt, auctionCount

export default function AdminCategories() {
  const [categories, setCategories] = useState([]);
  const [newName, setNewName] = useState('');
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState(null); // category being edited
  const [editForm, setEditForm] = useState({ name: '', description: '', displayOrder: '' });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

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

  // The category is only flagged deleted locally, matching what the server does. Anything
  // that refuses to delete, such as a category still holding active auctions, comes back as
  // an error message rather than silently doing nothing.
  const handleDelete = async (id) => {
    if (!window.confirm('Delete this category?')) return;
    setError('');
    try {
      await deleteCategory(id);
      setCategories(prev => prev.map(c => c.id === id ? { ...c, deleted: true } : c));
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not delete that category.'));
    }
  };

  const openEdit = (cat) => {
    setEditing(cat);
    setEditForm({
      name: cat.name ?? '',
      description: cat.description ?? '',
      displayOrder: cat.displayOrder ?? '',
    });
  };

  // An empty display order means "leave it alone", so it is sent as null and the local row
  // keeps whatever value it already had rather than being reset to zero.
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
      alert(apiErrorMessage(err, 'Failed to update category.'));
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

  // One response holds both, split here so the deactivated ones can be shown in their own
  // table with a restore button instead of disappearing from the admin's view.
  const visible  = categories.filter(c => !c.deleted);
  const deleted  = categories.filter(c => c.deleted);

  return (
    <div className="p-8">
      <h1 className="page-title">Categories</h1>
      <p className="page-subtitle mb-6">Manage auction categories</p>

      {error && (
        <div className="alert-error mb-5">
          <AlertCircle size={16} className="mt-0.5 shrink-0" />
          <span>{error}</span>
        </div>
      )}

      {editing && (
        <Modal title="Edit Category" icon={Tag} size="md" onClose={() => setEditing(null)}>
          <form onSubmit={handleEditSave} className="p-6 space-y-4">
            <div>
              <label className="block text-xs text-ink-500 mb-1">Name</label>
              <input
                value={editForm.name}
                onChange={e => setEditForm(f => ({ ...f, name: e.target.value }))}
                required
                className="input-field"
              />
            </div>
            <div>
              <label className="block text-xs text-ink-500 mb-1">Description</label>
              <textarea
                value={editForm.description}
                onChange={e => setEditForm(f => ({ ...f, description: e.target.value }))}
                rows={3}
                className="textarea-field"
              />
            </div>
            <div>
              <label className="block text-xs text-ink-500 mb-1">Display order</label>
              <input
                type="number"
                value={editForm.displayOrder}
                onChange={e => setEditForm(f => ({ ...f, displayOrder: e.target.value }))}
                className="input-field w-32"
              />
            </div>
            <div className="flex gap-3 pt-1">
              <button type="submit" disabled={saving}
                className="btn-primary flex-1">
                {saving ? 'Saving…' : 'Save Changes'}
              </button>
              <button type="button" onClick={() => setEditing(null)}
                className="btn-secondary flex-1">
                Cancel
              </button>
            </div>
          </form>
        </Modal>
      )}

      <form onSubmit={handleAdd} className="flex gap-3 mb-6">
        <input
          value={newName}
          onChange={e => setNewName(e.target.value)}
          placeholder="New category name"
          className="border border-ink-200 rounded-lg px-4 py-2.5 text-sm flex-1 max-w-xs focus:outline-none focus:ring-2 focus:ring-primary-200"
        />
        <button type="submit" disabled={adding} className="btn-primary">
          <Plus size={16} /> Add Category
        </button>
      </form>

      <div className="card overflow-hidden">
        <table className="w-full text-sm">
          <thead className="text-xs text-ink-500 uppercase tracking-wider bg-ink-50 border-b border-ink-200">
            <tr>
              <th className="px-4 py-3 text-left font-bold whitespace-nowrap">Category</th>
              <th className="px-4 py-3 text-left font-bold whitespace-nowrap">Listings</th>
              <th className="px-4 py-3 text-left font-bold whitespace-nowrap">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-ink-100">
            {visible.map(cat => (
              <tr key={cat.id} className="hover:bg-ink-50 transition-colors">
                <td className="px-4 py-3 font-medium text-ink-900">{cat.name}</td>
                <td className="px-4 py-3 text-ink-500">{cat.auctionCount ?? 0} listings</td>
                <td className="px-4 py-3">
                  <button onClick={() => openEdit(cat)} className="text-ink-400 hover:text-primary-500 transition-colors p-1" title="Edit category">
                    <Pencil size={14} />
                  </button>
                  <button onClick={() => handleDelete(cat.id)} className="text-ink-400 hover:text-red-500 transition-colors p-1" title="Delete category">
                    <Trash2 size={14} />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {visible.length === 0 && (
          <div className="text-center py-8 text-ink-400">No categories yet.</div>
        )}
      </div>

      {deleted.length > 0 && (
        <div className="mt-8">
          <h2 className="text-base font-semibold text-ink-500 mb-3">Deactivated Categories</h2>
          <div className="card overflow-hidden">
            <table className="w-full text-sm">
              <thead className="text-xs text-ink-500 uppercase tracking-wider bg-ink-50 border-b border-ink-200">
                <tr>
                  <th className="px-4 py-3 text-left font-bold whitespace-nowrap">Category</th>
                  <th className="px-4 py-3 text-left font-bold whitespace-nowrap">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-ink-100">
                {deleted.map(cat => (
                  <tr key={cat.id} className="hover:bg-ink-50 transition-colors">
                    <td className="px-4 py-3 text-ink-400 line-through">{cat.name}</td>
                    <td className="px-4 py-3">
                      <button
                        onClick={() => handleRestore(cat.id)}
                        className="flex items-center gap-1 text-xs text-primary-500 hover:text-primary-700 font-medium"
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
