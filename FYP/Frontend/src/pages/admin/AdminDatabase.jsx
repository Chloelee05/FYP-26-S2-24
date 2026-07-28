import { useState, useEffect } from 'react';
import { getDatabaseStatus, downloadDatabaseBackup, restoreDatabaseBackup } from '../../api/admin';
import { apiErrorMessage } from '../../utils/apiError';

function triggerBlobDownload(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

export default function AdminDatabase() {
  const [status, setStatus] = useState(null);
  const [msg, setMsg] = useState('');
  const [busy, setBusy] = useState(false);

  const loadStatus = () => {
    getDatabaseStatus()
      .then(r => setStatus(r.data))
      .catch(err => setMsg(apiErrorMessage(err, 'Could not load database status.')));
  };

  useEffect(() => { loadStatus(); }, []);

  const handleBackup = async () => {
    setBusy(true);
    setMsg('');
    try {
      const r = await downloadDatabaseBackup();
      triggerBlobDownload(r.data, 'auctionhub-backup.sql');
      setMsg('Backup downloaded.');
    } catch (err) {
      setMsg(apiErrorMessage(err, 'Could not download backup.'));
    } finally {
      setBusy(false);
    }
  };

  const handleRestore = async (e) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    if (!window.confirm('Restore from this backup file? Only INSERT statements will be applied.')) return;
    setBusy(true);
    setMsg('');
    try {
      const sqlText = await file.text();
      const r = await restoreDatabaseBackup(sqlText);
      setMsg(r.data?.message ?? 'Restore completed.');
      loadStatus();
    } catch (err) {
      setMsg(apiErrorMessage(err, 'Could not restore backup.'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="p-8">
      <h1 className="page-title">Database Management</h1>
      <p className="page-subtitle mb-6">Backup, restore, and inspect database status</p>

      {msg && <div className="text-sm text-primary-600 mb-4">{msg}</div>}

      <div className="grid md:grid-cols-2 gap-6 mb-8">
        <div className="card p-5">
          <h2 className="section-title text-base mb-3">Backup</h2>
          <p className="text-sm text-ink-500 mb-4">
            Export all tables as a SQL file. Use this before major changes or for archival.
          </p>
          <button
            type="button"
            onClick={handleBackup}
            disabled={busy}
            className="btn-primary"
          >
            Download backup (.sql)
          </button>
        </div>

        <div className="card p-5">
          <h2 className="section-title text-base mb-3">Restore</h2>
          <p className="text-sm text-ink-500 mb-4">
            Upload a previously exported backup file. Only INSERT statements are applied.
          </p>
          <label className="btn-dark inline-flex cursor-pointer">
            Upload backup file
            <input type="file" accept=".sql,text/plain" onChange={handleRestore} disabled={busy} className="hidden" />
          </label>
        </div>
      </div>

      {status ? (
        <div className="card p-5">
          <h2 className="section-title text-base mb-3">Database Status</h2>
          <div className="text-sm text-ink-600 mb-4 space-y-1">
            <p><span className="text-ink-400">Database:</span> {status.database}</p>
            <p><span className="text-ink-400">Tables:</span> {status.tableCount}</p>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-ink-400 border-b border-ink-100">
                  <th className="py-2 pr-4">Table</th>
                  <th className="py-2">Rows</th>
                </tr>
              </thead>
              <tbody>
                {(status.tables ?? []).map(t => (
                  <tr key={t.name} className="border-b border-ink-50">
                    <td className="py-2 pr-4 font-mono text-ink-700">{t.name}</td>
                    <td className="py-2 text-ink-600">{t.rows?.toLocaleString?.() ?? t.rows}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      ) : (
        <div className="text-center py-12 text-ink-400">Loading database status…</div>
      )}
    </div>
  );
}
