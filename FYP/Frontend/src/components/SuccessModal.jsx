/**
 * Simple success dialog (e.g. after registration).
 */
export default function SuccessModal({ title, message, buttonLabel = 'OK', onClose }) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4">
      <div className="bg-white rounded-2xl shadow-xl w-full max-w-sm p-6 text-center">
        <div className="w-14 h-14 bg-green-100 rounded-full flex items-center justify-center mx-auto mb-4">
          <span className="text-green-500 text-2xl">✓</span>
        </div>
        <h2 className="font-bold text-lg text-gray-900 mb-2">{title}</h2>
        {message && <p className="text-sm text-gray-500 mb-5">{message}</p>}
        <button
          type="button"
          onClick={onClose}
          className="w-full bg-blue-500 hover:bg-blue-600 text-white text-sm font-medium py-2.5 rounded-full transition-colors"
        >
          {buttonLabel}
        </button>
      </div>
    </div>
  );
}
