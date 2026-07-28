import { Check } from 'lucide-react';
import Modal from './Modal';

/**
 * Simple success dialog (e.g. after registration).
 */
export default function SuccessModal({ title, message, buttonLabel = 'OK', onClose }) {
  return (
    <Modal title={title} onClose={onClose} size="sm" hideHeader dismissOnBackdrop={false}>
      <div className="p-7 text-center">
        <div className="w-16 h-16 rounded-full bg-emerald-50 ring-8 ring-emerald-50/60 flex items-center justify-center mx-auto mb-5">
          <span className="grid place-items-center w-10 h-10 rounded-full bg-emerald-500 text-white">
            <Check size={22} strokeWidth={3} />
          </span>
        </div>
        <h2 className="font-display font-bold text-lg text-ink-900 mb-2">{title}</h2>
        {message && <p className="text-sm text-ink-500 mb-6 leading-relaxed">{message}</p>}
        <button type="button" onClick={onClose} className="btn-primary btn-block">
          {buttonLabel}
        </button>
      </div>
    </Modal>
  );
}
