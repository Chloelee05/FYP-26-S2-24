import { useState, useEffect, useRef, useCallback } from 'react';
import { Link } from 'react-router-dom';
import { X, User, Send } from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { getOrderMessages, sendOrderMessage } from '../api/messages';
import ChatMessage from './ChatMessage';

/**
 * Direct chat with the order's counterparty (buyer<->seller), Shopee-style.
 * Distinct from admin support — messages go straight to the other party.
 */
export default function OrderMessageModal({ order, onClose }) {
  const { user } = useAuth();
  const [messages, setMessages] = useState([]);
  const [body, setBody] = useState('');
  const [error, setError] = useState('');
  const [sending, setSending] = useState(false);
  const bottomRef = useRef(null);

  const isSeller = order?.role === 'seller';
  const peerLabel = order?.counterparty || (isSeller ? 'Buyer' : 'Seller');

  const load = useCallback(() => {
    if (!order) return;
    getOrderMessages(order.id)
      .then(r => setMessages(r.data ?? []))
      .catch(() => {});
  }, [order]);

  useEffect(() => {
    if (!order) return;
    load();
    const t = setInterval(load, 5000);
    return () => clearInterval(t);
  }, [order, load]);

  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages]);

  if (!order) return null;

  const handleSend = async (e) => {
    e.preventDefault();
    const text = body.trim();
    if (!text) return;
    setSending(true);
    setError('');
    try {
      await sendOrderMessage(order.id, text);
      setBody('');
      load();
    } catch (err) {
      setError(err.response?.data?.error || 'Could not send message.');
    } finally {
      setSending(false);
    }
  };

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4" onClick={onClose}>
      <div
        className="bg-gray-50 rounded-2xl shadow-xl max-w-md w-full h-[min(80vh,560px)] flex flex-col overflow-hidden"
        onClick={e => e.stopPropagation()}
      >
        <div className="flex items-center justify-between px-4 py-3 bg-white border-b border-gray-100 shrink-0">
          <div className="flex items-center gap-3 min-w-0">
            <div className="w-9 h-9 rounded-full bg-blue-100 flex items-center justify-center text-blue-600 font-bold">
              {peerLabel?.[0]?.toUpperCase() ?? 'U'}
            </div>
            <div className="min-w-0">
              <p className="font-semibold text-sm text-gray-900 truncate">{peerLabel}</p>
              <p className="text-xs text-gray-400 truncate">
                {isSeller ? 'Buyer' : 'Seller'} · {order.auctionTitle}
              </p>
            </div>
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600 shrink-0"><X size={18} /></button>
        </div>

        {!isSeller && order.sellerId && (
          <Link
            to={`/seller/${order.sellerId}`}
            onClick={onClose}
            className="px-4 py-1.5 text-xs text-blue-500 hover:underline flex items-center gap-1 bg-white border-b border-gray-100"
          >
            <User size={12} /> View seller profile
          </Link>
        )}

        <div className="flex-1 overflow-y-auto p-3 space-y-2 bg-[#e8ecf1]">
          {messages.length === 0 && (
            <p className="text-sm text-gray-400 text-center py-8">
              No messages yet. Say hello to {isSeller ? 'the buyer' : 'the seller'} about this order.
            </p>
          )}
          {messages.map(m => (
            <ChatMessage key={m.id} message={m} currentUserId={user?.id} peerLabel={peerLabel} />
          ))}
          <div ref={bottomRef} />
        </div>

        {error && <p className="px-4 py-1 text-xs text-red-500 bg-white">{error}</p>}

        <form onSubmit={handleSend} className="p-3 border-t border-gray-100 bg-white flex gap-2 shrink-0">
          <input
            value={body}
            onChange={e => setBody(e.target.value.slice(0, 2000))}
            placeholder="Type a message…"
            className="flex-1 border border-gray-200 rounded-full px-4 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-blue-300"
          />
          <button
            type="submit"
            disabled={sending || !body.trim()}
            className="bg-blue-500 text-white w-10 h-10 rounded-full flex items-center justify-center hover:bg-blue-600 disabled:opacity-50 shrink-0"
          >
            <Send size={16} />
          </button>
        </form>
      </div>
    </div>
  );
}
