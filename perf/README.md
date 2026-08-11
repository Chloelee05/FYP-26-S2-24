# Performance and concurrency testing

Verification for the non-functional requirements in section 6.1 of the technical documentation.
Until now those targets were stated but never measured, and the documentation correctly listed
"no multi-threaded load test" as a known limitation. This directory is what closes that.

There are two distinct questions here, and they need different tools:

| Question | Tool | Where |
| --- | --- | --- |
| Is bidding **correct** when bids collide? | JUnit + a real PostgreSQL | `BidDAOConcurrencyIntegrationTest` |
| Is the system **fast enough** under load? | k6 | `perf/browse-and-bid.js` |

Correctness first. A system that is fast but sells one item to two buyers is worse than a slow
one, and a load test cannot detect that — it sees two HTTP 200s and reports success.

---

## 1. Concurrency correctness

`FYP/src/test/java/com/auction/dao/BidDAOConcurrencyIntegrationTest.java`

`BidDAO.placeBid` protects the auction row with `SELECT … FOR UPDATE` so that two bids arriving
together cannot both win (SCRUM-265). Every other test of that method mocks the JDBC connection,
which cannot exercise a lock: there is no row to lock and no second thread to block. This test
fires eight bidders at one auction simultaneously, twenty times over, and asserts the invariants
that only hold if the lock is real:

- identical simultaneous bids → exactly one accepted, every round;
- accepted bids, read in insert order, strictly increase — the price never goes backwards;
- rows in `bids` == successes reported by `placeBid` — nothing lost, nothing invented;
- the auction is led by the highest bid, not by whoever committed last;
- one buyer racing itself is rate-limited rather than double-counted.

### Running it

Skipped by default so `mvn test` needs no database. Point it at a scratch database — never the
hosted one, which the test refuses outright:

```bash
# one-time: build a throwaway database
createdb -U postgres auction_it_scratch
tail -n +15 FYP/src/main/resources/auction_db.sql \
  | psql -U postgres -v ON_ERROR_STOP=1 -d auction_it_scratch          # skips the CREATE DATABASE header
psql -U postgres -v ON_ERROR_STOP=1 -d auction_it_scratch \
  -f FYP/src/main/resources/db/migrate_all.sql

# run
cd FYP
AUCTION_DB_IT=true \
AUCTION_DB_URL="jdbc:postgresql://localhost:5432/auction_it_scratch" \
AUCTION_DB_USER=postgres AUCTION_DB_PASSWORD="" \
mvn -B test -Dtest=BidDAOConcurrencyIntegrationTest
```

The first line of `auction_db.sql` is a `CREATE DATABASE` carrying a Windows locale
(`English_Singapore.1252`) that a macOS or Linux PostgreSQL rejects, which is why the schema is
piped from line 15 onward into a database created separately.

### Confirming the test can actually fail

A concurrency test that passes proves nothing on its own — it may simply never have triggered the
race. This one was validated by deleting `FOR UPDATE` from `BidDAO.placeBid` and re-running:

| Version | Result |
| --- | --- |
| `FOR UPDATE` present | 4 tests pass, repeatably |
| `FOR UPDATE` removed | 3 of 4 fail in round 0 — *"3 of 8 bidders offering the same amount were accepted"*, *"bid 151.00 was accepted after 152.00, so the price went down"* |

This is also why the races repeat twenty times. An earlier single-round version passed even with
the lock deleted: the window between reading the floor and inserting is under a millisecond, so
eight threads usually serialise by luck. Repetition is what turns "might catch it" into "does".

### Measured result

`placeBid` latency under 8-way contention, 5 rounds, 40 samples, local PostgreSQL 
(Apple Silicon, PostgreSQL on `localhost`):

| Metric | Measured | NFR 6.1 target |
| --- | --- | --- |
| Median | 2.2 ms | — |
| p95 | 5.3 – 8.0 ms | — |
| Max | 6.9 – 11.3 ms | < 3000 ms |

Three consecutive runs. The target is met with roughly three orders of magnitude of headroom, so
the lock is not a throughput problem at this scale. Note this measures the DAO against a local
database — it excludes HTTP, servlet dispatch and network latency, which is what section 2 adds.

---

## 2. Load test

`perf/browse-and-bid.js`, run with [k6](https://k6.io) (`brew install k6`).

Browse-heavy by design — ten browsing VUs to at most five bidders — because on a C2C marketplace
most traffic reads listings and only a fraction bids. A test that bid on every request would
report a bottleneck the real system never encounters.

### Running it

Read-only by default, and safe to point at any environment:

```bash
k6 run -e BASE_URL=http://localhost:8080/online-auction perf/browse-and-bid.js
```

Including the write path. **Scratch or staging only — this writes real bids:**

```bash
k6 run \
  -e BASE_URL=http://localhost:8080/online-auction \
  -e BID_EMAIL=buyer1@test.local,buyer2@test.local \
  -e BID_PASSWORD='Auction#2026' \
  -e AUCTION_ID=42 \
  perf/browse-and-bid.js
```

`AUCTION_ID` must be an open ascending (PRICE_UP) auction that none of the bidder accounts owns,
and those accounts must not have two-factor enabled — the script fails loudly on both rather than
producing a wall of 403s.

### Thresholds

The section 6.1 targets are encoded as k6 thresholds, so **the run fails if a target is missed**
rather than printing numbers for someone to interpret generously:

| Endpoint | Threshold | Source |
| --- | --- | --- |
| `/api/health` | p95 < 2000 ms | page load |
| `/api/search` | p95 < 2000 ms | search / listing query |
| `/api/auction/:id` | p95 < 2000 ms | page load |
| `/api/auth/login` | p95 < 1000 ms | authentication |
| `/api/bid` | p95 < 3000 ms | place bid (transactional) |
| any request | failure rate < 1% | — |

p95 rather than mean throughout: an average hides the tail the requirement is about, and a target
met on average while one user in twenty waits five seconds is not a target met.

Bids rejected as too low are counted separately (`bid_rejected_too_low`) and are **not** errors —
under contention they are the system behaving correctly. They are counted because a run where
every bid was rejected never exercised the write path, and would otherwise look like a pass.

### Results — first run

| Field | Value |
| --- | --- |
| Date | 10 August 2026 |
| Target environment | local (embedded Tomcat 10 via `mvn cargo:run`, PostgreSQL on `localhost`) |
| Duration and VUs | 10 browsing VUs for 1m45s; 5 bidding VUs for 60s, contending for one auction |
| Requests | 658, of which 63 were bids |
| k6 version | v2.2.0 |

| Metric | p95 measured | Target | Pass? |
| --- | --- | --- | --- |
| `/api/health` | 1.97 ms | < 2000 ms | PASS |
| `/api/search` | 4.26 ms | < 2000 ms | PASS |
| `/api/auction/:id` | 5.98 ms | < 2000 ms | PASS |
| `/api/auth/login` | 5.85 ms | < 1000 ms | PASS |
| `/api/bid` | 10.03 ms | < 3000 ms | PASS |
| Request failure rate | 0.00% | < 1% | PASS |

Bids accepted: 60  rejected as too low: 3  rate-limited: 0  unexpected failures: 0

**Observations.** Every section 6.1 target is met with two to three orders of magnitude of
headroom at this scale, and no request failed. The three rejected bids are the expected result of
five bidders contending for one auction — someone else raised the price first — and the write path
was genuinely exercised: the auction accumulated 99 bids and rose from its 500.00 opening to
935.33 over the run.

Two caveats on reading these numbers. They are from a local run, so they contain no real network
latency and no hosting-proxy overhead; the deployed figures will be higher, and the run should be
repeated against the deployed instance before the numbers are quoted as production performance.
And 10 VUs is a demonstration load, not a capacity study — this shows the targets are met, not
where the system breaks.

### Two script defects this run found

Recorded because "we wrote a load test" and "we ran one" are different claims, and the difference
is usually a script that was never executed:

1. **Business rejections counted as request failures.** k6 treats any non-2xx as a failed request,
   so the 400 that correctly refuses a too-low bid pushed `http_req_failed` to 3.80% and failed
   the run while the application was behaving perfectly. Fixed with a per-request
   `responseCallback` accepting 200 and 400 on the bid endpoint.
2. **Bidder accounts collided.** Accounts were selected by `__VU`, but k6 allocates VU ids across
   the whole test rather than per scenario, so with the browsing scenario also running two bidding
   VUs could share one account and rate-limit each other instead of contending. 22 of 61 bids were
   being rejected as too fast. Fixed by round-robin on the scenario's own iteration counter;
   accepted bids went from 37 to 60 and rate-limited rejections to zero.

---

## Known limits of this testing

State these rather than leaving them implied — they are the difference between "we load tested"
and a claim the numbers do not support.

- **Single instance.** Both tests run against one application instance. Horizontal scaling and
  SSE fan-out across instances are untested and remain future work.
- **Modest concurrency.** Eight concurrent bidders in the JUnit test, capped by the ten-connection
  Hikari pool, and ten browsing VUs in k6. This is enough to prove the lock is correct and the
  targets are met at demonstration scale; it is not a capacity study and does not establish a
  ceiling.
- **The DAO test excludes the network.** Section 1 measures database work only. The end-to-end
  figure a user actually experiences is the one in section 2.
- **No sustained soak.** Runs are minutes, not hours, so connection-pool exhaustion and memory
  leaks under prolonged load would not be detected.
