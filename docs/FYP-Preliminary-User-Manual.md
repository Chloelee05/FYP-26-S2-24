# Preliminary User Manual

| | |
|---|---|
| **Document title** | Preliminary User Manual |
| **Project name** | Online Auction Platform (AuctionHub) |
| **Repository** | FYP-26-S2-24 |
| **Document version** | 1.0 (Preliminary) |
| **Date** | June 2026 |
| **Team** | [VERIFY: team name] |

> **About this manual.** AuctionHub is a web-based online auction platform deployed on a Java application server (Apache Tomcat) with a PostgreSQL database. This manual covers minimum system requirements, administrator installation/maintenance, and step-by-step usage guides for each stakeholder (public visitor, buyer, seller, administrator). Functional/non-functional requirements are documented separately.

---

## Table of Contents

1. [Minimum System Requirements](#1-minimum-system-requirements)
2. [System Administrator — Installation & Maintenance Guide](#2-system-administrator--installation--maintenance-guide)
3. [User (Usage) Guide](#3-user-usage-guide)
4. [Troubleshooting for End Users](#4-troubleshooting-for-end-users)
5. [Site Map & Navigation Reference](#5-site-map--navigation-reference)

---

## 1. Minimum System Requirements

AuctionHub is a **web application** accessed through a browser. There is no installer or mobile app; end users only need a modern browser and network access to the server.

### 1.1 Client (end-user) requirements

| Item | Requirement |
|------|-------------|
| **Browser** | Recent Google Chrome, Microsoft Edge, or Mozilla Firefox (current or previous major version). Any evergreen browser supporting ES6 JavaScript and CSS Grid/Flexbox. [VERIFY: confirm Safari support if required] |
| **Browser features** | JavaScript **enabled** (used for the bid-confirmation modal, countdown timer, image switcher, client-side validation); **cookies enabled** (required for the login session cookie `JSESSIONID`) |
| **Add-ons** | None required |
| **Screen resolution** | Responsive (Bootstrap 5). Minimum ~**1280 × 720** recommended for comfortable use of admin/seller data tables; usable on smaller/mobile widths |
| **Internet access** | Required. The UI loads Bootstrap 5.3.3 and bootstrap-icons 1.11.3 from the **jsDelivr CDN**; access to the application server (LAN or hosted URL) is also required. [VERIFY: bundle assets locally if offline demo is needed] |
| **Bandwidth** | No special requirement; standard broadband is sufficient (no streaming/real-time media) |

### 1.2 Server-side requirements

| Item | Requirement |
|------|-------------|
| **JDK** | Java **11+** (the project compiles to Java 11 bytecode; JDK 17 may be used to run the server) |
| **Build tool** | Apache **Maven 3.9+** |
| **Application server** | Apache **Tomcat 10.1+** (Jakarta Servlet 6.x) |
| **Database** | **PostgreSQL** (reachable via JDBC; default `jdbc:postgresql://localhost:5432/auction_db`) |
| **Connection pool** | HikariCP (bundled as a dependency) |
| **Optional — email** | An SMTP server for password-reset OTP delivery. If not configured, the OTP is logged/shown on-screen for development. |

### 1.3 External hardware

No special external hardware (e.g. webcam, biometric reader, high-capacity storage) is required.

---

## 2. System Administrator — Installation & Maintenance Guide

This section is for the person deploying and maintaining AuctionHub.

### 2.1 Install prerequisites

1. Install **JDK 11+** and set `JAVA_HOME`.
2. Install **Apache Maven 3.9+** and ensure `mvn` is on the `PATH`.
3. Install **PostgreSQL** and ensure the server is running.
4. Install **Apache Tomcat 10.1+** and note its install directory (`CATALINA_HOME`).

### 2.2 Create and initialise the database

1. Create the database (default name `auction_db`):

```sql
CREATE DATABASE auction_db;
```

2. Apply the base schema:

```bash
psql -U postgres -d auction_db -f FYP/src/main/resources/auction_db.sql
```

3. Apply migration scripts under `FYP/src/main/resources/db/` as needed (for a fresh install, apply the feature migrations that add tables/columns not in the base schema):

```text
migration_categories.sql        -- categories table (+ 7 seed categories)
migration_auto_bids.sql         -- auto_bids table
migration_auction_questions.sql -- auction_questions table
migration_seller_features.sql   -- starting_price / max_price / cancel_reason
migration_seller_reports.sql    -- seller_reports table
migration_watchlist.sql         -- watchlist table
migration_search_index.sql      -- search indexes
-- (migration_admin_moderation.sql / migration_user_reviews.sql / migration_seller_ratings.sql
--  are only needed for older databases; the current auction_db.sql already includes these.)
```

4. **[VERIFY] Seed lookup tables.** `roles`, `user_status`, and `categories` are seeded by the scripts, but `auction_status`, `auction_type`, and `item_status` are **not** seeded by any SQL file. Insert their rows before creating auctions, for example:

```sql
INSERT INTO auction_status(status) VALUES ('Active'),('Finished'),('Cancelled'),('Pending');
INSERT INTO auction_type(type)     VALUES ('PriceUp'),('Dutch'),('Blind');
INSERT INTO item_status(item_condition) VALUES ('Brand New'),('Slightly Used'),('Used'),('Damaged');
-- [VERIFY exact label spelling/order expected by the application enums]
```

### 2.3 Configure database connectivity

The JDBC URL, username and password are defined in `com.auction.util.DBUtil`. Adjust them to match your environment (host, port, database, credentials).

> **Security note.** `DBUtil` ships with placeholder credentials and `SecurityUtil` uses a placeholder AES key. **Do not use these as-is outside development.** Externalise secrets via environment variables / a keystore before any shared or production deployment.

### 2.4 Build the application

```bash
cd FYP
mvn clean package
```

This produces `FYP/target/online-auction.war`.

### 2.5 Deploy to Tomcat

1. Stop Tomcat.
2. Copy the WAR into Tomcat's `webapps/`:

```bash
copy FYP\target\online-auction.war  %CATALINA_HOME%\webapps\
```

3. (If redeploying) remove any stale exploded directory `webapps/online-auction/` while Tomcat is stopped.
4. Start Tomcat.
5. Browse to the application (default context path = WAR name):

```text
http://localhost:8080/online-auction/
Login:    http://localhost:8080/online-auction/login
Register: http://localhost:8080/online-auction/register
```

### 2.6 Configure password-reset email (optional)

Set these environment variables on the **Tomcat process** before startup (e.g. in `setenv.bat`/`setenv.sh`):

| Variable | Meaning | Default |
|----------|---------|---------|
| `AUCTION_SMTP_HOST` | SMTP host (enables real email) | *(unset → OTP shown on-screen)* |
| `AUCTION_SMTP_PORT` | SMTP port | `587` |
| `AUCTION_SMTP_USER` | SMTP username | — |
| `AUCTION_SMTP_PASSWORD` | SMTP password / app password | `""` |
| `AUCTION_MAIL_FROM` | From address | `noreply@auctionhub.local` |
| `AUCTION_SMTP_STARTTLS` | STARTTLS on/off | `true` |
| `AUCTION_SMTP_SSL` | Implicit SSL (e.g. port 465) | `false` |

If `AUCTION_SMTP_HOST` is unset, the 6-digit OTP is shown in-page (development behaviour) and also logged.

### 2.7 Tomcat troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| **HTTP 400 "Request header is too large"** on every page/button | Browser cookie header for `localhost` exceeds Tomcat's default `maxHttpHeaderSize` (8 KB) | In `conf/server.xml`, add `maxHttpHeaderSize="65536"` to the HTTP `<Connector>` and restart; and/or clear `localhost` cookies in the browser |
| Stale pages / odd 404/500 after redeploy | Old exploded WAR directory lingering | Stop Tomcat, delete `webapps/online-auction/`, copy fresh WAR, start |
| 500 on JSP pages | Missing JSTL implementation / DB down | Confirm dependencies built into WAR; confirm PostgreSQL reachable |
| Login works but protected pages redirect to login | Session/cookie blocked | Ensure cookies enabled; check `JSESSIONID` is set |

### 2.8 Maintenance

- **Logs:** Tomcat logs under `%CATALINA_HOME%/logs/` (`catalina.*`, `localhost_access_log.*`). Application logs use `java.util.logging`.
- **Database backup:** use `pg_dump`:

```bash
pg_dump -U postgres auction_db > auction_db_backup.sql
```

- **Restore:** `psql -U postgres -d auction_db -f auction_db_backup.sql`.
- **Redeploy:** rebuild WAR (§2.4) → stop Tomcat → replace WAR → start Tomcat.

---

## 3. User (Usage) Guide

Each subsection lists step-by-step workflows with the **expected outcome**. URLs below omit the context path prefix (e.g. `/online-auction`).

### 3.0 Roles at a glance

| Role | Can do |
|------|--------|
| **Public visitor** | Search, view auctions, view bid history, view seller profiles, register/login |
| **Buyer** | Everything public + bid, auto-bid, watchlist, ask questions, rate/report sellers, manage own account |
| **Seller** | Create/edit/cancel auctions, manage seller dashboard, reply to questions, rate buyers |
| **Administrator** | Moderate users, listings and categories; view dashboard/analytics |

### 3.1 Public visitor

**Search for auctions**
1. From the home page, type a keyword in the navbar search box and press the search icon (`GET /search`).
2. *(Optional)* Use the left **Filters** panel (price min/max, condition, location, ending window) and **Apply Filters**.
3. *(Optional)* Change the **Sort** dropdown (Newly listed / Ending soonest / Price low→high / Price high→low).
4. **Expected:** A grid of auction cards with pagination; an empty-state message if nothing matches.

**View an auction**
1. Click **View** on any result card → `/auction/{id}`.
2. **Expected:** Image gallery, current bid, countdown, description, **Bid History** table, and **Q&A** section. Bidder names in history are masked (current leader partially, others fully).

**View full bid history**
1. On the auction page, click **Full list** in the Bid History section → `/auction-bids?auctionId={id}`.
2. **Expected:** A paginated table of all bids (amount, masked bidder, time), newest first.

**View a seller's public profile**
1. Click the seller name ("Sold by …") on an auction → `/seller/{id}`.
2. **Expected:** Seller's masked email, member-since, average rating, active-listing count, and paginated reviews.

### 3.2 Buyer

**Register**
1. Go to `/register`, choose **Buy Items** (Buyer), fill name, email, password (meeting the policy), confirm, accept terms, **Create Account**.
2. **Expected:** Account created; proceed to login.

**Log in / out**
1. `/login` → enter email + password → **Login** (optionally **Remember me**).
2. **Expected:** Redirect to your account dashboard (`/protected/account`); navbar shows your masked username.
3. To log out, click **Log out**. **Expected:** Session ends; protected pages now require login.

**Place a bid**
1. Open an auction (`/auction/{id}`); ensure it is open and you are not the seller.
2. Enter an amount greater than the current bid → **Place Bid** → confirm in the modal.
3. **Expected:** Success message and updated current bid. Errors (too low, exceeds max price, auction ended) appear as a red flash.

**Set or cancel an auto-bid (proxy bidding)**
1. On the auction page, expand **Set Maximum Auto-Bid**.
2. Enter your maximum amount (and an optional private note) → **Activate Auto-Bid**. The system will bid on your behalf up to that ceiling.
3. To stop, click **Cancel auto-bid**.
4. **Expected:** Confirmation flash; your ceiling is stored encrypted and never shown publicly.

**Watchlist**
1. Add an auction to your watchlist (watchlist action) → `/protected/watchlist`.
2. **Expected:** The auction appears in your watchlist; you cannot watch your own auction; duplicates are prevented. *(Note: the watchlist page view is pending — see Technical Document §3.8.)* [VERIFY]

**Ask a question**
1. On an open auction you do not own, use **Ask a question** → type → **Submit Question**.
2. **Expected:** Your (masked) question appears in the Q&A thread; the seller can reply.

**View your bidding history**
1. Navigate to `/protected/bidding-history`.
2. **Expected:** Your bids across auctions with status. *(View page pending — see Technical Document §3.8.)* [VERIFY]

**Rate or report a seller**
1. After an eligible auction, submit a 1–5 rating (rate-seller) or a report (report) from the auction context.
2. **Expected:** One rating per auction; self-rating/duplicate/self-report are blocked.

**Manage your account**
1. `/protected/account` shows your profile, ratings and transaction history.
2. **Edit profile** (`/protected/account/edit`) to update display name, email, phone, address, profile image URL → **Save**. Phone/address are stored encrypted.
3. **Change password** (`/protected/account/password`). **Expected:** You are logged out and must sign in again.
4. **Delete account** (Danger zone → confirm). **Expected:** PII is anonymised, status set to Deleted, session ends.

### 3.3 Seller

**Create an auction**
1. As a seller, open the create-auction page (`/create-auction`).
2. Enter title, description, category, condition, prices (starting/optional max), dates, images and tags → submit.
3. **Expected:** Auction created; redirect to the new auction page.

**Edit an auction**
1. Open edit-auction (`/seller/edit-auction`) for an auction with **no bids yet**.
2. Update title/description/images → save.
3. **Expected:** Changes saved; editing is blocked once bids exist. *(View page pending — see Technical Document §3.8.)* [VERIFY]

**Cancel an auction**
1. From the seller dashboard, cancel an ACTIVE/PENDING auction with a reason (`/seller/cancel-auction`).
2. **Expected:** Auction status becomes cancelled.

**Seller dashboard**
1. Go to `/protected/seller/auctions`; filter by status (active/pending/finished/cancelled); paginate.
2. **Expected:** Your auctions with prices, bid counts and dates. *(View page pending — see Technical Document §3.8.)* [VERIFY]

**Reply to questions**
1. On your auction's Q&A section, type a reply under an unanswered question → **Post Reply**.
2. **Expected:** Reply is shown with a timestamp; you can only reply to your own auctions' questions.

**Rate a buyer**
1. After a finished auction, submit a 1–5 rating for the winning buyer (`/protected/seller/rate-buyer`).
2. **Expected:** Rating stored; one per auction.

### 3.4 Administrator

**Log in**
1. Log in with an admin account at `/login`.
2. **Expected:** Redirect to `/admin/dashboard`.

**Dashboard**
1. `/admin/dashboard` shows KPI cards (users, active listings, flagged items, revenue), preview tables and a recent-activity feed.

**Moderate users (ban / unban)**
1. `/admin/users` → use **Ban** or **Unban** on a non-admin user.
2. **Expected:** Status changes with a confirmation flash. You cannot ban/unban an admin or your own account; banning an already-banned user (or unbanning an active/deleted one) is rejected.

**Moderate listings**
1. `/admin/listings` → **Flag**, **Remove**, or **Restore** a listing.
2. **Expected:** The listing's moderation state updates (`active`/`flagged`/`removed`).

**Manage categories**
1. `/admin/categories` → **Create** / **Edit** / **Deactivate** / **Reactivate** categories.
2. **Expected:** Duplicate names/slugs are rejected; a category referenced by auctions cannot be hard-deleted (soft-delete only).

**Analytics**
1. `/admin/analytics` → view analytics. *(Placeholder page.)* [VERIFY: confirm scope]

---

## 4. Troubleshooting for End Users

| Problem | Likely cause | What to do |
|---------|--------------|------------|
| "Invalid email or password" at login | Wrong credentials | Re-enter; use **Forgot password?** to reset |
| Account "suspended"/"no longer available" | Admin suspended or account deleted | Contact the administrator |
| Cannot see the bid form | Not logged in, you are the seller, or auction ended | Log in as a buyer on someone else's open auction |
| Bid rejected | Amount too low, above max price, or auction closed | Enter a higher valid amount on an open auction |
| Logged out unexpectedly | Session expired or password changed | Log in again |
| Page shows **HTTP 400 "Request header is too large"** | Accumulated browser cookies on the server host | Clear cookies for the site / use a private window; ask the admin to raise Tomcat `maxHttpHeaderSize` |
| Password-reset email not received | SMTP not configured (dev mode) | The OTP is shown on-screen / in server logs in development |
| Styling looks broken | CDN blocked / offline | Ensure internet access to `cdn.jsdelivr.net` |

---

## 5. Site Map & Navigation Reference

### 5.1 Site map

```
Public
├── /                         Landing page
├── /search                   Search results
├── /auction/{id}             Auction detail (bid, Q&A, history)
├── /auction-bids?auctionId=  Full bid history
├── /seller/{id}              Public seller profile
├── /login  /register
└── /forgot-password  /reset-password

Authenticated (/protected/*)
├── /protected/account                 Account dashboard
│   ├── /edit         Edit profile
│   ├── /update       (save profile)
│   ├── /password     Change password
│   └── /delete       Delete account
├── /protected/bid                     Place bid
├── /protected/auto-bid                Set/cancel auto-bid
├── /protected/watchlist               Watchlist        [view pending]
├── /protected/bidding-history         My bids          [view pending]
├── /protected/auction-question        Ask/reply Q&A
├── /protected/rate-seller             Rate seller
├── /protected/report                  Report seller
└── /protected/seller/
    ├── auctions      Seller dashboard  [view pending]
    └── rate-buyer    Rate buyer
Seller (public-prefixed)
├── /create-auction
├── /seller/edit-auction               [view pending]
└── /seller/cancel-auction

Admin (/admin/*)
├── /admin/dashboard
├── /admin/users   /admin/users/action
├── /admin/listings
├── /admin/categories
└── /admin/analytics
```

### 5.2 Navigation bars

- **Public navbar (`home-navbar.jsp`):** brand, Explore, Sell Items, Help, search box, sign-in/account, category pills.
- **Account navbar (`navbar.jsp`):** Home, My Account, Admin (admins only), masked username + Logout.
- **Admin sidebar (`admin-sidebar.jspf`):** Overview, User Moderation, Listing Moderation, Categories, Analytics, Account, Log out.

> **Items marked "[view pending]"** have working controllers and database logic but their JSP pages are not yet present; they are excluded from the recommended demo path until completed (see Technical Document §3.8).

---

*End of Preliminary User Manual v1.0.*
