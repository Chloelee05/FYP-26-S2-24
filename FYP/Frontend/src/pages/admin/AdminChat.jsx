import { useState, useEffect, useRef, useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { getSupportThreads, getSupportMessages, sendSupportMessage, closeSupportThread, markSupportThreadRead } from '../../api/support';
import { apiErrorMessage } from '../../utils/apiError';
import ChatMessage from '../../components/ChatMessage';
import SupportChatInput from '../../components/SupportChatInput';

function threadPreview(t) {
  const body = (t.lastBody || '').trim();
  if (body && body !== ' ') return body;
  if (t.lastAttachmentUrl) return '📷 Image attached';
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
  const [selectedId, setSelectedId] = useState(null);
  const [messages, setMessages] = useState([]);
  const [msg, setMsg] = useState('');
  const bottomRef = useRef(null);

  const loadThreads = useCallback(async () => {
    try {
      const r = await getSupportThreads();
      setThreads(r.data ?? []);
    } catch (err) {
      setMsg(apiErrorMessage(err, 'Could not load threads.'));
    }
  }, []);

  useEffect(() => { loadThreads(); const t = setInterval(loadThreads, 10000); return () => clearInterval(t); }, [loadThreads]);

  useEffect(() => {
    const thread = searchParams.get('thread');
    if (thread) setSelectedId(Number(thread));
  }, [searchParams]);

  useEffect(() => {
    if (!selectedId) return;
    const load = () => getSupportMessages(selectedId)
      .then(r => setMessages(r.data ?? []))
      .catch(err => setMsg(apiErrorMessage(err, 'Could not load messages.')));
    markSupportThreadRead(selectedId).catch(() => {});
    load();
    const t = setInterval(load, 5000);
    return () => clearInterval(t);
  }, [selectedId]);

  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages]);

  const selected = threads.find(t => t.id === selectedId);

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

  const selectThread = (t) => {
    setSelectedId(t.id);
    setMsg('');
    setThreads(prev => prev.map(x => x.id === t.id ? { ...x, unread: false } : x));
    markSupportThreadRead(t.id).catch(() => {});
  };

  return (
    <div className="p-6 flex flex-col h-[calc(100vh-3.5rem)] max-h-[calc(100vh-3.5rem)] overflow-hidden">
      <div className="shrink-0">
        <h1 className="text-2xl font-bold text-gray-900 mb-1">Support Chat</h1>
        <p className="text-gray-400 text-sm mb-4">Direct messages with users</p>
        {msg && <div className="text-sm text-blue-600 mb-2">{msg}</div>}
      </div>

      <div className="flex flex-1 gap-4 min-h-0 overflow-hidden">
        <div className="w-80 card overflow-hidden flex flex-col min-h-0 shrink-0">
          <div className="px-4 py-3 border-b border-gray-100 text-xs font-semibold text-gray-500 uppercase">Threads</div>
          <div className="flex-1 overflow-y-auto min-h-0">
            {threads.length === 0 && <p className="p-4 text-sm text-gray-400">No conversations yet.</p>}
            {threads.map(t => (
              <button
                key={t.id}
                type="button"
                onClick={() => selectThread(t)}
                className={`w-full text-left px-4 py-3 border-b border-gray-50 hover:bg-gray-50 transition-colors ${
                  selectedId === t.id ? 'bg-blue-50' : t.unread ? 'bg-sky-50 border-l-4 border-l-sky-500' : ''
                }`}
              >
                <div className="flex items-start justify-between gap-2 mb-1">
                  <div className="flex items-center gap-2 min-w-0">
                    {t.unread && <span className="shrink-0 w-2.5 h-2.5 rounded-full bg-sky-500" aria-hidden />}
                    <p className={`text-sm text-gray-900 truncate ${t.unread ? 'font-bold' : 'font-medium'}`}>
                      {t.username}
                    </p>
                  </div>
                  {t.unread && (
                    <span className="shrink-0 text-[10px] font-bold uppercase tracking-wide text-white bg-sky-500 px-1.5 py-0.5 rounded">
                      New
                    </span>
                  )}
                </div>
                <p className={`text-xs truncate ${t.unread ? 'font-semibold text-gray-800' : 'text-gray-600'}`}>
                  {threadPreview(t)}
                </p>
                <div className="flex items-center justify-between mt-1.5">
                  <span className={`text-xs ${t.status === 'OPEN' ? 'text-green-600' : 'text-gray-400'}`}>{t.status}</span>
                  <span className="text-xs text-gray-400">{formatThreadTime(t.lastMessageAt)}</span>
                </div>
              </button>
            ))}
          </div>
        </div>

        <div className="flex-1 card flex flex-col min-h-0 overflow-hidden">
          {!selectedId ? (
            <div className="flex-1 flex items-center justify-center text-gray-400 text-sm">Select a conversation</div>
          ) : (
            <>
              <div className="px-4 py-3 border-b border-gray-100 flex items-center justify-between bg-white shrink-0">
                <div>
                  <p className="font-semibold text-gray-900">{selected?.username}</p>
                  <p className="text-xs text-gray-500">{selected?.subject}</p>
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
                <div className="p-4 border-t border-gray-100 text-center text-sm text-gray-400 bg-white shrink-0">
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
