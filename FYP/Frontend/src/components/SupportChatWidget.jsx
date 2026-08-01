import { useState, useEffect, useRef, useCallback } from 'react';
import { useLocation } from 'react-router-dom';
import { MessageCircle, X } from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import {
  getSupportThreads, createSupportThread, getSupportMessages, sendSupportMessage, markSupportThreadRead,
} from '../api/support';
import { apiErrorMessage } from '../utils/apiError';
import usePolling from '../hooks/usePolling';
import ChatMessage from './ChatMessage';
import SupportChatInput from './SupportChatInput';

/** Floating buyer/seller -> admin support chat. Order chat with sellers lives at /messages. */
export default function SupportChatWidget() {
  const { pathname } = useLocation();
  const { user } = useAuth();
  const [open, setOpen] = useState(false);
  const [threads, setThreads] = useState([]);
  const [selectedId, setSelectedId] = useState(null);
  const [messages, setMessages] = useState([]);
  const [newSubject, setNewSubject] = useState('');
  const [newBody, setNewBody] = useState('');
  const [mode, setMode] = useState('chat');
  const [status, setStatus] = useState('');
  const bottomRef = useRef(null);

  const visible = user && user.role !== 'ADMIN' && !pathname.endsWith('/support');

  const loadMessages = useCallback(async (threadId, config) => {
    if (!threadId) return;
    try {
      const r = await getSupportMessages(threadId, config);
      setMessages(r.data ?? []);
    } catch (err) {
      setStatus(apiErrorMessage(err, 'Could not load messages.'));
    }
  }, []);

  const loadThreads = useCallback(async (config) => {
    try {
      const r = await getSupportThreads(config);
      const list = r.data ?? [];
      setThreads(list);
      setSelectedId(prev => {
        const id = prev ?? list[0]?.id;
        return id != null ? Number(id) : null;
      });
    } catch (err) {
      setStatus(apiErrorMessage(err, 'Could not load support threads.'));
    }
  }, []);

  const pollMessages = useCallback(
    (config) => loadMessages(selectedId, config),
    [loadMessages, selectedId],
  );

  usePolling(loadThreads, 12000, visible && open);
  usePolling(pollMessages, 5000, Boolean(visible && open && selectedId));

  // Opening a thread clears its unread flag; the poll above keeps the list fresh.
  useEffect(() => {
    if (!visible || !open || !selectedId) return;
    markSupportThreadRead(selectedId).catch(() => {});
  }, [visible, open, selectedId]);

  useEffect(() => {
    if (open) bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, open]);

  if (!visible) return null;

  const unreadCount = threads.filter(t => t.unread).length;
  const selected = threads.find(t => Number(t.id) === Number(selectedId));

  const handleCreate = async (e) => {
    e.preventDefault();
    if (!newBody.trim()) return;
    setStatus('');
    try {
      const r = await createSupportThread(newSubject, newBody.trim());
      const threadId = Number(r.data.threadId);
      setNewSubject('');
      setNewBody('');
      setMode('chat');
      setSelectedId(threadId);
      await loadThreads();
      await loadMessages(threadId);
      setStatus('Message sent. An admin will reply soon.');
    } catch (err) {
      setStatus(apiErrorMessage(err, 'Could not send message.'));
    }
  };

  const handleSend = async ({ body, attachmentUrl }) => {
    if (!selectedId || selected?.status === 'CLOSED') return;
    setStatus('');
    try {
      await sendSupportMessage(selectedId, body, attachmentUrl);
      await loadMessages(selectedId);
    } catch (err) {
      setStatus(apiErrorMessage(err, 'Could not send message.'));
    }
  };

  return (
    <>
      {!open && (
        <div className="fixed bottom-6 right-6 z-50">
          <button
            type="button"
            onClick={() => { setStatus(''); setOpen(true); }}
            className="relative flex items-center gap-2 bg-primary-600 hover:bg-primary-700 text-white px-4 py-3 rounded-full shadow-pop hover:-translate-y-0.5 transition-all"
            title="Contact Admin"
          >
            <MessageCircle size={20} />
            <span className="text-sm font-semibold hidden sm:inline">Contact Admin</span>
            {unreadCount > 0 && (
              <span className="absolute -top-1 -right-1 min-w-[18px] h-[18px] px-1 rounded-full bg-red-500 text-white text-[10px] font-bold flex items-center justify-center ring-2 ring-white">
                {unreadCount > 9 ? '9+' : unreadCount}
              </span>
            )}
          </button>
        </div>
      )}

      {open && (
        <div className="fixed bottom-6 right-6 z-50 w-[min(100vw-2rem,380px)] h-[min(70vh,520px)] bg-ink-50 rounded-2xl shadow-pop border border-ink-200 flex flex-col overflow-hidden animate-scale-in origin-bottom-right">
          <div className="flex items-center justify-between px-4 py-3 bg-ink-900 text-white shrink-0">
            <div className="flex items-center gap-2.5">
              <span className="grid place-items-center w-8 h-8 rounded-lg bg-white/10">
                <MessageCircle size={16} />
              </span>
              <div className="leading-tight">
                <p className="font-semibold text-sm">Admin Support</p>
                <p className="text-xs text-ink-400">Platform help · we reply within 24h</p>
              </div>
            </div>
            <button type="button" onClick={() => setOpen(false)} className="p-1.5 hover:bg-white/10 rounded-lg transition-colors">
              <X size={18} />
            </button>
          </div>

          {threads.length > 0 && mode === 'chat' && (
            <div className="flex gap-1 px-2 py-2 bg-white border-b border-ink-100 overflow-x-auto shrink-0">
              {threads.map(t => (
                <button
                  key={t.id}
                  type="button"
                  onClick={() => {
                    setSelectedId(Number(t.id));
                    setStatus('');
                    setThreads(prev => prev.map(x => Number(x.id) === Number(t.id) ? { ...x, unread: false } : x));
                    markSupportThreadRead(t.id).catch(() => {});
                  }}
                  className={`shrink-0 px-3 py-1.5 rounded-full text-xs font-semibold transition-colors ${
                    Number(selectedId) === Number(t.id) ? 'bg-primary-600 text-white' : 'bg-ink-100 text-ink-600 hover:bg-ink-200'
                  } ${t.unread ? 'ring-2 ring-primary-300' : ''}`}
                >
                  {t.subject?.slice(0, 20) || `Thread #${t.id}`}
                </button>
              ))}
              <button
                type="button"
                onClick={() => setMode('new')}
                className="shrink-0 px-3 py-1.5 rounded-full text-xs font-semibold border border-dashed border-ink-300 text-ink-500 hover:border-primary-300 hover:text-primary-600 transition-colors"
              >
                + New
              </button>
            </div>
          )}

          {status && (
            <div className="px-4 py-2 text-xs bg-primary-50 border-b border-primary-100 shrink-0 text-primary-600">
              {status}
            </div>
          )}

          {mode === 'new' || threads.length === 0 ? (
            <form onSubmit={handleCreate} className="flex-1 flex flex-col p-4 gap-3 overflow-y-auto bg-white">
              <p className="text-sm text-ink-600">Send a message to the admin team.</p>
              <input
                value={newSubject}
                onChange={e => setNewSubject(e.target.value)}
                placeholder="Subject (optional)"
                className="input-field"
              />
              <textarea
                value={newBody}
                onChange={e => setNewBody(e.target.value)}
                placeholder="How can we help?"
                rows={5}
                required
                className="textarea-field flex-1"
              />
              <div className="flex gap-2">
                <button type="submit" className="btn-primary flex-1">
                  Send
                </button>
                {threads.length > 0 && (
                  <button type="button" onClick={() => setMode('chat')} className="btn-ghost">
                    Back
                  </button>
                )}
              </div>
            </form>
          ) : (
            <>
              <div className="flex-1 overflow-y-auto p-3 space-y-2 bg-ink-100">
                {messages.length === 0 && !status && (
                  <p className="text-sm text-ink-400 text-center py-8">No messages yet.</p>
                )}
                {messages.map(m => (
                  <ChatMessage key={m.id} message={m} currentUserId={user.id} peerLabel="Admin" />
                ))}
                <div ref={bottomRef} />
              </div>

              {selected?.status === 'OPEN' ? (
                <SupportChatInput onSend={handleSend} placeholder="Type a message…" />
              ) : (
                <div className="p-3 border-t bg-white text-center text-xs text-ink-400 shrink-0">
                  This conversation is closed.
                </div>
              )}
            </>
          )}
        </div>
      )}
    </>
  );
}
