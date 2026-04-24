# Copy-paste recipes

Working SQL scripts you can drop into `duckdb` / DBeaver / pgAdmin and run against a live `ofpgproxy`. Two families:

1. **DuckDB** — attach the proxy directly, query Fusion alongside local DuckDB tables, Parquet files, or CTAS-cached slices.
2. **Real PostgreSQL → proxy** — expose Fusion tables inside another Postgres via `dblink` or `postgres_fdw`, then JOIN them with your real analytical schemas.

Everything below assumes the proxy is listening on `127.0.0.1:5433` (the default `--listen`). If you're running inside a Docker container and the proxy is on the host — substitute `host.docker.internal` on macOS/Windows Docker Desktop, or the host's LAN IP on Linux. See [Connection refused](#connection-refused-from-inside-docker) at the end.

---

## 1. DuckDB — native `ATTACH`

Start the `duckdb` shell and paste the block. The proxy shows up as a regular attached database; you can JOIN Fusion tables with local CSV/Parquet/in-memory tables in one query.

```sql
-- 1. Install + load the postgres extension.
INSTALL postgres;
LOAD postgres;

-- 2. REQUIRED: steer postgres_scanner to the regular-query protocol.
--    The proxy doesn't implement binary COPY yet; pg_use_binary_copy=false
--    is currently ignored by DuckDB, so use pg_use_text_protocol instead.
SET pg_use_text_protocol = true;

-- 3. Attach the proxy as a database.
ATTACH 'host=127.0.0.1 port=5433 user=anyone password=anyone dbname=any sslmode=disable'
    AS fusion (TYPE POSTGRES, READ_ONLY);

-- 4. Simple SELECT — real rows from Fusion.
SELECT period_name, period_year
FROM   fusion.public.gl_periods
WHERE  period_year = 2024
ORDER  BY period_name
LIMIT  5;

-- 5. Aggregation — pushed down as a SELECT COUNT(*) / GROUP BY to BI Publisher.
--    The AS n alias matters: BIP can't serialize a column named COUNT(*).
SELECT period_year, COUNT(*) AS n
FROM   fusion.public.gl_periods
WHERE  period_year BETWEEN 2020 AND 2025
GROUP  BY period_year
ORDER  BY period_year;

-- 6. JOIN a local DuckDB table with a Fusion table — in one query.
CREATE TEMP TABLE filter_years AS
    SELECT 2023 AS yr UNION ALL SELECT 2024 UNION ALL SELECT 2025;

SELECT p.period_name, p.period_year, p.start_date
FROM   fusion.public.gl_periods p
JOIN   filter_years            f ON p.period_year = f.yr
ORDER  BY p.period_year, p.start_date
LIMIT  10;

-- 7. CTAS — cache a slice of Fusion locally for fast iterative analysis.
CREATE OR REPLACE TABLE main.gl_periods_2024 AS
    SELECT period_name, period_year, start_date, end_date
    FROM   fusion.public.gl_periods
    WHERE  period_year = 2024;

SELECT COUNT(*) AS cached_rows FROM main.gl_periods_2024;
SELECT * FROM main.gl_periods_2024 ORDER BY start_date LIMIT 3;
```

Expected results:

- Step 4: 5 rows like `APR-24, 2024`
- Step 5: `2020..2025` with `n=27` each (27 periods/year in a standard Fusion calendar)
- Step 6: 10 rows across 2023/2024/2025
- Step 7: `cached_rows = 27`, then 3 sample rows

If step 5 fails with `Failed to prepare COPY ... FORMAT "binary"` — you skipped step 2.

---

## 2. Real PostgreSQL → proxy — `dblink`

Paste into a SQL editor connected to **your real PostgreSQL** (not the proxy). This is the quickest path for ad-hoc cross-database reads — no foreign-table DDL, one connection string per call.

```sql
-- 0. Clean slate (safe to re-run).
DROP VIEW IF EXISTS gl_periods_live;

-- 1. Extension.
CREATE EXTENSION IF NOT EXISTS dblink;

-- 2. Smoke test: one round-trip to the proxy, 5 rows from Fusion.
SELECT *
FROM dblink(
    'host=host.docker.internal port=5433 user=anyone password=anyone dbname=any sslmode=disable',
    'SELECT period_name, period_year FROM gl_periods WHERE period_year = 2024 ORDER BY period_name LIMIT 5'
) AS t(period_name text, period_year numeric);

-- 3. Wrap dblink in a VIEW — now Fusion looks like a local table for every
--    downstream query, no connection string repeated.
CREATE OR REPLACE VIEW gl_periods_live AS
SELECT *
FROM dblink(
    'host=host.docker.internal port=5433 user=anyone password=anyone dbname=any sslmode=disable',
    'SELECT period_name, period_year, start_date, end_date FROM gl_periods'
) AS t(
    period_name text,
    period_year numeric,
    start_date  timestamp,
    end_date    timestamp
);

-- 4. JOIN a local PG table with the Fusion-backed view.
DROP TABLE IF EXISTS filter_years;
CREATE TEMP TABLE filter_years(yr int);
INSERT INTO filter_years VALUES (2023), (2024), (2025);

SELECT p.period_name, p.period_year, p.start_date
FROM   gl_periods_live p
JOIN   filter_years    f ON p.period_year = f.yr
ORDER  BY p.period_year, p.start_date
LIMIT  10;

-- 5. CTAS — cache a Fusion slice into a real PG table.
DROP TABLE IF EXISTS gl_periods_2024;
CREATE TABLE gl_periods_2024 AS
SELECT period_name, period_year, start_date, end_date
FROM   gl_periods_live
WHERE  period_year = 2024;

SELECT COUNT(*) AS cached_rows FROM gl_periods_2024;
SELECT * FROM gl_periods_2024 ORDER BY start_date LIMIT 3;
```

Substitute `host.docker.internal` → `127.0.0.1` if both your PG and the proxy run directly on the host.

---

## 3. Real PostgreSQL → proxy — `postgres_fdw`

Heavier setup (one foreign-table per Fusion table), but once in place every read is just a regular `SELECT`. The proxy supports `DECLARE CURSOR` natively, so `postgres_fdw` streams pages with constant memory no matter how big the Fusion table is.

```sql
-- 0. Clean slate.
DROP FOREIGN TABLE IF EXISTS gl_periods_remote;
DROP USER MAPPING  IF EXISTS FOR CURRENT_USER SERVER fusion_proxy;
DROP SERVER        IF EXISTS fusion_proxy CASCADE;

-- 1. Extension.
CREATE EXTENSION IF NOT EXISTS postgres_fdw;

-- 2. Foreign server + user mapping.
CREATE SERVER fusion_proxy
    FOREIGN DATA WRAPPER postgres_fdw
    OPTIONS (host 'host.docker.internal', port '5433', dbname 'any', sslmode 'disable');

CREATE USER MAPPING FOR CURRENT_USER
    SERVER fusion_proxy
    OPTIONS (user 'anyone', password 'anyone');

-- 3. Foreign-table. For a typed wrapper around a specific Fusion table,
--    the shipped generator script produces the full column list:
--      ./gen-fdw-typed.sh metadata.db gl_periods > gl_periods.sql
--    For this demo we just declare the four columns we'll use.
CREATE FOREIGN TABLE gl_periods_remote (
    period_name text,
    period_year numeric,
    start_date  timestamp,
    end_date    timestamp
) SERVER fusion_proxy
  OPTIONS (schema_name 'public', table_name 'gl_periods');

-- 4. Plain SELECT.
SELECT period_name, period_year
FROM   gl_periods_remote
WHERE  period_year = 2024
ORDER  BY period_name
LIMIT  5;

-- 5. Aggregation. AS n alias matters.
SELECT period_year, COUNT(*) AS n
FROM   gl_periods_remote
WHERE  period_year BETWEEN 2020 AND 2025
GROUP  BY period_year
ORDER  BY period_year;

-- 6. JOIN with a local PG table.
DROP TABLE IF EXISTS filter_years;
CREATE TEMP TABLE filter_years(yr int);
INSERT INTO filter_years VALUES (2023), (2024), (2025);

SELECT p.period_name, p.period_year, p.start_date
FROM   gl_periods_remote p
JOIN   filter_years      f ON p.period_year = f.yr
ORDER  BY p.period_year, p.start_date
LIMIT  10;
```

---

## Connection refused from inside Docker

If your SQL client or the real PostgreSQL runs in a Docker container and the proxy is on the host, `127.0.0.1` inside the container does **not** reach the host:

- **macOS / Windows Docker Desktop:** use `host.docker.internal` instead of `127.0.0.1` in every connection string above.
- **Linux:** use the host's LAN IP, or start the container with `--add-host=host.docker.internal:host-gateway` and then the macOS form works.

Also verify the proxy isn't bound only to loopback on the host:

```bash
lsof -iTCP:5433 -sTCP:LISTEN
```

If the output shows `127.0.0.1:5433`, restart with `--listen 0.0.0.0:5433` (or `LISTEN=0.0.0.0:5433 make run`) so containers can reach it.

---

## Notes

- `COUNT(*)` without `AS alias` returns an empty value — BI Publisher can't render a column whose Oracle name contains `(*)` into an XML tag. Always alias: `COUNT(*) AS n`. Same for `SUM(expr)`, `MAX(expr)` when used bare.
- Both `dblink` and `postgres_fdw` use the proxy's read-only path. Any `INSERT` / `UPDATE` / `DELETE` ends up rejected as `42501 permission_denied` — BIP is read-only, and so is the proxy.
- For bigger extracts consider caching with CTAS into a real PG/DuckDB table (step 7 in DuckDB, step 5 in `dblink`). Fusion is slow on the BIP side — amortize the cost.
