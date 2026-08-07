/**
 * Password input with a reveal toggle, used on login, register, password reset and the
 * security tab of settings. Controlled: the caller owns `value` and `onChange`, and the
 * remaining props pass straight through to the input.
 */
import { useState } from 'react';
import { Eye, EyeOff } from 'lucide-react';

/**
 * Password input with show/hide toggle (eye icon).
 */
export default function PasswordField({
  id,
  name,
  value,
  onChange,
  placeholder,
  required = false,
  className = 'input-field',
  autoComplete,
}) {
  const [visible, setVisible] = useState(false);

  return (
    <div className="relative">
      <input
        id={id}
        name={name}
        type={visible ? 'text' : 'password'}
        value={value}
        onChange={onChange}
        placeholder={placeholder}
        required={required}
        autoComplete={autoComplete}
        className={`${className} pr-12`}
      />
      <button
        type="button"
        onClick={() => setVisible(v => !v)}
        className="absolute right-4 top-1/2 -translate-y-1/2 text-ink-400 hover:text-ink-600 p-1"
        aria-label={visible ? 'Hide password' : 'Show password'}
        // Out of the tab order on purpose, so Tab goes from the password to the submit
        // button. The aria-label keeps it reachable to a screen reader.
        tabIndex={-1}
      >
        {visible ? <EyeOff size={18} /> : <Eye size={18} />}
      </button>
    </div>
  );
}
