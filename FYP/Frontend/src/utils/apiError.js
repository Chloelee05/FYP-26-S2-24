/**
 * Turns an axios error into one sentence a user can act on. Every catch block in the app
 * that shows an error to the screen goes through this, so a failed bid and a failed
 * upload report themselves the same way instead of each page inventing its own wording.
 */

/**
 * User-facing text for axios failures (network, 401, 500, etc.).
 *
 * A request that never reached the server has no `response` at all — that reads very
 * differently from a server that answered with an error, so it gets its own message
 * rather than the caller's generic fallback.
 *
 * Servlets in this project report failures as either `error` or `message` depending on
 * the endpoint, so both are checked before falling back.
 */
export function apiErrorMessage(err, fallback = 'Something went wrong.') {
  if (!err?.response) {
    return 'Cannot reach the server. Start Tomcat on port 8080 (online-auction) and try again.';
  }
  const data = err.response.data;
  return data?.error || data?.message || fallback;
}
