import { useEffect, useRef, useState } from 'react';

/**
 * Fades and slides its content in the first time it scrolls into view.
 * The motion itself lives in the `.reveal` utilities, so the reduced-motion
 * block in index.css can neutralise it without any JS branching here.
 * Interactive children stay outside the revealed element so the stagger
 * delay never leaks into their own hover transitions.
 */
export default function Reveal({ as: Tag = 'div', className = '', delay = 0, style, children, ...rest }) {
  const ref = useRef(null);
  // Without observer support, show the content rather than hiding it forever.
  const [shown, setShown] = useState(() => typeof IntersectionObserver === 'undefined');

  useEffect(() => {
    const el = ref.current;
    if (!el || typeof IntersectionObserver === 'undefined') return;
    const observer = new IntersectionObserver(
      entries => {
        if (!entries.some(e => e.isIntersecting)) return;
        setShown(true);
        observer.disconnect();
      },
      { threshold: 0, rootMargin: '0px 0px -12% 0px' },
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  return (
    <Tag
      ref={ref}
      className={`reveal${shown ? ' reveal-in' : ''}${className ? ` ${className}` : ''}`}
      style={delay ? { ...style, '--reveal-delay': `${delay}ms` } : style}
      {...rest}
    >
      {children}
    </Tag>
  );
}
