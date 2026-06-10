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
        className="absolute right-4 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600 p-1"
        aria-label={visible ? 'Hide password' : 'Show password'}
        tabIndex={-1}
      >
        {visible ? <EyeOff size={18} /> : <Eye size={18} />}
      </button>
    </div>
  );
}
