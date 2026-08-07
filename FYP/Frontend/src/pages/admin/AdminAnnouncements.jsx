/*
 * NEW page for the "system-wide announcement" admin story at "/admin/announcements". ADMIN
 * only, guarded the same way every other admin page is: the <ProtectedRoute roles={['ADMIN']}>
 * wrapper on the parent /admin route in App.jsx.
 *
 * "As an Admin, I want to send system-wide announcements or notifications to all users, so
 * that I can send maintenance or policy updates." Deliberately small: a title field, a body
 * field, a send button and a confirmation of how many recipients were reached and whether
 * email is configured on this deployment. No rich text editor, no scheduling, no recipient
 * segmentation, no channel picker and no history view beyond what the existing admin audit
 * log already shows on /admin/dashboard-adjacent tooling — those are explicitly out of scope
 * for this story.
 *
 * The "is email configured" state is read from the server's own emailConfigured field on
 * every send response, never assumed on the client, so this page cannot show the same
 * self-contradiction ("email is not configured" next to a message implying it was sent) that
 * was previously found and fixed on the seller-analytics admin page (AdminAnalytics.jsx).
 */
import { useState } from 'react';
import { Megaphone } from 'lucide-react';
import { sendAnnouncement } from '../../api/admin';
import { apiErrorMessage } from '../../utils/apiError';

const TITLE_MAX = 200;
const BODY_MAX = 2000;

export default function AdminAnnouncements() {
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [sending, setSending] = useState(false);
  const [msg, setMsg] = useState('');
  const [err, setErr] = useState('');
  // Set only from the server's own response, so this can never claim email was sent while
  // the same response says otherwise (or vice versa) — the exact bug class this story was
  // told to avoid reintroducing.
  const [lastResult, setLastResult] = useState(null);

  const canSend = title.trim().length > 0 && body.trim().length > 0
    && title.length <= TITLE_MAX && body.length <= BODY_MAX && !sending;

  const handleSend = async () => {
    if (!canSend) return;
    if (!window.confirm('Send this announcement to every active user? This cannot be recalled.')) {
      return;
    }
    setSending(true);
    setMsg('');
    setErr('');
    try {
      const r = await sendAnnouncement(title.trim(), body.trim());
      setLastResult(r.data);
      setMsg(r.data?.message ?? 'Announcement sent.');
      setTitle('');
      setBody('');
    } catch (e) {
      setErr(apiErrorMessage(e, 'Could not send the announcement.'));
    } finally {
      setSending(false);
    }
  };

  return (
    <div className="p-8 max-w-3xl">
      <h1 className="page-title flex items-center gap-2">
        <Megaphone size={22} className="text-primary-600" /> Announcements
      </h1>
      <p className="page-subtitle mb-6">
        Send a one-off system-wide announcement — a maintenance window or a policy update —
        to every active user. Delivery follows each user's own notification settings, the
        same as any other AuctionHub notification.
      </p>

      {msg && (
        <div className="text-sm text-emerald-700 bg-emerald-50 ring-1 ring-emerald-200 rounded-lg px-3 py-2 mb-4">
          {msg}
        </div>
      )}
      {err && (
        <div className="text-sm text-red-700 bg-red-50 ring-1 ring-red-200 rounded-lg px-3 py-2 mb-4">
          {err}
        </div>
      )}

      <div className="card p-5">
        <div className="mb-4">
          <label className="block text-xs text-ink-500 mb-1" htmlFor="announcement-title">
            Title
          </label>
          <input
            id="announcement-title"
            type="text"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            maxLength={TITLE_MAX}
            placeholder="Scheduled maintenance this weekend"
            className="w-full border border-ink-200 rounded-lg px-3 py-2 text-sm"
          />
          <p className="text-[11px] text-ink-400 mt-1">{title.length}/{TITLE_MAX}</p>
        </div>

        <div className="mb-4">
          <label className="block text-xs text-ink-500 mb-1" htmlFor="announcement-body">
            Message
          </label>
          <textarea
            id="announcement-body"
            value={body}
            onChange={(e) => setBody(e.target.value)}
            maxLength={BODY_MAX}
            rows={6}
            placeholder="AuctionHub will be briefly unavailable on Saturday 2am-3am SGT for scheduled maintenance."
            className="w-full border border-ink-200 rounded-lg px-3 py-2 text-sm"
          />
          <p className="text-[11px] text-ink-400 mt-1">{body.length}/{BODY_MAX}</p>
        </div>

        <button
          type="button"
          onClick={handleSend}
          disabled={!canSend}
          className="btn-primary"
        >
          {sending ? 'Sending…' : 'Send announcement'}
        </button>

        {lastResult && (
          <div className="mt-5 pt-5 border-t border-ink-100 text-sm text-ink-600 space-y-1">
            <p>Reached <span className="font-semibold text-ink-900">{lastResult.recipients}</span> active user(s).</p>
            <p>
              {lastResult.emailConfigured
                ? 'Email is configured on this server, so eligible recipients were also emailed.'
                : 'Email is not configured on this server, so only the in-app notification was delivered.'}
            </p>
          </div>
        )}
      </div>
    </div>
  );
}
