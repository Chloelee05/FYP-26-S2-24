# FYP 26-S2-24 — Online Auction Platform

Course final-year project: an online auction marketplace (AuctionHub) built as a **Jakarta Servlet / JSP** backend with a **React** SPA. Covers bidding and listings, unified buyer/seller accounts, personalised recommendations, orders/shipping, Telegram alerts, and PDPA-oriented handling of personal data.

**Deployed demo:** [https://fyp-26-s2-24.onrender.com/online-auction](https://fyp-26-s2-24.onrender.com/online-auction)

## Tech stack

| Layer | Choice |
|--------|--------|
| Backend | Java 11 servlets/DAO, packaged as a WAR, run on Tomcat 10 via the Cargo Maven plugin. JSON API under `/api/*` |
| Frontend | React 19 + Vite (SPA) styled with Tailwind CSS (`FYP/Frontend/`) |
| Build | Maven (`packaging: war`); production image builds frontend then merges `dist/` into the WAR (`Dockerfile`) |
| Database | PostgreSQL — base schema `FYP/src/main/resources/auction_db.sql`, incremental migrations in `FYP/src/main/resources/db/` |
| Pooling | HikariCP |

Application sources live under **`FYP/`** (artifact `online-auction`, context path `/online-auction`, WAR `online-auction.war`).

## Key features

- Auctions, search, bidding, auto-bid, watchlist, buy-it-now
- Unified buyer/seller accounts via `can_sell` (enable selling without a separate seller login)
- Hybrid recommendation pipeline with per-card explainability, admin-tunable `recommendation_settings`, and per-arm CTR metrics
- Admin-editable landing-page copy (`landing_content`)
- Orders with shipping/refund alerts; optional Telegram bot notifications (link account, bid/order/seller alerts)
- Auth (session, 2FA, Google OAuth when configured), account management, admin moderation/analytics

## Prerequisites

- JDK 11+
- Maven 3.9+
- PostgreSQL (database name typically `auction_db`)
- Node.js 18+ and npm 9+ (React / Vite)

## Environment variables (names only)

Set these before `mvn cargo:run` or in the deploy environment. **Do not commit real secrets.**

| Variable | Purpose |
|----------|---------|
| `AUCTION_DB_URL` | JDBC URL (default local: `jdbc:postgresql://localhost:5432/auction_db`) |
| `AUCTION_DB_USER` | DB user (default `postgres`) |
| `AUCTION_DB_PASSWORD` | DB password |
| `AUCTION_AES_SECRET` | AES-GCM key material for encrypted PII |
| `AUCTION_PUBLIC_BASE_URL` | Public site URL used in notification links |
| `AUCTION_UPLOAD_DIR` | Optional upload directory (useful on Render) |
| `GOOGLE_CLIENT_ID` | Optional Google sign-in |
| `TELEGRAM_BOT_TOKEN` | Optional Telegram bot |
| `TELEGRAM_BOT_USERNAME` | Bot handle (no `@`) |
| `TELEGRAM_WEBHOOK_SECRET` | Webhook auth secret |
| `AUCTION_TELEGRAM_PEPPER` | Pepper for Telegram chat-id hashing |
| `AUCTION_SMTP_*` / `AUCTION_MAIL_*` | Optional SMTP for password-reset mail (`MailConfig`) |

Telegram and SMTP features stay off when their required vars are unset.

## Database setup

1. Create PostgreSQL database `auction_db` (or match your JDBC URL).
2. Apply the base schema: `FYP/src/main/resources/auction_db.sql`.
3. Apply incremental migrations (safe to re-run):

```bash
psql -U postgres -h localhost -p 5432 -d auction_db -f FYP/src/main/resources/db/migrate_all.sql
```

`migrate_all.sql` includes seller capability, landing content, recommendation, orders/shipping, Telegram, and related scripts under `FYP/src/main/resources/db/`.

## Build and test

```bash
cd FYP
mvn clean package
mvn test
```

Subset example:

```bash
mvn test -Dtest=TestLoginServlet,TestChangePasswordServlet
```

Frontend unit tests:

```bash
cd FYP/Frontend
npm install      # first time only
npm test
```

## Run locally (Mac / local)

Terminal 1 — backend (Tomcat 10 via Cargo, port 8080, context `/online-auction`):

```bash
cd FYP
mvn package -DskipTests cargo:run
```

Terminal 2 — frontend (Vite on port 3000; proxies `/api` to the WAR):

```bash
cd FYP/Frontend
npm install      # first time only
npm run dev
```

- SPA (dev): `http://localhost:3000`
- WAR directly: `http://localhost:8080/online-auction`

You can also deploy `FYP/target/online-auction.war` to Tomcat 10+ yourself. Servlets use `@WebServlet` / `@WebFilter`; ensure annotation scanning is enabled (default on recent Tomcat).

## Deploy

Production uses the multi-stage `Dockerfile` (Node frontend build → Maven WAR → Tomcat 10). The live Render service is served at `/online-auction`. Configure the env vars above in the host; do not bake secrets into the image.

## Project layout (high level)

```
FYP/
  Frontend/                 # React 19 + Vite + Tailwind SPA
  src/main/java/com/auction/
    servlet/                # JSP-oriented servlets + servlet/api JSON API
    filter/                 # Security headers, auth
    dao/                    # Persistence (users, auctions, recommendations, …)
    model/
    telegram/               # Bot config, outbox, alert copy
    util/                   # SecurityUtil, DBUtil, MailConfig, …
    notification/ realtime/ listener/
  src/main/resources/
    auction_db.sql          # Base schema
    db/                     # migrate_all.sql + incremental migrations
  src/main/webapp/          # JSP views, static assets packaged in the WAR
Dockerfile                  # Render / container production build
docs/
  sequence-diagrams/        # PlantUML flows
  FYP-Preliminary-Technical-Document.md
  FYP-Preliminary-User-Manual.md
```

## Security notes (summary)

- Passwords at rest: **salted SHA-256** via `SecurityUtil.hashPassword` / `verifyPassword`.
- Sensitive profile fields (e.g. phone, address): **AES-GCM** via `SecurityUtil.encrypt` / `decrypt`; set `AUCTION_AES_SECRET` in every non-dev environment.
- Public-facing strings: masking helpers (`maskEmail`, `maskUsername`, `maskPhone`) where applicable.
- Configure DB and crypto via environment variables — do not rely on local development fallbacks in production.

## Documentation

- Technical detail (including the recommendation pipeline): `docs/FYP-Preliminary-Technical-Document.md`
- UML-style flows: `docs/sequence-diagrams/*.puml` (render with [PlantUML](https://plantuml.com/) or your IDE plugin)

## License / course

Internal FYP repository — use and distribution terms are defined by your institution and team.
