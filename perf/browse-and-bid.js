/**
 * AuctionHub load test — verifies the response-time targets in section 6.1 of the technical
 * documentation against a running instance.
 *
 * Those targets (page load < 2s, search < 2s, place bid < 3s, authentication < 1s) have been
 * stated since the PRD but never measured, which is why the documentation lists "no
 * multi-threaded load test" as a known limitation. This script closes that gap: it encodes each
 * target as a k6 threshold, so the run fails if the system misses one rather than merely
 * printing numbers for someone to interpret charitably.
 *
 * The load shape is deliberately browse-heavy. On a C2C marketplace most traffic reads listings
 * and only a small fraction bids, so a test that bid with every request would report a
 * bottleneck the real system never meets. Ten browsers to two bidders approximates that.
 *
 *   k6 run perf/browse-and-bid.js
 *
 * Read-only by default: with no credentials configured the bidding scenario does not start, so
 * the script is safe to point at any environment. Bidding writes real rows and must only be run
 * against a scratch or staging instance — never production.
 *
 * Environment:
 *   BASE_URL     default http://localhost:8080/online-auction
 *   BID_EMAIL    comma-separated buyer emails; enables the bidding scenario
 *   BID_PASSWORD shared password for those accounts
 *   AUCTION_ID   the auction to contend for (ascending/PRICE_UP, open, not owned by the bidders)
 *   DURATION     default 2m
 */
import http from 'k6/http';
import { check, group, sleep, fail } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://localhost:8080/online-auction';
const DURATION = __ENV.DURATION || '2m';
const AUCTION_ID = __ENV.AUCTION_ID || '';
const BID_PASSWORD = __ENV.BID_PASSWORD || '';

// Parsed once at init rather than per iteration: SharedArray keeps a single copy across VUs
// instead of one per VU, which matters as soon as the VU count is nontrivial.
const bidders = new SharedArray('bidders', () => {
  const raw = (__ENV.BID_EMAIL || '').split(',').map((s) => s.trim()).filter(Boolean);
  return raw.map((email) => ({ email, password: BID_PASSWORD }));
});

const biddingEnabled = bidders.length > 0 && BID_PASSWORD !== '' && AUCTION_ID !== '';

// Outcome counters. A bid rejected as too low is the system working correctly under contention —
// someone else got there first — so it must not be counted as an error, but it must be visible,
// because a run where every bid was rejected has not exercised the write path at all.
const bidAccepted = new Counter('bid_accepted');
const bidRejectedTooLow = new Counter('bid_rejected_too_low');
const bidRejectedRateLimit = new Counter('bid_rejected_rate_limited');
const bidFailed = new Rate('bid_unexpected_failure');

const loginTime = new Trend('t_login', true);
const searchTime = new Trend('t_search', true);
const detailTime = new Trend('t_auction_detail', true);
const bidTime = new Trend('t_place_bid', true);

const FORM = { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } };

export const options = {
  scenarios: {
    browsing: {
      executor: 'ramping-vus',
      exec: 'browse',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 10 },
        { duration: DURATION, target: 10 },
        { duration: '15s', target: 0 },
      ],
      tags: { scenario: 'browsing' },
    },
    ...(biddingEnabled
      ? {
          bidding: {
            executor: 'constant-vus',
            exec: 'bid',
            vus: Math.min(bidders.length, 5),
            duration: DURATION,
            startTime: '30s', // let the browse load establish first, so bids land on a busy system
            tags: { scenario: 'bidding' },
          },
        }
      : {}),
  },

  // The documented targets, as pass/fail gates. p(95) rather than the mean, because an average
  // hides exactly the tail the requirement is about, and a target met on average while one user
  // in twenty waits five seconds is not a target met.
  thresholds: {
    'http_req_duration{endpoint:health}': ['p(95)<2000'],
    'http_req_duration{endpoint:search}': ['p(95)<2000'],
    'http_req_duration{endpoint:detail}': ['p(95)<2000'],
    'http_req_duration{endpoint:login}': ['p(95)<1000'],
    'http_req_duration{endpoint:bid}': ['p(95)<3000'],
    http_req_failed: ['rate<0.01'],
    bid_unexpected_failure: ['rate<0.01'],
  },
};

// ── browsing: the anonymous read path ────────────────────────────────────────

export function browse() {
  group('health', () => {
    const res = http.get(`${BASE}/api/health`, { tags: { endpoint: 'health' } });
    check(res, {
      'health 200': (r) => r.status === 200,
      'health reports UP': (r) => r.json('status') === 'UP',
    });
  });

  let auctionIds = [];

  group('search', () => {
    const res = http.get(`${BASE}/api/search?page=1&size=12`, { tags: { endpoint: 'search' } });
    searchTime.add(res.timings.duration);
    const ok = check(res, {
      'search 200': (r) => r.status === 200,
      'search returns a result array': (r) => Array.isArray(r.json('results')),
    });
    if (ok) {
      auctionIds = (res.json('results') || []).map((it) => it.auctionId).filter(Boolean);
    }
  });

  // Think time between landing on results and opening one. Without it the VU behaves like a
  // scraper rather than a person, which inflates throughput and understates response times.
  sleep(1 + Math.random() * 2);

  group('auction detail', () => {
    if (auctionIds.length === 0) return;
    const id = auctionIds[Math.floor(Math.random() * auctionIds.length)];
    const res = http.get(`${BASE}/api/auction/${id}`, { tags: { endpoint: 'detail' } });
    detailTime.add(res.timings.duration);
    check(res, {
      'detail 200': (r) => r.status === 200,
      'detail has a title': (r) => !!r.json('title'),
    });
  });

  sleep(2 + Math.random() * 3);
}

// ── bidding: the authenticated write path ────────────────────────────────────

export function bid() {
  // Round-robin by iteration rather than by __VU. VU ids are allocated across the whole test,
  // not per scenario, so with the browsing scenario also running they are not guaranteed to be
  // consecutive here — two bidding VUs can land on the same account, and those two then
  // rate-limit each other rather than contending for the auction. Keying on the scenario's own
  // iteration counter also spaces each account's bids further apart than the 3-second limit.
  const account = bidders[exec.scenario.iterationInTest % bidders.length];
  const token = login(account);
  if (!token) return;

  const auth = { headers: { ...FORM.headers, Authorization: `Bearer ${token}` } };

  // Read the price, then raise it. Bidding a fixed amount would be rejected as too low from the
  // second iteration onward and the write path would stop being exercised almost immediately.
  const detail = http.get(`${BASE}/api/auction/${AUCTION_ID}`, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { endpoint: 'detail' },
  });
  if (detail.status !== 200) {
    bidFailed.add(1);
    return;
  }
  const current = Number(detail.json('currentBid') ?? detail.json('startingPrice') ?? 0);
  const amount = (current + 1 + Math.random() * 5).toFixed(2);

  const res = http.post(
    `${BASE}/api/bid`,
    { auctionId: AUCTION_ID, bidAmount: amount },
    {
      ...auth,
      tags: { endpoint: 'bid' },
      // A 400 here is the server correctly refusing a bid that lost the race or came too fast.
      // Without this, k6's default "2xx/3xx only" rule books those as failed requests and the
      // http_req_failed threshold fails a run in which the application behaved perfectly — which
      // is exactly what happened the first time this script was run for real.
      responseCallback: http.expectedStatuses(200, 400),
    },
  );
  bidTime.add(res.timings.duration);

  if (res.status === 200) {
    bidAccepted.add(1);
    bidFailed.add(0);
  } else if (res.status === 400) {
    // Expected contention outcomes, distinguished so the summary shows which guard is firing.
    const body = String(res.body || '');
    if (body.includes('too low') || body.includes('higher')) {
      bidRejectedTooLow.add(1);
    } else if (body.includes('too fast') || body.includes('wait')) {
      bidRejectedRateLimit.add(1);
    }
    bidFailed.add(0);
  } else {
    bidFailed.add(1);
  }

  // Comfortably clear of the 3-second per-(buyer, auction) rate limit, so the run measures the
  // bid path rather than the rate limiter rejecting almost every request cheaply.
  sleep(4 + Math.random() * 2);
}

function login(account) {
  const res = http.post(
    `${BASE}/api/auth/login`,
    { email: account.email, password: account.password },
    { ...FORM, tags: { endpoint: 'login' } },
  );
  loginTime.add(res.timings.duration);

  const ok = check(res, { 'login 200': (r) => r.status === 200 });
  if (!ok) {
    fail(`login failed for ${account.email}: ${res.status} ${String(res.body).slice(0, 200)}`);
  }
  const token = res.json('token');
  if (!token) {
    // A 2FA-enabled account returns a pending token instead of a session, which would otherwise
    // show up as a confusing wall of 403s on every later bid.
    fail(`no token for ${account.email} — is two-factor enabled on this account?`);
  }
  return token;
}

export function setup() {
  const res = http.get(`${BASE}/api/health`);
  if (res.status !== 200) {
    fail(`${BASE}/api/health returned ${res.status} — is the instance up and is the context path right?`);
  }
  console.log(`target: ${BASE}`);
  console.log(biddingEnabled
    ? `bidding scenario ON — ${bidders.length} account(s) contending for auction ${AUCTION_ID}`
    : 'bidding scenario OFF — set BID_EMAIL, BID_PASSWORD and AUCTION_ID to include the write path');
  return {};
}
