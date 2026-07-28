import { useState, useEffect, useRef, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Bell } from 'lucide-react';
import { getNotifications, markNotificationRead, markAllNotificationsRead } from '../api/notifications';

const POLL_MS = 30000;

function timeAgo(iso) {
  if (!iso) return '';
  const diff = Math.max(0, Date.now() - new Date(iso).getTime());
  const m = Math.floor(diff / 60000);
  if (m < 1) return 'just now';
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.floor(h / 24)}d ago`;
}

export default function NotificationBell() {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState([]);
  const [unread, setUnread] = useState(0);
  const ref = useRef(null);

  const load = useCallback(() => {
    getNotifications()
      .then(r => {
        setItems(r.data.notifications ?? []);
        setUnread(r.data.unreadCount ?? 0);
      })
      .catch(() => {});
  }, []);

  useEffect(() => {
    load();
    const t = setInterval(load, POLL_MS);
    return () => clearInterval(t);
  }, [load]);

  useEffect(() => {
    const onClickOutside = (e) => {
      if (ref.current && !ref.current.contains(e.target)) setOpen(false);
    };
    document.addEventListener('mousedown', onClickOutside);
    return () => document.removeEventListener('mousedown', onClickOutside);
  }, []);

  const handleOpen = () => {
    setOpen(v => !v);
    if (!open) load();
  };

  const handleClick = async (n) => {
    if (!n.read) {
      try { await markNotificationRead(n.id); } catch { /* ignore */ }
      setUnread(u => Math.max(0, u - 1));
      setItems(list => list.map(it => it.id === n.id ? { ...it, read: true } : it));
    }
    setOpen(false);
    if (n.link) navigate(n.link);
  };

  const handleMarkAll = async () => {
    try { await markAllNotificationsRead(); } catch { /* ignore */ }
    setUnread(0);
    setItems(list => list.map(it => ({ ...it, read: true })));
  };

  return (
    <div className="relative" ref={ref}>
      <button
        onClick={handleOpen}
        className={`relative p-2 rounded-full transition-colors ${open ? 'bg-ink-100 text-ink-900' : 'text-ink-500 hover:bg-ink-100 hover:text-ink-900'}`}
        aria-label="Notifications"
      >
        <Bell size={20} />
        {unread > 0 && (
          <span className="absolute top-0 right-0 min-w-[18px] h-[18px] px-1 bg-red-500 text-white text-[10px] font-bold rounded-full flex items-center justify-center ring-2 ring-white">
            {unread > 9 ? '9+' : unread}
          </span>
        )}
      </button>

      {open && (
        <div className="absolute right-0 top-full mt-2.5 bg-white border border-ink-200 rounded-2xl shadow-pop w-[21rem] max-w-[calc(100vw-2rem)] z-50 overflow-hidden animate-scale-in origin-top-right">
          <div className="flex items-center justify-between px-4 py-3 border-b border-ink-100">
            <span className="text-sm font-bold text-ink-900">
              Notifications
              {unread > 0 && <span className="ml-2 badge-danger">{unread} new</span>}
            </span>
            {unread > 0 && (
              <button onClick={handleMarkAll} className="text-xs font-semibold text-primary-600 hover:text-primary-700">
                Mark all read
              </button>
            )}
          </div>
          <div className="max-h-96 overflow-y-auto">
            {items.length === 0 ? (
              <div className="px-4 py-12 text-center">
                <span className="grid place-items-center w-11 h-11 rounded-full bg-ink-100 text-ink-400 mx-auto mb-3">
                  <Bell size={18} />
                </span>
                <p className="text-sm text-ink-400">No notifications yet.</p>
              </div>
            ) : (
              items.map(n => (
                <button
                  key={n.id}
                  onClick={() => handleClick(n)}
                  className={`w-full text-left px-4 py-3 border-b border-ink-100 last:border-0 transition-colors flex gap-2.5 ${
                    n.read ? 'hover:bg-ink-50' : 'bg-primary-50/50 hover:bg-primary-50'
                  }`}
                >
                  <span className={`mt-1.5 w-2 h-2 rounded-full shrink-0 ${n.read ? 'bg-transparent' : 'bg-primary-500'}`} />
                  <span className="min-w-0">
                    <span className={`block text-sm leading-snug ${n.read ? 'text-ink-600' : 'text-ink-900 font-semibold'}`}>{n.message}</span>
                    <span className="block text-xs text-ink-400 mt-0.5">{timeAgo(n.createdAt)}</span>
                  </span>
                </button>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  );
}
