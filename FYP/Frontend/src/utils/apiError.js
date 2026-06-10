/** User-facing text for axios failures (network, 401, 500, etc.). */
export function apiErrorMessage(err, fallback = 'Something went wrong.') {
  if (!err?.response) {
    return 'Cannot reach the server. Start Tomcat on port 8080 (online-auction) and try again.';
  }
  return err.response?.data?.error || fallback;
}
