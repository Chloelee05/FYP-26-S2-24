/*
 * Landing page copy editor at "/admin/landing-content". ADMIN only.
 * This is the admin end of the dynamic Home page: every heading, subheading and button label
 * on "/" is a row in the landing_content table, and editing it here changes what the next
 * visitor reads. Nothing on the landing page is hardcoded except the fallback strings Home.jsx
 * uses if the content call comes back empty.
 * Reads GET admin landing content, and calls save, reset one field and reset a whole group.
 * Every field keeps its original wording in defaultValue, so a reset restores the shipped copy
 * rather than blanking the field.
 * Live data on the landing page (categories, metrics, listings, testimonials) is generated and
 * is not editable here.
 */
import { useState, useEffect, useMemo } from 'react';
import {
  AlertCircle, RotateCcw, Check, Save, Type, Search, X, ChevronDown, ChevronsDownUp,
} from 'lucide-react';
import {
  getAdminLandingContent, saveLandingContent,
  resetLandingContentField, resetLandingContentGroup,
} from '../../api/admin';
import { apiErrorMessage } from '../../utils/apiError';

// Backend LandingContentItem fields: key, group, label, value, defaultValue,
// multiline, displayOrder, updatedAt, updatedBy, default.
// The field list, its grouping and its defaults all come from the landing_content
// table, so adding a new piece of copy is a migration change only. Nothing here is hardcoded.

// Scope marker for the sticky save bar, which saves across every group at once. It is not a
// real group name, so it can never collide with one coming from the database.
const ALL_GROUPS = '__all__';

export default function AdminLandingContent() {
  // items holds what the server currently has, values holds what is in the boxes. Keeping the
  // two apart is what makes "unsaved changes", Discard and the per field Reset possible: a
  // field counts as changed whenever its value differs from the item it came from.
  const [items, setItems] = useState([]);
  const [values, setValues] = useState({});
  const [loading, setLoading] = useState(true);
  const [savingGroup, setSavingGroup] = useState('');
  const [savedGroup, setSavedGroup] = useState('');
  const [error, setError] = useState('');
  const [query, setQuery] = useState('');
  const [collapsed, setCollapsed] = useState(() => new Set());

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

  const needle = query.trim().toLowerCase();
  const matches = (item) => !needle
    || item.label.toLowerCase().includes(needle)
    || item.key.toLowerCase().includes(needle)
    || (values[item.key] ?? '').toLowerCase().includes(needle);

  const visibleGroups = groups
    .map(([group, fields]) => [group, fields.filter(matches)])
    .filter(([, fields]) => fields.length > 0);

  const changedIn = (fields) => fields.filter(f => (values[f.key] ?? '') !== (f.value ?? ''));
  const allChanged = changedIn(items);

  // While filtering, every surviving group opens so a match is never hidden behind a collapsed header.
  const isOpen = (group) => Boolean(needle) || !collapsed.has(group);

  const toggleGroup = (group) => setCollapsed(prev => {
    const next = new Set(prev);
    if (next.has(group)) next.delete(group); else next.add(group);
    return next;
  });

  // Saves one group or the whole page depending on scope. Only the fields that actually
  // differ are sent, and items is patched locally on success so the group stops showing as
  // unsaved without refetching the entire list.
  const saveFields = async (scope, fields) => {
    const changed = changedIn(fields);
    if (changed.length === 0) return;
    setError('');
    setSavedGroup('');
    setSavingGroup(scope);
    try {
      await saveLandingContent(Object.fromEntries(changed.map(f => [f.key, values[f.key]])));
      const saved = new Map(changed.map(f => [f.key, values[f.key]]));
      setItems(prev => prev.map(i => saved.has(i.key)
        ? { ...i, value: saved.get(i.key), default: saved.get(i.key) === i.defaultValue }
        : i));
      setSavedGroup(scope);
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not save that content.'));
    } finally {
      setSavingGroup('');
    }
  };

  // Throws away local edits by copying the saved values back into the boxes. Nothing is sent
  // to the server, since nothing was saved.
  const discardFields = (fields) => setValues(v => {
    const next = { ...v };
    fields.forEach(f => { next[f.key] = f.value ?? ''; });
    return next;
  });

  // Reset is a save, not a discard: it writes the original wording back to the database, so
  // both the stored item and the box on screen move to defaultValue.
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

  // Resetting a whole group can undo a lot of writing at once, so it is confirmed first and
  // then the list is reloaded rather than patched field by field.
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
    <div className="p-8 pb-28">
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

      {!loading && groups.length > 0 && (
        <div className="flex flex-wrap items-center gap-3 mb-5">
          <div className="relative flex-1 min-w-[16rem] max-w-sm">
            <Search size={15} className="absolute left-3 top-1/2 -translate-y-1/2 text-ink-400" />
            <input
              value={query}
              onChange={e => setQuery(e.target.value)}
              placeholder="Filter by label, key or text…"
              className="input-field pl-9 pr-9"
            />
            {query && (
              <button
                type="button"
                onClick={() => setQuery('')}
                title="Clear filter"
                className="absolute right-3 top-1/2 -translate-y-1/2 text-ink-400 hover:text-ink-700 transition-colors"
              >
                <X size={14} />
              </button>
            )}
          </div>
          <span className="text-xs text-ink-400">
            {items.length} field{items.length === 1 ? '' : 's'} in {groups.length} group{groups.length === 1 ? '' : 's'}
          </span>
          <button
            type="button"
            onClick={() => setCollapsed(prev => prev.size > 0 ? new Set() : new Set(groups.map(([g]) => g)))}
            className="btn-secondary btn-sm ml-auto"
          >
            <ChevronsDownUp size={14} /> {collapsed.size > 0 ? 'Expand all' : 'Collapse all'}
          </button>
        </div>
      )}

      {loading ? (
        <div className="card p-8 text-center text-ink-400">Loading content…</div>
      ) : groups.length === 0 ? (
        <div className="card p-8 text-center text-ink-400">
          No editable content found. Run the database migrations and reload.
        </div>
      ) : visibleGroups.length === 0 ? (
        <div className="card p-8 text-center text-ink-400">
          Nothing matches “{query}”.
        </div>
      ) : (
        <div className="space-y-5">
          {visibleGroups.map(([group, fields]) => {
            const changed = changedIn(fields);
            const busy = savingGroup === group;
            const open = isOpen(group);
            return (
              <section key={group} className="card overflow-hidden">
                <header className="flex items-center justify-between gap-4 px-5 py-3.5 bg-ink-50 border-b border-ink-200">
                  <button
                    type="button"
                    onClick={() => toggleGroup(group)}
                    aria-expanded={open}
                    className="flex items-center gap-2 font-semibold text-ink-900 hover:text-primary-600 transition-colors"
                  >
                    <Type size={16} className="text-primary-600" />
                    {group}
                    <span className="text-xs font-normal text-ink-400">
                      {fields.length} field{fields.length === 1 ? '' : 's'}
                    </span>
                    <ChevronDown
                      size={15}
                      className={`text-ink-400 transition-transform ${open ? '' : '-rotate-90'}`}
                    />
                  </button>
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

                {open && (
                  <>
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
                        onClick={() => saveFields(group, fields)}
                        disabled={busy || changed.length === 0}
                        className="btn-primary disabled:opacity-50 disabled:cursor-not-allowed"
                      >
                        <Save size={15} /> {busy ? 'Saving…' : 'Save Changes'}
                      </button>
                      {changed.length > 0 && (
                        <button onClick={() => discardFields(fields)} className="btn-secondary">
                          Discard
                        </button>
                      )}
                    </div>
                  </>
                )}
              </section>
            );
          })}
        </div>
      )}

      {/* Edits can span collapsed or filtered-out groups, so saving stays reachable. */}
      {allChanged.length > 0 && (
        <div className="fixed bottom-0 left-0 right-0 z-30 border-t border-ink-200 bg-white/95 backdrop-blur-sm shadow-lift">
          <div className="flex items-center justify-end gap-3 px-8 py-3.5">
            <span className="text-sm text-ink-500 mr-auto">
              {allChanged.length} unsaved change{allChanged.length === 1 ? '' : 's'} across the page
            </span>
            <button onClick={() => discardFields(items)} className="btn-secondary">
              Discard all
            </button>
            <button
              onClick={() => saveFields(ALL_GROUPS, items)}
              disabled={savingGroup === ALL_GROUPS}
              className="btn-primary"
            >
              <Save size={15} /> {savingGroup === ALL_GROUPS ? 'Saving…' : 'Save all changes'}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
