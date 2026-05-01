# FYP 26-S2-24 — Online Auction Platform

Course final-year project: a **Jakarta Servlet / JSP** web application for auctions, with authentication, account management, and PDPA-oriented handling of personal data.

## Tech stack

| Layer | Choice |
|--------|--------|
| Language | Java 11 |
| Web | Jakarta Servlet API, JSP, JSTL (Bootstrap 5 in views) |
| Build | Maven (`packaging: war`) |
| Database | PostgreSQL (schema in `FYP/src/main/resources/auction_db.sql`) |
| Pooling | HikariCP |

Application sources live under **`FYP/`** (artifact `online-auction`, final WAR name `online-auction.war`).

## Prerequisites

- JDK 11+
- Maven 3.9+
- PostgreSQL with a database matching JDBC settings in `com.auction.util.DBUtil` (default in code: `jdbc:postgresql://localhost:5432/auction_db`, user `postgres` — adjust for your environment)

## Database setup

1. Create the database (or use the script’s `CREATE DATABASE` if suitable for your setup).
2. Run / apply `FYP/src/main/resources/auction_db.sql`.
3. If you already had an older schema, apply any missing columns manually (e.g. `profile_image_url`, encrypted PII columns, `user_status` **Deleted**, etc.).

## Build and test

```bash
cd FYP
mvn clean package
mvn test
```

Run a subset of tests, for example:

```bash
mvn test -Dtest=TestLoginServlet,TestChangePasswordServlet
```

## Run locally

Deploy the generated `FYP/target/online-auction.war` to a Jakarta EE–compatible servlet container (e.g. Apache Tomcat 10+ for Jakarta Servlet 6).  
Configure the container and `DBUtil` so the app can reach PostgreSQL.

Servlets use `@WebServlet` / `@WebFilter` annotations where defined; ensure your server scans annotated classes (default on recent Tomcat).

## Project layout (high level)

```
FYP/
  src/main/java/com/auction/
    servlet/          # Login, register, profile, password change, logout, …
    filter/           # Security headers, auth for /protected/*
    dao/              # UserDAO, …
    model/            # User, Role, Status, …
    util/             # SecurityUtil, InputValidator, DBUtil, …
  src/main/webapp/
    WEB-INF/views/    # JSP account pages, …
    WEB-INF/includes/
docs/
  sequence-diagrams/  # PlantUML (e.g. SCRUM-7 … SCRUM-12)
```

## Security notes (summary)

- Passwords at rest: **salted SHA-256** via `SecurityUtil.hashPassword` / `verifyPassword`.
- Sensitive profile fields (e.g. phone, address): **AES-GCM** via `SecurityUtil.encrypt` / `decrypt` before persistence.
- Public-facing strings: masking helpers (`maskEmail`, `maskUsername`, `maskPhone`) where applicable.
- `DBUtil` uses placeholder credentials — **do not use as-is in production**; use environment-specific config or JNDI.

## Documentation

UML-style flows: see `docs/sequence-diagrams/*.puml` (render with [PlantUML](https://plantuml.com/) or your IDE plugin).

## License / course

Internal FYP repository — use and distribution terms are defined by your institution and team.
