/**
 * The dialog shell every modal in the app is built on: ReportModal, OrderRefundModal,
 * OrderMessageModal, OrderTrackingModal, RateBuyerModal, SuccessModal and
 * TelegramConnectModal all render their content as children of this component.
 *
 * It renders through createPortal into document.body rather than where it is written in
 * the tree. That is deliberate. In place, the dialog is subject to whatever the ancestors
 * do: an overflow rule on a card clips it, and a backdrop-filter or transform on an
 * ancestor makes position: fixed resolve against that ancestor instead of the viewport.
 * Moving it to document.body means it always sits over the whole page.
 *
 * The accessibility handling lives here so no individual dialog has to repeat it: the
 * panel is role="dialog" with aria-modal, it is labelled by its own title, focus moves
 * into it on open and back to the trigger on close, Tab cycles inside it, and Escape
 * closes it.
 */
import { useEffect, useRef, useId } from 'react';
import { createPortal } from 'react-dom';
import { X } from 'lucide-react';

// Selector for the elements a keyboard user can reach, used both to place the initial
// focus and to work out where the Tab cycle wraps.
const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Accessible dialog shell: labelled, focus-trapped, Escape-closable, and it
 * restores focus to whatever opened it. Page scroll is locked while open.
 *
 * Rendered through a portal on `document.body`. A `backdrop-filter`, `transform` or
 * `filter` on any ancestor makes `position: fixed` resolve against that ancestor
 * instead of the viewport, so a dialog opened from inside the blurred navbar was
 * being centred on the 4rem navbar strip and clipped off the top of the screen.
 *
 * The panel is a flex column capped to the height of the padded backdrop: short
 * dialogs still hug their content, tall ones keep the header put and scroll the body.
 *
 * Props:
 *   title            — accessible name (rendered in the header unless `hideHeader`)
 *   subtitle         — optional secondary line under the title
 *   icon             — optional lucide component shown beside the title
 *   onClose          — required; called by Escape, the backdrop and the close button
 *   dismissOnBackdrop— set false for destructive/multi-step dialogs (default true)
 *   size             — 'sm' | 'md' | 'lg' | 'xl'
 *   scrollBody       — set false when the dialog owns the panel's column itself
 *                      (its own scroll area and a pinned footer)
 */
const SIZES = { sm: 'max-w-sm', md: 'max-w-md', lg: 'max-w-lg', xl: 'max-w-xl' };

export default function Modal({
  title,
  subtitle,
  icon: Icon,
  onClose,
  dismissOnBackdrop = true,
  size = 'md',
  hideHeader = false,
  scrollBody = true,
  className = '',
  children,
}) {
  const panelRef = useRef(null);
  // Whatever had focus when the dialog opened, so it can be given focus back on close.
  const restoreFocusRef = useRef(null);
  // Unique id tying the heading to the panel's aria-labelledby, so several dialogs in one
  // page cannot collide.
  const titleId = useId();

  // Callers pass an inline arrow for `onClose`, so it is a new function on every render.
  // Reading it through a ref keeps the setup effect below on a `[]` dependency list: with
  // `onClose` as a dependency it tore down and re-ran on each keystroke in a controlled
  // field, and the re-run moved focus back to the first focusable element — you had to
  // click the input again after typing a single character.
  const onCloseRef = useRef(onClose);
  useEffect(() => { onCloseRef.current = onClose; });

  // One effect owns the whole open/close lifecycle: initial focus, the key handler,
  // the scroll lock, and undoing all three on unmount.
  useEffect(() => {
    restoreFocusRef.current = document.activeElement;

    // Move focus into the dialog so the keyboard lands inside it, not behind it.
    const panel = panelRef.current;
    const first = panel?.querySelector(FOCUSABLE);
    (first ?? panel)?.focus();

    const onKeyDown = (e) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        onCloseRef.current?.();
        return;
      }
      if (e.key !== 'Tab' || !panel) return;

      // Cycle Tab within the dialog.
      const items = [...panel.querySelectorAll(FOCUSABLE)].filter(el => el.offsetParent !== null);
      if (items.length === 0) { e.preventDefault(); return; }
      const firstItem = items[0];
      const lastItem = items[items.length - 1];
      if (e.shiftKey && document.activeElement === firstItem) {
        e.preventDefault();
        lastItem.focus();
      } else if (!e.shiftKey && document.activeElement === lastItem) {
        e.preventDefault();
        firstItem.focus();
      }
    };

    // Capture phase, so Escape reaches the outermost dialog first even when one dialog
    // opens another.
    document.addEventListener('keydown', onKeyDown, true);

    // Lock page scroll while open, remembering the previous value rather than assuming
    // it was the default. A dialog opened from inside another must not unlock the page.
    const { overflow } = document.body.style;
    document.body.style.overflow = 'hidden';

    return () => {
      document.removeEventListener('keydown', onKeyDown, true);
      document.body.style.overflow = overflow;
      restoreFocusRef.current?.focus?.();
    };
    // Mount-only on purpose — see the onCloseRef note above.
  }, []);

  return createPortal(
    <div
      className="modal-backdrop"
      // mousedown rather than click, and only when the press started on the backdrop
      // itself: a drag that begins inside the panel and ends outside it must not be
      // read as dismissing the dialog.
      onMouseDown={(e) => {
        if (dismissOnBackdrop && e.target === e.currentTarget) onClose?.();
      }}
    >
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={title ? titleId : undefined}
        aria-label={title ? undefined : 'Dialog'}
        tabIndex={-1}
        className={`modal-panel ${SIZES[size] ?? SIZES.md} max-h-full outline-none ${className}`}
      >
        {!hideHeader && (
          <div className="flex items-start justify-between gap-3 p-5 border-b border-ink-100 shrink-0">
            <div className="flex items-center gap-3 min-w-0">
              {Icon && (
                <span className="grid place-items-center w-10 h-10 rounded-xl bg-primary-50 text-primary-600 shrink-0">
                  <Icon size={18} />
                </span>
              )}
              <div className="min-w-0">
                <h2 id={titleId} className="font-display font-bold text-ink-900">{title}</h2>
                {subtitle && <p className="text-xs text-ink-400 mt-0.5 line-clamp-1">{subtitle}</p>}
              </div>
            </div>
            <button
              type="button"
              onClick={onClose}
              aria-label="Close dialog"
              className="p-1.5 rounded-lg text-ink-400 hover:bg-ink-100 hover:text-ink-700 transition-colors shrink-0"
            >
              <X size={18} />
            </button>
          </div>
        )}
        {/* `min-h-0` lets this shrink past its content once the panel hits its cap,
            which is what makes the body the part that scrolls. */}
        {scrollBody ? <div className="min-h-0 overflow-y-auto">{children}</div> : children}
      </div>
    </div>,
    document.body,
  );
}
