/*
 * Admin side of support chat, at "/admin/chat". ADMIN only. The same conversations appear to
 * the member at /support, so this is one thread seen from two ends.
 * Reads the support thread list and the messages of the selected thread, POSTs a reply,
 * marks a thread read when it is opened, and can close a thread.
 * Both lists poll: threads every 10 seconds, the open conversation every 5, which is the
 * pattern used on the other messaging pages since there is no websocket in this project.
 * Closing a thread makes it read only on both sides; the composer is replaced by a notice
 * and the server rejects any further message to it.
 */
import { useState, useEffect, useRef, useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { getSupportThreads, getSupportMessages, sendSupportMessage, closeSupportThread, markSupportThreadRead } from '../../api/support';
import { apiErrorMessage } from '../../utils/apiError';
import usePolling from '../../hooks/usePolling';
import ChatMessage from '../../components/ChatMessage';
import SupportChatInput from '../../components/SupportChatInput';

// An image only message has no text to preview, so the list would otherwise show a blank
// line. The single space check catches messages the composer sent as whitespace.
function threadPreview(t) {
  const body = (t.lastBody || '').trim();
  if (body && body !== ' ') return body;
  if (t.lastAttachmentUrl) return 'Image attached';
  return 'New message';
}

function formatThreadTime(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  const now = new Date();
  const sameDay = d.toDateString() === now.toDateString();
  return sameDay
    ? d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    : d.toLocaleDateString([], { month: 'short', day: 'numeric' });
}

export default function AdminChat() {
  const { user } = useAuth();
  const [searchParams] = useSearchParams();
  const [threads, setThreads] = useState([]);
  // ?thread= deep-links a conversation (e.g. from Reports); clicking the list takes over.
  // pickedId is null until the admin clicks, so the query parameter wins on first render and
  // is then ignored, which avoids having to rewrite the URL on every selection.
  const [pickedId, setPickedId] = useState(null);
  const selectedId = pickedId ?? (Number(searchParams.get('thread')) || null);
  const [messages, setMessages] = useState([]);
  const [msg, setMsg] = useState('');
  const bottomRef = useRef(null);

  const loadThreads = useCallback(async (config) => {
    try {
      const r = await getSupportThreads(config);
      setThreads(r.data ?? []);
    } catch (err) {
      setMsg(apiErrorMessage(err, 'Could not load threads.'));
    }
  }, []);

  const loadMessages = useCallback(async (config) => {
    if (!selectedId) return;
    try {
      const r = await getSupportMessages(selectedId, config);
      setMessages(r.data ?? []);
    } catch (err) {
      setMsg(apiErrorMessage(err, 'Could not load messages.'));
    }
  }, [selectedId]);

  // Thread list every 10 seconds so a new ticket appears without a refresh, open thread every
  // 5 for the reply itself. The message poll is switched off when nothing is selected.
  usePolling(loadThreads, 10000);
  usePolling(loadMessages, 5000, Boolean(selectedId));

  // Covers the deep-linked case: arriving with ?thread= never goes through selectThread, so
  // the read receipt has to be sent from here as well.
  useEffect(() => {
    if (!selectedId) return;
    markSupportThreadRead(selectedId).catch(() => {});
  }, [selectedId]);

  // Keeps the newest message in view after each poll or send.
  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages]);

  const selected = threads.find(t => t.id === selectedId);

  // Refetches immediately rather than waiting for the next poll, and reloads the thread list
  // too so the preview line and timestamp on the left move with the reply.
  const handleSend = async ({ body, attachmentUrl }) => {
    if (!selectedId) return;
    try {
      await sendSupportMessage(selectedId, body, attachmentUrl);
      const r = await getSupportMessages(selectedId);
      setMessages(r.data ?? []);
      loadThreads();
      setMsg('');
    } catch (err) {
      setMsg(apiErrorMessage(err, 'Could not send message.'));
    }
  };

  const handleClose = async () => {
    if (!selectedId || !window.confirm('Close this support thread?')) return;
    try {
      await closeSupportThread(selectedId);
      setMsg('Thread closed.');
      loadThreads();
    } catch {
      setMsg('Could not close thread.');
    }
  };

  // The unread flag is cleared locally at the same time as the read receipt is sent, so the
  // highlight disappears on click instead of after the next poll.
  const selectThread = (t) => {
    setPickedId(t.id);
    setMsg('');
    setThreads(prev => prev.map(x => x.id === t.id ? { ...x, unread: false } : x));
    markSupportThreadRead(t.id).catch(() => {});
  };

  return (
    <div className="p-6 flex flex-col h-[calc(100vh-3.5rem)] max-h-[calc(100vh-3.5rem)] overflow-hidden">
      <div className="shrink-0">
        <h1 className="page-title">Support Chat</h1>
        <p className="text-ink-400 text-sm mb-4">Direct messages with users</p>
        {msg && <div className="text-sm text-primary-600 mb-2">{msg}</div>}
      </div>

      <div className="flex flex-1 gap-4 min-h-0 overflow-hidden">
        <div className="w-80 card overflow-hidden flex flex-col min-h-0 shrink-0">
          <div className="px-4 py-3 border-b border-ink-100 text-xs font-semibold text-ink-500 uppercase">Threads</div>
          <div className="flex-1 overflow-y-auto min-h-0">
            {threads.length === 0 && <p className="p-4 text-sm text-ink-400">No conversations yet.</p>}
            {threads.map(t => (
              <button
                key={t.id}
                type="button"
                onClick={() => selectThread(t)}
                className={`w-full text-left px-4 py-3 border-b border-ink-50 hover:bg-ink-50 transition-colors ${
                  selectedId === t.id ? 'bg-primary-50' : t.unread ? 'bg-sky-50 border-l-4 border-l-sky-500' : ''
                }`}
              >
                <div className="flex items-start justify-between gap-2 mb-1">
                  <div className="flex items-center gap-2 min-w-0">
                    {t.unread && <span className="shrink-0 w-2.5 h-2.5 rounded-full bg-sky-500" aria-hidden />}
                    <p className={`text-sm text-ink-900 truncate ${t.unread ? 'font-bold' : 'font-medium'}`}>
                      {t.username}
                    </p>
                  </div>
                  {t.unread && (
                    <span className="shrink-0 text-[10px] font-bold uppercase tracking-wide text-white bg-sky-500 px-1.5 py-0.5 rounded">
                      New
                    </span>
                  )}
                </div>
                <p className={`text-xs truncate ${t.unread ? 'font-semibold text-ink-800' : 'text-ink-600'}`}>
                  {threadPreview(t)}
                </p>
                <div className="flex items-center justify-between mt-1.5">
                  <span className={`text-xs ${t.status === 'OPEN' ? 'text-green-600' : 'text-ink-400'}`}>{t.status}</span>
                  <span className="text-xs text-ink-400">{formatThreadTime(t.lastMessageAt)}</span>
                </div>
              </button>
            ))}
          </div>
        </div>

        <div className="flex-1 card flex flex-col min-h-0 overflow-hidden">
          {!selectedId ? (
            <div className="flex-1 flex items-center justify-center text-ink-400 text-sm">Select a conversation</div>
          ) : (
            <>
              <div className="px-4 py-3 border-b border-ink-100 flex items-center justify-between bg-white shrink-0">
                <div>
                  <p className="font-semibold text-ink-900">{selected?.username}</p>
                  <p className="text-xs text-ink-500">{selected?.subject}</p>
                </div>
                {selected?.status === 'OPEN' && (
                  <button type="button" onClick={handleClose} className="text-xs text-red-500 hover:text-red-600">Close thread</button>
                )}
              </div>
              <div className="flex-1 overflow-y-auto min-h-0 p-4 space-y-2 bg-[#e8ecf1]">
                {messages.map(m => (
                  <ChatMessage
                    key={m.id}
                    message={m}
                    currentUserId={user?.id}
                    peerLabel={selected?.username || 'User'}
                  />
                ))}
                <div ref={bottomRef} />
              </div>
              {selected?.status === 'OPEN' ? (
                <SupportChatInput onSend={handleSend} placeholder="Type a reply…" />
              ) : (
                <div className="p-4 border-t border-ink-100 text-center text-sm text-ink-400 bg-white shrink-0">
                  This thread is closed.
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
