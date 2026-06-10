import { useState, useEffect, useRef, useCallback } from 'react';
import { useSearchParams, Link } from 'react-router-dom';
import { MessageSquare, Send, Store, ShoppingBag } from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { getConversations, getOrderMessages, sendOrderMessage } from '../api/messages';
import { apiErrorMessage } from '../utils/apiError';
import ChatMessage from '../components/ChatMessage';

export default function Messages() {
  const { user } = useAuth();
  const [searchParams, setSearchParams] = useSearchParams();
  const [conversations, setConversations] = useState([]);
  const [selectedId, setSelectedId] = useState(null);
  const [messages, setMessages] = useState([]);
  const [body, setBody] = useState('');
  const [error, setError] = useState('');
  const bottomRef = useRef(null);

  const loadConversations = useCallback(() => {
    getConversations()
      .then(r => setConversations(r.data ?? []))
      .catch(err => setError(apiErrorMessage(err, 'Could not load conversations.')));
  }, []);

  useEffect(() => {
    loadConversations();
    const t = setInterval(loadConversations, 12000);
    return () => clearInterval(t);
  }, [loadConversations]);

  // Auto-select from ?order= or the first conversation.
  useEffect(() => {
    const fromQuery = searchParams.get('order');
    if (fromQuery) { setSelectedId(Number(fromQuery)); return; }
    setSelectedId(prev => prev ?? (conversations[0]?.orderId ?? null));
  }, [searchParams, conversations]);

  useEffect(() => {
    if (!selectedId) return;
    const load = () => getOrderMessages(selectedId)
      .then(r => { setMessages(r.data ?? []); setError(''); })
      .catch(err => setError(apiErrorMessage(err, 'Could not load messages.')));
    load();
    const t = setInterval(load, 5000);
    return () => clearInterval(t);
  }, [selectedId]);

  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages]);

  const selected = conversations.find(c => c.orderId === selectedId);

  const handleSend = async (e) => {
    e.preventDefault();
    const text = body.trim();
    if (!text || !selectedId) return;
    setError('');
    try {
      await sendOrderMessage(selectedId, text);
      setBody('');
      const r = await getOrderMessages(selectedId);
      setMessages(r.data ?? []);
      loadConversations();
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not send message.'));
    }
  };

  const peerLabel = selected?.counterparty || 'User';

  return (
    <div className="max-w-5xl mx-auto px-4 py-8">
      <div className="mb-5">
        <h1 className="text-2xl font-bold text-gray-900 flex items-center gap-2">
          <MessageSquare size={22} className="text-blue-500" /> Messages
        </h1>
        <p className="text-sm text-gray-400">
          Chat directly with the buyer or seller about an order. For platform help, use{' '}
          <Link to="/support" className="text-blue-500 hover:underline">Contact Admin</Link>.
        </p>
      </div>

      <div className="flex gap-4 h-[min(70vh,560px)]">
        <div className="w-72 bg-white border border-gray-200 rounded-2xl overflow-hidden flex flex-col shrink-0">
          <div className="px-4 py-3 border-b border-gray-100 text-xs font-semibold text-gray-500 uppercase">
            Conversations
          </div>
          <div className="flex-1 overflow-y-auto">
            {conversations.length === 0 && (
              <p className="p-4 text-sm text-gray-400">
                No conversations yet. Start one from an order in your profile.
              </p>
            )}
            {conversations.map(c => (
              <button
                key={c.orderId}
                onClick={() => { setSelectedId(c.orderId); setSearchParams({}); }}
                className={`w-full text-left px-4 py-3 border-b border-gray-50 hover:bg-gray-50 ${
                  selectedId === c.orderId ? 'bg-blue-50' : ''
                }`}
              >
                <div className="flex items-center gap-1.5">
                  {c.role === 'seller'
                    ? <ShoppingBag size={12} className="text-purple-500 shrink-0" />
                    : <Store size={12} className="text-blue-500 shrink-0" />}
                  <p className="font-medium text-sm text-gray-900 truncate">{c.counterparty}</p>
                </div>
                <p className="text-xs text-gray-500 truncate">{c.title}</p>
                {c.lastBody && <p className="text-xs text-gray-400 truncate mt-0.5">{c.lastBody}</p>}
              </button>
            ))}
          </div>
        </div>

        <div className="flex-1 bg-white border border-gray-200 rounded-2xl flex flex-col overflow-hidden">
          {!selectedId ? (
            <div className="flex-1 flex items-center justify-center text-gray-400 text-sm">
              Select a conversation
            </div>
          ) : (
            <>
              <div className="px-4 py-3 border-b border-gray-100 shrink-0">
                <p className="font-semibold text-gray-900">{peerLabel}</p>
                <p className="text-xs text-gray-400">
                  {selected ? `${selected.role === 'seller' ? 'Buyer' : 'Seller'} · ${selected.title}` : `Order #${selectedId}`}
                </p>
              </div>
              <div className="flex-1 overflow-y-auto p-4 space-y-2 bg-[#e8ecf1]">
                {messages.length === 0 && (
                  <p className="text-sm text-gray-400 text-center py-8">No messages yet.</p>
                )}
                {messages.map(m => (
                  <ChatMessage key={m.id} message={m} currentUserId={user?.id} peerLabel={peerLabel} />
                ))}
                <div ref={bottomRef} />
              </div>
              {error && <p className="px-4 py-1 text-xs text-red-500">{error}</p>}
              <form onSubmit={handleSend} className="p-3 border-t border-gray-100 flex gap-2 shrink-0">
                <input
                  value={body}
                  onChange={e => setBody(e.target.value.slice(0, 2000))}
                  placeholder="Type a message…"
                  className="flex-1 border border-gray-200 rounded-full px-4 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-blue-300"
                />
                <button
                  type="submit"
                  disabled={!body.trim()}
                  className="bg-blue-500 text-white w-10 h-10 rounded-full flex items-center justify-center hover:bg-blue-600 disabled:opacity-50 shrink-0"
                >
                  <Send size={16} />
                </button>
              </form>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
