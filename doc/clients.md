# Connecting clients

Any tool that speaks the PostgreSQL wire protocol will connect. Use `user=anyone password=anything database=any` by default — the proxy doesn't authenticate PG clients.

Assumes the proxy is running on `127.0.0.1:5433` (the default `--listen`).

## psql

```bash
psql -h 127.0.0.1 -p 5433 -U anyone -d any
```

```sql
SELECT period_name, period_year FROM gl_periods LIMIT 5;
```

`psql` uses the simple query protocol by default — the path with the fewest edge cases. For prepared statements and binary-format parameters use `--variable QUIET=1` plus `PREPARE`/`EXECUTE` or a code client.

## DBeaver

1. **New Connection** → PostgreSQL (built-in driver).
2. **Host** `127.0.0.1`, **Port** `5433`, **Database** `any`, User/Password anything.
3. **Driver properties** → set:
   - `preferQueryMode` = `simple`  (skips binary-format params we don't need)
   - `loggerLevel` = `OFF` (pgJDBC chatter is noisy on query runs)
4. **Test connection**, **Finish**.

### Pagination

DBeaver auto-paginates via OFFSET/FETCH batching on the proxy side. First page (default 200 rows) arrives in seconds even on million-row tables. Adjust batch size with `--foreign-batch-size`.

### Navigation tree

- Tables appear under `public` (or per-module schemas: `fscm`, `hcm`, `crm` when present in `metadata.db`).
- `Tools → Execute SQL script` runs as expected; multi-statement scripts are broken into individual queries.
- `\d`-style column listing populated from `pg_catalog.pg_attribute` via the DuckDB catalog.

## DataGrip / IntelliJ IDEA / PyCharm

Same as DBeaver — PostgreSQL driver, `preferQueryMode=simple`. The IDE's `application_name` is detected automatically and enables OFFSET/FETCH batching.

## DuckDB

DuckDB's `postgres_scanner` extension relies on `COPY … TO STDOUT (FORMAT binary)` internally, which the proxy doesn't implement. Two paths work cleanly:

### CSV pipeline (recommended)

```bash
psql -h 127.0.0.1 -p 5433 -U anyone -d any --csv \
  -c "SELECT * FROM gl_periods" \
  | duckdb -c "SELECT COUNT(*) FROM read_csv('/dev/stdin', header=true)"
```

Cache a table locally for fast iterative analysis without repeated SOAP hits:

```bash
mkdir -p cache
psql -h 127.0.0.1 -p 5433 -U anyone -d any --csv \
  -c "SELECT * FROM gl_periods" > cache/gl_periods.csv
duckdb cache/fusion.duckdb <<SQL
CREATE OR REPLACE TABLE gl_periods AS
  SELECT * FROM read_csv('cache/gl_periods.csv', header=true);
SQL
```

### Through a staging PostgreSQL with `dblink`

If you already run a real PG side-by-side, define the Fusion table via `dblink` in the PG (see below) and point DuckDB at that PG.

## postgres_fdw

Use a regular PG (Docker or local install) as a front; define Fusion tables as foreign via `postgres_fdw`.

```sql
-- One-time setup
CREATE EXTENSION IF NOT EXISTS postgres_fdw;

CREATE SERVER ofpg
    FOREIGN DATA WRAPPER postgres_fdw
    OPTIONS (host '127.0.0.1', port '5433', dbname 'any', sslmode 'disable');

CREATE USER MAPPING FOR current_user
    SERVER ofpg
    OPTIONS (user 'anyone', password 'anyone');

-- Declare a foreign table with real PG types.
-- Use the included generator script to produce this from metadata.db:
--     ./gen-fdw-typed.sh metadata.db gl_je_categories > gl_je_categories.sql
CREATE FOREIGN TABLE gl_je_categories_remote (
    row_id                text,
    je_category_name      text,
    description           text,
    -- ... 25+ more columns ...
    object_version_number numeric
) SERVER ofpg
  OPTIONS (schema_name 'public', table_name 'gl_je_categories');
```

```sql
-- Query as if it were a local table
SELECT * FROM gl_je_categories_remote LIMIT 10;

-- JOIN local and remote
SELECT g.je_category_name, t.some_local_metric
FROM   gl_je_categories_remote g
JOIN   local_metrics t USING (je_category_name);
```

Under the hood `postgres_fdw` uses **named cursors** — `DECLARE c1 CURSOR FOR …` / `FETCH N` — which the proxy supports natively with streaming (constant memory regardless of table size).

## dblink

Simpler than `postgres_fdw` for ad-hoc cross-DB queries (no foreign-table declaration, no cursor machinery).

```sql
CREATE EXTENSION IF NOT EXISTS dblink;

SELECT *
FROM dblink(
    'host=127.0.0.1 port=5433 user=anyone password=anyone dbname=any sslmode=disable',
    'SELECT je_category_name, description FROM gl_je_categories LIMIT 10'
) AS t(
    je_category_name text,
    description      text
);
```

Wrap in a VIEW to make it look like a table:

```sql
CREATE VIEW gl_je_categories_live AS
SELECT *
FROM dblink(
    'host=127.0.0.1 port=5433 user=anyone password=anyone dbname=any sslmode=disable',
    'SELECT * FROM gl_je_categories'
) AS t(
    row_id text, je_category_name text, description text /* … */
);
```

## Metabase / Superset / Grafana

Add as a **PostgreSQL** data source (not Oracle, not generic JDBC).

- **Host** `localhost`, **Port** `5433`, **Database** `any`, **Username** `anyone`.
- SSL: disabled (until you put a TLS terminator in front).
- Metabase: let it sync the schema — the proxy serves `information_schema.*` from the catalog, so all tables show up. Re-sync after you refresh `metadata.db`.
- Grafana: PostgreSQL data source, enable "Min time interval" to avoid sub-second queries hammering BIP.
- Superset: expose per-module schemas in the database connection UI for cleaner dataset pickers.

## Code clients

### pgx (Go)

```go
conn, err := pgx.Connect(ctx, "postgres://anyone@127.0.0.1:5433/any?sslmode=disable")
// …
rows, err := conn.Query(ctx,
    "SELECT period_name, period_year FROM gl_periods WHERE period_year = $1",
    int64(2024),
)
```

Binary-format parameters work — the proxy infers OIDs from the WHERE clause and advertises them in `ParameterDescription`.

### psycopg (Python)

```python
import psycopg
with psycopg.connect("host=127.0.0.1 port=5433 user=anyone dbname=any") as conn:
    rows = conn.execute(
        "SELECT period_name FROM gl_periods WHERE period_year = %s",
        (2024,),
    ).fetchall()
```

### pgJDBC (Java / Kotlin)

```kotlin
val conn = DriverManager.getConnection(
    "jdbc:postgresql://127.0.0.1:5433/any?preferQueryMode=simple",
    "anyone", "anyone"
)
```

Set `preferQueryMode=simple` for the most compatible path; extended mode works too but generates more catalog probes.

### dbt-postgres

Configure the target as a normal PG:

```yaml
fusion:
  target: dev
  outputs:
    dev:
      type: postgres
      host: 127.0.0.1
      port: 5433
      user: anyone
      password: anyone
      dbname: any
      schema: public
```

`dbt run` executes models against Fusion via the proxy. Writes are rejected (read-only) — `dbt seed` / `dbt snapshot` must target a real downstream DB.

## Pagination

| Client kind | Behaviour | How to change |
|---|---|---|
| IDE clients (DBeaver, DataGrip, JetBrains IDEs, pgAdmin, Metabase, Superset, Grafana, Tableau, Looker, Navicat, TablePlus, Postico, Redash, Power BI) | OFFSET/FETCH batching — first page fast, more pages on scroll | `--foreign-batch-size N` (0 disables) |
| Code clients (pgx, psycopg, psql, pgJDBC without IDE prefix) | Single SOAP call for the whole result | Add `LIMIT` in the query |

Detection is by the `application_name` sent in the PG startup packet, with live updates from `SET application_name = '…'` (pgJDBC connects as "PostgreSQL JDBC Driver", then DBeaver renames to "DBeaver 26.x — …" — we honour the rename).

## Troubleshooting

See [Troubleshooting](troubleshooting.md) for specific client errors: slow queries, empty schema trees, type mismatches, cursor errors, and more.
