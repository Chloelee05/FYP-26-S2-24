import { useEffect, useRef } from 'react';

/**
 * Segmented one-time-code entry: one box per digit, with the keyboard and paste
 * behaviour people expect from an OTP field (type to advance, Backspace to go
 * back, arrows to move, paste to fill the whole code).
 *
 * Controlled: `value` is the plain digit string, so callers keep sending the
 * same `otpCode`/`otp` payload the backend already accepts.
 */
export default function CodeInput({
  value = '',
  onChange,
  length = 6,
  disabled = false,
  autoFocus = false,
  invalid = false,
  label = 'Verification code',
  id = 'code-input',
}) {
  const inputsRef = useRef([]);

  useEffect(() => {
    if (autoFocus) inputsRef.current[0]?.focus();
  }, [autoFocus]);

  const digits = Array.from({ length }, (_, i) => value[i] ?? '');

  const commit = (next) => onChange?.(next.slice(0, length));

  const focusBox = (i) => {
    const el = inputsRef.current[Math.max(0, Math.min(length - 1, i))];
    el?.focus();
    el?.select();
  };

  const setDigitAt = (index, digit) => {
    const chars = digits.slice();
    chars[index] = digit;
    commit(chars.join('').replace(/\s/g, ''));
  };

  const handleChange = (index) => (e) => {
    const typed = e.target.value.replace(/\D/g, '');
    if (!typed) { setDigitAt(index, ''); return; }

    // Typing over a filled box, or an autofilled/pasted run of digits.
    if (typed.length > 1) {
      const chars = digits.slice();
      typed.split('').forEach((d, k) => { if (index + k < length) chars[index + k] = d; });
      commit(chars.join(''));
      focusBox(index + typed.length);
      return;
    }

    setDigitAt(index, typed);
    focusBox(index + 1);
  };

  const handleKeyDown = (index) => (e) => {
    if (e.key === 'Backspace') {
      e.preventDefault();
      if (digits[index]) setDigitAt(index, '');
      else if (index > 0) { setDigitAt(index - 1, ''); focusBox(index - 1); }
      return;
    }
    if (e.key === 'ArrowLeft') { e.preventDefault(); focusBox(index - 1); }
    if (e.key === 'ArrowRight') { e.preventDefault(); focusBox(index + 1); }
  };

  const handlePaste = (index) => (e) => {
    const pasted = e.clipboardData.getData('text').replace(/\D/g, '');
    if (!pasted) return;
    e.preventDefault();
    const chars = digits.slice();
    pasted.split('').forEach((d, k) => { if (index + k < length) chars[index + k] = d; });
    commit(chars.join(''));
    focusBox(index + pasted.length);
  };

  return (
    <div role="group" aria-label={label} className="flex gap-2 sm:gap-3">
      {digits.map((digit, i) => (
        <input
          key={i}
          ref={el => { inputsRef.current[i] = el; }}
          id={i === 0 ? id : undefined}
          type="text"
          inputMode="numeric"
          autoComplete={i === 0 ? 'one-time-code' : 'off'}
          aria-label={`${label}, digit ${i + 1} of ${length}`}
          aria-invalid={invalid || undefined}
          value={digit}
          disabled={disabled}
          onChange={handleChange(i)}
          onKeyDown={handleKeyDown(i)}
          onPaste={handlePaste(i)}
          onFocus={e => e.target.select()}
          className={`h-14 w-full min-w-0 rounded-xl border bg-white text-center text-xl font-semibold text-ink-900
            shadow-sm transition-all duration-150 tabular-nums
            focus:outline-none focus:ring-4 focus:ring-primary-500/10
            disabled:bg-ink-50 disabled:text-ink-400 ${
              invalid
                ? 'border-red-300 focus:border-red-400 focus:ring-red-500/10'
                : 'border-ink-200 hover:border-ink-300 focus:border-primary-400'
            }`}
        />
      ))}
    </div>
  );
}
