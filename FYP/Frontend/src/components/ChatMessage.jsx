import { publicPath } from '../utils/appBase';

/** Telegram-style bubble — own messages right, others left. */
export default function ChatMessage({ message, currentUserId, peerLabel = 'Support' }) {
  const isMe = Number(message.senderId) === Number(currentUserId);
  const time = message.createdAt
    ? new Date(message.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    : '';

  return (
    <div className={`flex ${isMe ? 'justify-end' : 'justify-start'}`}>
      <div className={`max-w-[80%] flex flex-col ${isMe ? 'items-end' : 'items-start'}`}>
        {!isMe && (
          <span className="text-[11px] text-ink-400 mb-0.5 px-1">
            {message.senderName || peerLabel}
          </span>
        )}
        <div
          className={`rounded-2xl px-3.5 py-2 text-sm shadow-sm leading-relaxed ${
            isMe
              ? 'bg-primary-600 text-white rounded-br-md'
              : 'bg-white border border-ink-200 text-ink-800 rounded-bl-md'
          }`}
        >
          {message.attachmentUrl && (
            <a href={publicPath(message.attachmentUrl)} target="_blank" rel="noreferrer" className="block mb-1">
              <img
                src={publicPath(message.attachmentUrl)}
                alt="attachment"
                className="max-w-full rounded-lg max-h-52 object-cover"
              />
            </a>
          )}
          {message.body ? (
            <p className="whitespace-pre-wrap">{message.body}</p>
          ) : null}
        </div>
        {time && (
          <span className="text-[10px] text-ink-400 mt-0.5 px-1">{time}</span>
        )}
      </div>
    </div>
  );
}
