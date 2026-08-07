/**
 * Scroll-triggered entrance animation, used to stagger the sections of the landing page
 * and the other marketing pages. Wrap content in it and pass a `delay` in milliseconds to
 * offset one block from the next.
 *
 * Props: `as` picks the element to render (default div), `delay` sets the stagger through
 * the --reveal-delay custom property, and anything else is spread onto the element.
 *
 * A visitor who has asked for reduced motion sees the content appear with no movement:
 * the reduced-motion block in index.css neutralises the .reveal utilities, so there is no
 * media query to check in JavaScript here.
 */
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

  // Watch the element until it first enters the viewport, then disconnect: this is a
  // one-shot entrance, so scrolling back past it must not replay the animation.
  useEffect(() => {
    const el = ref.current;
    if (!el || typeof IntersectionObserver === 'undefined') return;
    const observer = new IntersectionObserver(
      entries => {
        if (!entries.some(e => e.isIntersecting)) return;
        setShown(true);
        observer.disconnect();
      },
      // The negative bottom margin holds the trigger back until the element is properly
      // on screen, rather than firing on the first pixel.
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
