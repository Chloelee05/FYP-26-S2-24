import { useState, useEffect, useRef, useCallback } from 'react';
import { Link } from 'react-router-dom';
import { User, Send } from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { getOrderMessages, sendOrderMessage } from '../api/messages';
import { apiErrorMessage } from '../utils/apiError';
import usePolling from '../hooks/usePolling';
import ChatMessage from './ChatMessage';
import Modal from './Modal';

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

  const load = useCallback(async (config) => {
    if (!order) return;
    try {
      const r = await getOrderMessages(order.id, config);
      setMessages(r.data ?? []);
      setError('');
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not load messages.'));
    }
  }, [order]);

  usePolling(load, 5000, Boolean(order));

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
      setError(apiErrorMessage(err, 'Could not send message.'));
    } finally {
      setSending(false);
    }
  };

  return (
    <Modal
      title={peerLabel}
      subtitle={`${isSeller ? 'Buyer' : 'Seller'} · ${order.auctionTitle}`}
      onClose={onClose}
      size="md"
      className="h-[min(80vh,560px)] flex flex-col overflow-hidden bg-ink-50"
    >
      <>
        {!isSeller && order.sellerId && (
          <Link
            to={`/seller/${order.sellerId}`}
            onClick={onClose}
            className="px-4 py-1.5 text-xs text-primary-500 hover:underline flex items-center gap-1 bg-white border-b border-ink-100"
          >
            <User size={12} /> View seller profile
          </Link>
        )}

        <div className="flex-1 overflow-y-auto p-3 space-y-2 bg-ink-100">
          {messages.length === 0 && (
            <p className="text-sm text-ink-400 text-center py-8">
              No messages yet. Say hello to {isSeller ? 'the buyer' : 'the seller'} about this order.
            </p>
          )}
          {messages.map(m => (
            <ChatMessage key={m.id} message={m} currentUserId={user?.id} peerLabel={peerLabel} />
          ))}
          <div ref={bottomRef} />
        </div>

        {error && <p className="px-4 py-1 text-xs text-red-500 bg-white">{error}</p>}

        <form onSubmit={handleSend} className="p-3 border-t border-ink-100 bg-white flex gap-2 shrink-0">
          <input
            value={body}
            onChange={e => setBody(e.target.value.slice(0, 2000))}
            placeholder="Type a message…"
            className="input-field flex-1 rounded-full"
          />
          <button
            type="submit"
            disabled={sending || !body.trim()}
            className="btn-primary w-10 h-10 p-0 rounded-full shrink-0"
          >
            <Send size={16} />
          </button>
        </form>
      </>
    </Modal>
  );
}
