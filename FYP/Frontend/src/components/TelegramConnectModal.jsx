import { useState, useEffect, useRef, useCallback } from 'react';
import { Link } from 'react-router-dom';
import {
  Send, ShieldCheck, CheckCircle2, Check, Copy, ChevronDown, Loader2,
  AlertCircle, ExternalLink, RefreshCw,
} from 'lucide-react';
import Modal from './Modal';
import usePolling from '../hooks/usePolling';
import { getTelegramStatus, startTelegramLink } from '../api/telegram';
import { apiErrorMessage } from '../utils/apiError';

/**
 * Three-step Telegram linking dialog: explain and consent, wait for the link, confirm.
 *
 * The deep link is the happy path and owns the visual weight; the 6-digit code is a
 * fallback for people whose Telegram is on another device, so it sits behind a quiet
 * disclosure. Both redeem through the same server-side single-use code.
 *
 * Explanatory copy comes from the server (`landing_content`, editable by an admin) with
 * the strings below as fallbacks; button labels and errors stay here because they are
 * interface mechanics rather than content.
 */

const POLL_INTERVAL_MS = 3000;
const POLL_TIMEOUT_MS = 5 * 60 * 1000;

const FALLBACK_COPY = {
  'telegram.connect.heading': 'Get auction alerts on Telegram',
  'telegram.connect.body':
    'Link your Telegram account and AuctionHub will message you the moment something happens on a listing you care about.',
  'telegram.connect.events':
    'You were outbid on an auction\nYou won an auction\nAn auction you bid on closed without you\nYour listing sold or ended (sellers)',
  'telegram.connect.privacy':
    'We store your Telegram chat ID so we can send you these messages. It is encrypted at rest and is never shown to other members. Connecting is your consent to this use; disconnect at any time from Account settings.',
};

const formatCountdown = (ms) => {
  const total = Math.max(0, Math.ceil(ms / 1000));
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`;
};

/** Small copy-to-clipboard affordance shared by the bot handle and the code. */
function CopyButton({ value, label }) {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      setTimeout(() => setCopied(false), 1600);
    } catch {
      // Clipboard permission denied — the value is on screen to read anyway.
    }
  };

  return (
    <button
      type="button"
      onClick={handleCopy}
      aria-label={copied ? `${label} copied` : `Copy ${label}`}
      className="p-1.5 rounded-lg text-ink-400 hover:bg-ink-100 hover:text-ink-700 transition-colors shrink-0"
    >
      {copied ? <Check size={15} className="text-emerald-600" /> : <Copy size={15} />}
    </button>
  );
}

/** Read-only digit tiles, matching the boxes people type codes into elsewhere. */
function CodeDisplay({ code }) {
  return (
    <div className="flex gap-1.5" aria-label={`Your code is ${code.split('').join(' ')}`}>
      {code.split('').map((digit, i) => (
        <span
          key={i}
          aria-hidden="true"
          className="grid place-items-center h-10 w-8 rounded-lg border border-ink-200 bg-white
                     text-base font-semibold tabular-nums text-ink-900 shadow-sm"
        >
          {digit}
        </span>
      ))}
    </div>
  );
}

export default function TelegramConnectModal({ onClose, onLinked }) {
  const [step, setStep] = useState('explain'); // explain | waiting | success
  const [copy, setCopy] = useState(FALLBACK_COPY);
  const [available, setAvailable] = useState(true);
  const [session, setSession] = useState(null); // { deepLink, code, botUsername, expiresAt }
  const [remaining, setRemaining] = useState(0);
  const [expired, setExpired] = useState(false);
  const [fallbackOpen, setFallbackOpen] = useState(false);
  const [linkedUsername, setLinkedUsername] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const mounted = useRef(true);
  const pollStartedAt = useRef(0);
  useEffect(() => () => { mounted.current = false; }, []);

  // Load the admin-editable copy, and short-circuit if this account is already linked.
  useEffect(() => {
    getTelegramStatus()
      .then(({ data }) => {
        if (!mounted.current) return;
        setAvailable(data.available !== false);
        if (data.copy && Object.keys(data.copy).length) {
          setCopy({ ...FALLBACK_COPY, ...data.copy });
        }
        if (data.linked) {
          setLinkedUsername(data.telegramUsername || '');
          setStep('success');
        }
      })
      .catch(() => { /* Fall back to the built-in copy; the connect button still works. */ });
  }, []);

  const beginLink = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const { data } = await startTelegramLink();
      if (!mounted.current) return;
      setSession({
        deepLink: data.deepLink,
        code: data.code,
        botUsername: data.botUsername,
        expiresAt: Date.now() + (data.expiresInSeconds ?? 600) * 1000,
      });
      setExpired(false);
      pollStartedAt.current = Date.now();
      setStep('waiting');
    } catch (err) {
      if (mounted.current) setError(apiErrorMessage(err, 'Could not start the connection. Please try again.'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }, []);

  // Countdown to code expiry.
  useEffect(() => {
    if (step !== 'waiting' || !session) return undefined;
    const tick = () => {
      const left = session.expiresAt - Date.now();
      setRemaining(left);
      if (left <= 0) setExpired(true);
    };
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [step, session]);

  // The bot talks to the server, not to this tab, so polling is the only way the dialog
  // learns it succeeded. Stops on unmount, once linked, and once the code has expired.
  const pollForLink = useCallback(async ({ signal }) => {
    if (Date.now() - pollStartedAt.current > POLL_TIMEOUT_MS) {
      setExpired(true);
      return;
    }
    const { data } = await getTelegramStatus({ signal });
    if (!data.linked) return;
    setLinkedUsername(data.telegramUsername || '');
    setStep('success');
    onLinked?.();
  }, [onLinked]);

  usePolling(pollForLink, POLL_INTERVAL_MS, step === 'waiting' && !expired);

  const events = (copy['telegram.connect.events'] || '')
    .split('\n')
    .map(line => line.trim())
    .filter(Boolean);

  return (
    <Modal
      title={step === 'success' ? 'Telegram connected' : copy['telegram.connect.heading']}
      subtitle={step === 'waiting' ? 'Step 2 of 2 — confirm in Telegram' : undefined}
      icon={step === 'success' ? CheckCircle2 : Send}
      onClose={onClose}
      dismissOnBackdrop={false}
      size="md"
    >
      <div className="p-5 space-y-4">
        {error && (
          <div className="alert-error">
            <AlertCircle size={16} className="mt-0.5 shrink-0" />
            <span>{error}</span>
          </div>
        )}

        {!available && (
          <div className="alert-warning">
            <AlertCircle size={16} className="mt-0.5 shrink-0" />
            <span>Telegram alerts aren’t switched on for this server yet. Please try again later.</span>
          </div>
        )}

        {/* ── Step 1: what you get, what we keep, and consent ─────────────── */}
        {step === 'explain' && (
          <>
            <p className="text-sm text-ink-600 leading-relaxed">{copy['telegram.connect.body']}</p>

            {events.length > 0 && (
              <ul className="space-y-2">
                {events.map(event => (
                  <li key={event} className="flex items-start gap-2.5 text-sm text-ink-700">
                    <Check size={15} className="mt-0.5 shrink-0 text-emerald-600" />
                    <span>{event}</span>
                  </li>
                ))}
              </ul>
            )}

            <div className="surface-muted p-4">
              <div className="flex items-start gap-2.5">
                <ShieldCheck size={16} className="mt-0.5 shrink-0 text-ink-500" />
                <div className="min-w-0">
                  <p className="text-xs font-semibold text-ink-700 mb-1">What we store</p>
                  <p className="text-xs text-ink-500 leading-relaxed">{copy['telegram.connect.privacy']}</p>
                  <Link to="/privacy" className="link-subtle text-xs inline-flex items-center gap-1 mt-2">
                    Read the Privacy Notice <ExternalLink size={11} />
                  </Link>
                </div>
              </div>
            </div>

            <div className="flex gap-3 pt-1">
              <button type="button" onClick={onClose} className="btn-secondary flex-1">
                Not now
              </button>
              <button
                type="button"
                onClick={beginLink}
                disabled={busy || !available}
                className="btn-primary flex-1"
              >
                {busy ? <Loader2 size={15} className="animate-spin" /> : <Send size={15} />}
                {busy ? 'Preparing…' : 'Agree & connect'}
              </button>
            </div>
          </>
        )}

        {/* ── Step 2: open the deep link, or fall back to the code ────────── */}
        {step === 'waiting' && session && (
          <>
            {expired ? (
              <div className="alert-warning">
                <AlertCircle size={16} className="mt-0.5 shrink-0" />
                <span>This code has expired. Get a new one to carry on.</span>
              </div>
            ) : (
              <p className="text-sm text-ink-600 leading-relaxed">
                Open the bot and press <span className="font-semibold text-ink-800">Start</span>. We’ll finish
                the connection here as soon as Telegram confirms it.
              </p>
            )}

            <a
              href={session.deepLink}
              target="_blank"
              rel="noopener noreferrer"
              aria-disabled={expired}
              className={`btn-primary btn-lg btn-block ${expired ? 'pointer-events-none opacity-50' : ''}`}
            >
              <Send size={17} /> Open in Telegram
            </a>

            <div className="flex items-center justify-center gap-2 text-xs text-ink-400">
              {expired ? (
                <button type="button" onClick={beginLink} disabled={busy} className="btn-ghost btn-sm">
                  <RefreshCw size={13} /> Get a new code
                </button>
              ) : (
                <>
                  <Loader2 size={13} className="animate-spin" />
                  <span>
                    Waiting for Telegram — code expires in{' '}
                    <span className="tabular-nums font-semibold text-ink-600">{formatCountdown(remaining)}</span>
                  </span>
                </>
              )}
            </div>

            <div className="divider pt-3">
              <button
                type="button"
                onClick={() => setFallbackOpen(v => !v)}
                aria-expanded={fallbackOpen}
                className="flex items-center gap-1.5 text-xs font-medium text-ink-400 hover:text-ink-600 transition-colors"
              >
                <ChevronDown size={13} className={`transition-transform ${fallbackOpen ? 'rotate-180' : ''}`} />
                Link not working?
              </button>

              {fallbackOpen && (
                <div className="mt-3 surface-muted p-4 space-y-3 animate-fade-up">
                  <p className="text-xs text-ink-500 leading-relaxed">
                    If Telegram is on another device, search for the bot there and send it this code.
                  </p>
                  <div className="flex items-center justify-between gap-2">
                    <div className="min-w-0">
                      <p className="text-[11px] font-semibold text-ink-400 uppercase tracking-wide">Bot</p>
                      <p className="text-sm font-medium text-ink-800 truncate">@{session.botUsername}</p>
                    </div>
                    <CopyButton value={`@${session.botUsername}`} label="bot username" />
                  </div>
                  <div className="flex items-end justify-between gap-2">
                    <div>
                      <p className="text-[11px] font-semibold text-ink-400 uppercase tracking-wide mb-1.5">Code</p>
                      <CodeDisplay code={session.code} />
                    </div>
                    <CopyButton value={session.code} label="code" />
                  </div>
                </div>
              )}
            </div>

            <button type="button" onClick={onClose} className="btn-ghost btn-block">
              Cancel
            </button>
          </>
        )}

        {/* ── Step 3: confirmation ────────────────────────────────────────── */}
        {step === 'success' && (
          <>
            <div className="flex flex-col items-center text-center py-2">
              <span className="grid place-items-center w-14 h-14 rounded-2xl bg-emerald-50 text-emerald-600 mb-3">
                <CheckCircle2 size={26} />
              </span>
              <p className="font-display font-bold text-ink-900">You’re all set</p>
              <p className="text-sm text-ink-500 mt-1 max-w-xs">
                {linkedUsername
                  ? <>Alerts will arrive in Telegram as <span className="font-semibold text-ink-700">@{linkedUsername}</span>.</>
                  : 'Alerts will arrive in your linked Telegram chat.'}
              </p>
            </div>

            <Link
              to="/profile/settings?tab=notifications"
              onClick={onClose}
              className="btn-secondary btn-block"
            >
              Choose which alerts you get
            </Link>
            <button type="button" onClick={onClose} className="btn-primary btn-block">
              Done
            </button>
          </>
        )}
      </div>
    </Modal>
  );
}
