# Quick Start

From zero to a working `SELECT` against Fusion in five minutes.

## 1. Prerequisites

- macOS (Apple Silicon), Windows (x86_64), or Linux (x86_64).
- Chrome / Chromium on `PATH` (only needed for SSO auth mode).
- A Fusion Cloud tenant with the `RP_ARB.xdo` BI Publisher report deployed (download from [krokozyab/ofjdbc/otbireport](https://github.com/krokozyab/ofjdbc/tree/master/otbireport)) — typically under `/Custom/sql/RP_ARB.xdo` or `/Custom/Financials/RP_ARB.xdo`.
- At least one PG-wire client: `psql`, DBeaver, or anything else from the [client recipes](clients.md).

## 2. Get the artefacts

Drop into an empty working directory:

- `ofpgproxy` — the binary (`chmod +x` on macOS/Linux).
- `metadata.db` — pre-built Fusion catalog (~30 MB, DuckDB format).
- Optional: `.env.example` — copy to `.env` and fill in.

## 3. Configure

Create `.env` alongside the binary:

```
# Tenant
FUSION_HOST=fa-xxxx.oraclecloud.com
FUSION_SQL_REPORT_PATH=/Custom/sql/RP_ARB.xdo

# Auth mode: sso | password | token-file
FUSION_AUTH_TYPE=sso

# Only needed for FUSION_AUTH_TYPE=password
# FUSION_USER=your.user
# FUSION_PASSWORD=...
```

Full reference: [Configuration](configuration.md) · [Authentication](auth.md)

## 4. Launch

```bash
set -a; source .env; set +a
./ofpgproxy --metadata-path ./metadata.db
```

Expected output:

```
time=... level=INFO msg="pg_catalog emulation enabled, reading ./metadata.db (SIGHUP reloads)"
time=... level=INFO msg="SSO: opening Chrome for Fusion login (300s timeout)"
time=... level=INFO msg="ofpgproxy listening on 127.0.0.1:5433"
```

On first SSO run the Chrome window points at your IdP — log in as you normally would. The captured token is held in-process until the proxy exits.

## 5. Run your first query

### psql

```bash
psql -h localhost -p 5433 -U anyone -d any
```

```sql
SELECT period_name, period_year
FROM   gl_periods
WHERE  period_year = 2024
LIMIT  5;
```

Username and database are placeholders — the proxy accepts any values.

### DBeaver

1. New Connection → PostgreSQL (built-in driver).
2. Host `127.0.0.1`, Port `5433`, Database `any`, User / Password any.
3. **Driver properties** → `preferQueryMode` = `simple`. (Optional but avoids binary-format parameter edge cases.)
4. Test connection, finish.
5. Schema tree: tables appear under `public` (or per-module schemas — `fscm`, `hcm`, `crm` — when the module is present in `metadata.db`).
6. Double-click any table → rows stream into the result grid.

### Other clients

- [postgres_fdw](clients.md#postgres_fdw) — expose Fusion tables inside another PG
- [dblink](clients.md#dblink) — ad-hoc cross-database queries
- [pgx / psycopg / pgJDBC](clients.md#code-clients) — code integration

## What's next

- **[SQL compatibility](sql-compat.md)** — see which PG idioms get auto-rewritten and what edge cases to watch for.
- **[Troubleshooting](troubleshooting.md)** — for the first time you hit an `ORA-…` message.
- **[Configuration](configuration.md)** — every flag and env var the proxy honours.
