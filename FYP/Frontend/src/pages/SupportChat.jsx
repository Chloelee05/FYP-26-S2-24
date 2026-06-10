import { useState, useEffect, useRef } from 'react';
import { useAuth } from '../context/AuthContext';
import { getSupportThreads, createSupportThread, getSupportMessages, sendSupportMessage } from '../api/support';
import { apiErrorMessage } from '../utils/apiError';
import ChatMessage from '../components/ChatMessage';
import SupportChatInput from '../components/SupportChatInput';

export default function SupportChat() {
  const { user } = useAuth();
  const [threads, setThreads] = useState([]);
  const [selectedId, setSelectedId] = useState(null);
  const [messages, setMessages] = useState([]);
  const [newSubject, setNewSubject] = useState('');
  const [newBody, setNewBody] = useState('');
  const [showNew, setShowNew] = useState(false);
  const [msg, setMsg] = useState('');
  const bottomRef = useRef(null);

  const loadThreads = () => getSupportThreads().then(r => {
    const list = r.data ?? [];
    setThreads(list);
    setSelectedId(prev => {
      const id = prev ?? list[0]?.id;
      return id != null ? Number(id) : null;
    });
  }).catch(err => setMsg(apiErrorMessage(err, 'Could not load threads.')));

  useEffect(() => { loadThreads(); }, []);

  useEffect(() => {
    if (!selectedId) return;
    const load = () => getSupportMessages(selectedId)
      .then(r => setMessages(r.data ?? []))
      .catch(err => setMsg(apiErrorMessage(err, 'Could not load messages.')));
    load();
    const t = setInterval(load, 5000);
    return () => clearInterval(t);
  }, [selectedId]);

  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages]);

  const selected = threads.find(t => Number(t.id) === Number(selectedId));

  const handleCreate = async (e) => {
    e.preventDefault();
    if (!newBody.trim()) return;
    try {
      const r = await createSupportThread(newSubject, newBody.trim());
      setShowNew(false);
      setNewSubject('');
      setNewBody('');
      const id = Number(r.data.threadId);
      await loadThreads();
      setSelectedId(id);
      const msgs = await getSupportMessages(id);
      setMessages(msgs.data ?? []);
      setMsg('Support request sent. An admin will respond soon.');
    } catch (err) {
      setMsg(apiErrorMessage(err, 'Could not start conversation.'));
    }
  };

  const handleSend = async ({ body, attachmentUrl }) => {
    if (!selectedId || selected?.status === 'CLOSED') return;
    try {
      await sendSupportMessage(selectedId, body, attachmentUrl);
      const r = await getSupportMessages(selectedId);
      setMessages(r.data ?? []);
    } catch (err) {
      setMsg(apiErrorMessage(err, 'Could not send message.'));
    }
  };

  return (
    <div className="max-w-4xl mx-auto px-4 py-8">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Contact Admin</h1>
          <p className="text-gray-400 text-sm">Chat directly with our support team</p>
        </div>
        <button
          onClick={() => setShowNew(true)}
          className="bg-blue-500 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-blue-600"
        >
          New request
        </button>
      </div>
      {msg && <div className="text-sm text-blue-600 mb-4">{msg}</div>}

      {showNew && (
        <div className="card p-5 mb-6">
          <h2 className="font-bold text-gray-900 mb-3">New support request</h2>
          <form onSubmit={handleCreate} className="space-y-3">
            <input
              value={newSubject}
              onChange={e => setNewSubject(e.target.value)}
              placeholder="Subject (optional)"
              className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm"
            />
            <textarea
              value={newBody}
              onChange={e => setNewBody(e.target.value)}
              placeholder="Describe your issue…"
              rows={4}
              required
              className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm resize-none"
            />
            <div className="flex gap-2">
              <button type="submit" className="px-4 py-2 bg-blue-500 text-white text-sm rounded-lg">Send</button>
              <button type="button" onClick={() => setShowNew(false)} className="px-4 py-2 text-sm text-gray-500">Cancel</button>
            </div>
          </form>
        </div>
      )}

      <div className="card flex min-h-[420px]">
        {threads.length === 0 && !showNew ? (
          <div className="flex-1 flex items-center justify-center text-gray-400 text-sm p-8">
            No conversations yet. Start a new request above.
          </div>
        ) : (
          <>
            {threads.length > 1 && (
              <div className="w-48 border-r border-gray-100 overflow-y-auto">
                {threads.map(t => (
                  <button
                    key={t.id}
                    onClick={() => setSelectedId(Number(t.id))}
                    className={`w-full text-left px-3 py-2 text-sm border-b border-gray-50 hover:bg-gray-50 ${Number(selectedId) === Number(t.id) ? 'bg-blue-50' : ''}`}
                  >
                    <p className="font-medium truncate">{t.subject}</p>
                    <p className="text-xs text-gray-400">{t.status}</p>
                  </button>
                ))}
              </div>
            )}
            <div className="flex-1 flex flex-col">
              {selectedId ? (
                <>
                  <div className="px-4 py-3 border-b border-gray-100">
                    <p className="font-semibold text-gray-900">{selected?.subject}</p>
                    <p className="text-xs text-gray-400">{selected?.status}</p>
                  </div>
                  <div className="flex-1 overflow-y-auto p-4 space-y-2 bg-[#e8ecf1]">
                    {messages.map(m => (
                      <ChatMessage key={m.id} message={m} currentUserId={user?.id} peerLabel="Admin" />
                    ))}
                    <div ref={bottomRef} />
                  </div>
                  {selected?.status === 'OPEN' ? (
                    <SupportChatInput onSend={handleSend} placeholder="Type a message…" />
                  ) : (
                    <div className="p-4 border-t text-center text-sm text-gray-400">This conversation is closed.</div>
                  )}
                </>
              ) : null}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
