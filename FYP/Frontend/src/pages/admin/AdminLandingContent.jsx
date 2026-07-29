import { useState, useEffect, useMemo } from 'react';
import { AlertCircle, RotateCcw, Check, Save, Type } from 'lucide-react';
import {
  getAdminLandingContent, saveLandingContent,
  resetLandingContentField, resetLandingContentGroup,
} from '../../api/admin';
import { apiErrorMessage } from '../../utils/apiError';

// Backend LandingContentItem fields: key, group, label, value, defaultValue,
// multiline, displayOrder, updatedAt, updatedBy, default.
// The field list, its grouping and its defaults all come from the landing_content
// table, so adding copy is a migration change only — nothing here is hardcoded.

export default function AdminLandingContent() {
  const [items, setItems] = useState([]);
  const [values, setValues] = useState({});
  const [loading, setLoading] = useState(true);
  const [savingGroup, setSavingGroup] = useState('');
  const [savedGroup, setSavedGroup] = useState('');
  const [error, setError] = useState('');

  const load = () => getAdminLandingContent()
    .then(r => {
      const list = r.data ?? [];
      setItems(list);
      setValues(Object.fromEntries(list.map(i => [i.key, i.value ?? ''])));
    })
    .catch(err => setError(apiErrorMessage(err, 'Could not load landing page content.')))
    .finally(() => setLoading(false));

  useEffect(() => { load(); }, []);

  // Server order is display_order, so groups come out in the page's own reading order.
  const groups = useMemo(() => {
    const byGroup = new Map();
    items.forEach(item => {
      if (!byGroup.has(item.group)) byGroup.set(item.group, []);
      byGroup.get(item.group).push(item);
    });
    return [...byGroup.entries()];
  }, [items]);

  const changedIn = (fields) => fields.filter(f => (values[f.key] ?? '') !== (f.value ?? ''));

  const handleSave = async (group, fields) => {
    const changed = changedIn(fields);
    if (changed.length === 0) return;
    setError('');
    setSavedGroup('');
    setSavingGroup(group);
    try {
      await saveLandingContent(Object.fromEntries(changed.map(f => [f.key, values[f.key]])));
      const saved = new Map(changed.map(f => [f.key, values[f.key]]));
      setItems(prev => prev.map(i => saved.has(i.key)
        ? { ...i, value: saved.get(i.key), default: saved.get(i.key) === i.defaultValue }
        : i));
      setSavedGroup(group);
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not save that content.'));
    } finally {
      setSavingGroup('');
    }
  };

  const handleResetField = async (item) => {
    setError('');
    try {
      await resetLandingContentField(item.key);
      setItems(prev => prev.map(i => i.key === item.key
        ? { ...i, value: i.defaultValue, default: true }
        : i));
      setValues(v => ({ ...v, [item.key]: item.defaultValue }));
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not restore the default text.'));
    }
  };

  const handleResetGroup = async (group) => {
    if (!window.confirm(`Restore every "${group}" field to its original wording?`)) return;
    setError('');
    try {
      await resetLandingContentGroup(group);
      await load();
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not restore the default text.'));
    }
  };

  return (
    <div className="p-8">
      <h1 className="page-title">Landing Page Content</h1>
      <p className="page-subtitle mb-6">
        Edit the wording shown to visitors on the home page. Categories, metrics, listings
        and testimonials are generated from live data and are not editable here.
      </p>

      {error && (
        <div className="alert-error mb-5">
          <AlertCircle size={16} className="mt-0.5 shrink-0" />
          <span>{error}</span>
        </div>
      )}

      {loading ? (
        <div className="card p-8 text-center text-ink-400">Loading content…</div>
      ) : groups.length === 0 ? (
        <div className="card p-8 text-center text-ink-400">
          No editable content found. Run the database migrations and reload.
        </div>
      ) : (
        <div className="space-y-6">
          {groups.map(([group, fields]) => {
            const changed = changedIn(fields);
            const busy = savingGroup === group;
            return (
              <section key={group} className="card overflow-hidden">
                <header className="flex items-center justify-between gap-4 px-5 py-3.5 bg-ink-50 border-b border-ink-200">
                  <h2 className="font-semibold text-ink-900 flex items-center gap-2">
                    <Type size={16} className="text-primary-600" />
                    {group}
                  </h2>
                  <div className="flex items-center gap-3">
                    {changed.length > 0 && (
                      <span className="badge-warning">{changed.length} unsaved</span>
                    )}
                    {savedGroup === group && changed.length === 0 && (
                      <span className="badge-success"><Check size={12} /> Saved</span>
                    )}
                    <button
                      onClick={() => handleResetGroup(group)}
                      className="flex items-center gap-1 text-xs text-ink-400 hover:text-primary-600 font-medium transition-colors"
                      title={`Restore all ${group} defaults`}
                    >
                      <RotateCcw size={13} /> Reset group
                    </button>
                  </div>
                </header>

                <div className="p-5 space-y-4">
                  {fields.map(item => (
                    <div key={item.key}>
                      <div className="flex items-baseline justify-between gap-3 mb-1">
                        <label htmlFor={item.key} className="block text-xs text-ink-500">
                          {item.label}
                        </label>
                        {(values[item.key] ?? '') !== item.defaultValue && (
                          <button
                            type="button"
                            onClick={() => handleResetField(item)}
                            className="flex items-center gap-1 text-[11px] text-ink-400 hover:text-primary-600 font-medium transition-colors"
                            title="Restore the original wording"
                          >
                            <RotateCcw size={11} /> Reset
                          </button>
                        )}
                      </div>
                      {item.multiline ? (
                        <textarea
                          id={item.key}
                          value={values[item.key] ?? ''}
                          onChange={e => setValues(v => ({ ...v, [item.key]: e.target.value }))}
                          rows={3}
                          maxLength={2000}
                          className="textarea-field"
                        />
                      ) : (
                        <input
                          id={item.key}
                          value={values[item.key] ?? ''}
                          onChange={e => setValues(v => ({ ...v, [item.key]: e.target.value }))}
                          maxLength={2000}
                          className="input-field"
                        />
                      )}
                      <p className="text-[11px] text-ink-400 mt-1 font-mono">{item.key}</p>
                    </div>
                  ))}
                </div>

                <div className="flex gap-3 px-5 py-3.5 border-t border-ink-100 bg-white">
                  <button
                    onClick={() => handleSave(group, fields)}
                    disabled={busy || changed.length === 0}
                    className="btn-primary disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    <Save size={15} /> {busy ? 'Saving…' : 'Save Changes'}
                  </button>
                  {changed.length > 0 && (
                    <button
                      onClick={() => setValues(v => {
                        const next = { ...v };
                        fields.forEach(f => { next[f.key] = f.value ?? ''; });
                        return next;
                      })}
                      className="btn-secondary"
                    >
                      Discard
                    </button>
                  )}
                </div>
              </section>
            );
          })}
        </div>
      )}
    </div>
  );
}
