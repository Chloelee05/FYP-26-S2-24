/**
 * The Google sign-in button on the login and register pages, and on the linked accounts
 * section of account settings.
 *
 * Props: `onCredential(idToken)` receives the Google ID token, which the caller then
 * posts to /api/oauth/login or /api/oauth/link for the server to verify;
 * `onAvailabilityChange(bool)` lets the page hide its "or" divider when Google is not
 * configured; `text` picks the wording Google renders on the button.
 *
 * The button itself is drawn by Google's own script rather than by us, which is a
 * requirement of using their identity service.
 */
import { useEffect, useRef, useState } from 'react';
import { getOAuthConfig } from '../api/auth';

// Google Identity Services script is loaded once and shared.
let gsiScriptPromise;
function loadGsiScript() {
  if (!gsiScriptPromise) {
    gsiScriptPromise = new Promise((resolve, reject) => {
      if (window.google?.accounts?.id) { resolve(); return; }
      const s = document.createElement('script');
      s.src = 'https://accounts.google.com/gsi/client';
      s.async = true;
      s.onload = resolve;
      s.onerror = reject;
      document.head.appendChild(s);
    });
  }
  return gsiScriptPromise;
}

/**
 * Renders the official Google sign-in button and calls `onCredential(idToken)`
 * when the user completes the Google flow. Renders nothing when the server has
 * no GOOGLE_CLIENT_ID configured.
 */
export default function GoogleSignInButton({ onCredential, onAvailabilityChange, text = 'signin_with' }) {
  const divRef = useRef(null);
  const [hidden, setHidden] = useState(false);
  const callbackRef = useRef(onCredential);
  const availabilityRef = useRef(onAvailabilityChange);

  // Keep the latest callbacks without re-running the script/render effect below.
  useEffect(() => {
    callbackRef.current = onCredential;
    availabilityRef.current = onAvailabilityChange;
  });

  // Ask the server whether Google is configured, and only then load the script and let
  // Google draw its button. Anything that goes wrong along the way, including no client
  // id and a blocked script, ends in `unavailable` and the component renders nothing, so
  // the password form is still usable.
  useEffect(() => {
    let cancelled = false;
    const unavailable = () => {
      if (cancelled) return;
      setHidden(true);
      availabilityRef.current?.(false);
    };
    getOAuthConfig()
      .then(async r => {
        const cfg = r.data?.google;
        if (!cfg?.configured) { unavailable(); return; }
        await loadGsiScript();
        if (cancelled || !divRef.current) return;
        window.google.accounts.id.initialize({
          client_id: cfg.clientId,
          callback: resp => callbackRef.current?.(resp.credential),
        });
        window.google.accounts.id.renderButton(divRef.current, {
          theme: 'outline', size: 'large', text, shape: 'pill',
        });
        availabilityRef.current?.(true);
      })
      .catch(unavailable);
    return () => { cancelled = true; };
  }, [text]);

  if (hidden) return null;
  return <div ref={divRef} className="flex justify-center" />;
}
