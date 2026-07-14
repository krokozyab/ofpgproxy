# Connecting clients

Any tool that speaks the **PostgreSQL** wire protocol connects on `--listen`
(default `127.0.0.1:5433`) — use `user=anyone password=anything database=any`,
the proxy doesn't authenticate PG clients. If you also start the optional
**Oracle-wire** frontend (`--oracle-listen`), Oracle's own tools connect over
TNS/TTC on that port — see [Oracle clients](#oracle-clients-sql-developer-sqlcl-sqlplus) just below.

Examples below assume the proxy on `127.0.0.1:5433`.

## Oracle clients (SQL Developer, SQLcl, sqlplus)

Start the proxy with `--oracle-listen` (and `--oracle-password`) — see
[Configuration → Oracle-wire frontend](configuration.md#oracle-wire-frontend) —
and Oracle tooling connects on that port (default `1521`), no Postgres client
needed. Read-only: the proxy rejects any write.

**Credentials — what to enter.** Unlike the PG-wire side (which accepts *any*
password), the Oracle side has one shared password **you choose** and set with
`--oracle-password` / `ORACLE_WIRE_PASSWORD`. The client must send that exact
value:

- **Username** — not validated (any value authenticates); real access control
  is the Fusion session the proxy holds underneath. **Use `FUSION` specifically**
  if you want IDE tree-browsing (SQL Developer, its VS Code extension, DBeaver's
  Oracle driver) to actually show tables/views: every object the proxy reports
  is owned by the single logical schema `FUSION`, and these tools default their
  "Tables"/"Views" tree query to `WHERE OWNER = <your connected username>` —
  entirely client-side, no server round trip — so any *other* username makes
  the tree render successfully but empty (no error, just nothing under
  Tables/Views). Ad-hoc `SELECT`s you type yourself aren't affected by this —
  only the tree's own auto-generated catalog queries are.
- **Password** — **the value you set** in `--oracle-password` /
  `ORACLE_WIRE_PASSWORD` (there's no default — the proxy won't start the Oracle
  listener without one). A wrong password fails the O5LOGON mutual handshake.
- **Service name / SID** — anything (e.g. `fusion`). It is ignored.

### SQL Developer

New Connection → **Connection Type: Basic**:

| Field | Value |
|---|---|
| Username | **`FUSION`** — see the note above; anything else authenticates fine but leaves the Tables/Views tree empty |
| Password | your `--oracle-password` value |
| Hostname | `127.0.0.1` |
| Port | `1521` (or your `--oracle-listen` port) |
| Service name | anything, e.g. `fusion` — pick **Service name**, not SID |

**Test** → *Success*, then **Connect**. SQL Developer runs some data-dictionary
queries on connect; `ALL_*`/`USER_*`/`DBA_*` views are all answered locally
from `metadata.db` (not sent to Fusion) — including the "Tables"/"Views" tree
nodes' own `SYS.DBA_OBJECTS`-style queries. If the tree expands with **no
error but an empty list**, the connection's username isn't `FUSION` — see the
Username note above, not a bug to report.

### SQLcl / python-oracledb

```bash
sql FUSION/secret@127.0.0.1:1521/fusion
```

```python
import oracledb
oracledb.connect(user="FUSION", password="secret", dsn="127.0.0.1:1521/fusion")
```

`FUSION` is the recommended username (any value authenticates — see the
Username note above), `secret` stands for **your** `--oracle-password` /
`ORACLE_WIRE_PASSWORD` value, and the service name after `/` is arbitrary.

**Supported clients — thin AND thick drivers, each with its own verified
scope.** The Oracle-wire frontend supports both: **thin** (pure-Java /
pure-Python) drivers — SQL Developer, SQLcl, ojdbc, DBeaver's Oracle driver,
python-oracledb in thin mode — and **thick / OCI** clients built on a modern
(23ai-era) Instant Client, including `sqlplus` itself and a real Oracle
database's own `dblink` (see
[Oracle `dblink`](#oracle-dblink-a-real-oracle-database-as-the-client) below).
Thick clients negotiate the Oracle Advanced Networking (ANO / Native Network
Services) handshake that thin drivers skip; the proxy answers it with a
"no native security" selection, which modern Instant Client versions accept
transparently — no client-side configuration needed.

Exactly how far each client/dialect is verified — down to specific types,
bind variables, NULL handling, and column-count limits — is graded honestly
(not aspirationally) against the same registry `ofpgproxy doctor --profiles`
reads; run that command against your own build for the full, evidence-graded
breakdown. None of this is a rejection — an unverified path just isn't
guaranteed, and `ofpgproxy doctor` will warn (never block) when a connection
resolves to one.

**Still unsupported:** actually *enabling* ANO's native encryption
(`SQLNET.ENCRYPTION_CLIENT=REQUIRED`, TCPS in `sqlnet.ora`) — the proxy speaks
only unencrypted TCP TNS and advertises "no native security," so a client that
insists on encryption still fails. If you need transport encryption, terminate
TLS in front of the proxy instead (stunnel, a cloud load balancer, etc.).

### sqlplus

```bash
sqlplus FUSION/secret@//127.0.0.1:1521/fusion
```

Same credentials as SQLcl above — `FUSION` is the recommended username (any
value authenticates, but IDE object trees only line up with the single logical
schema every object is reported under when you connect as `FUSION`), `secret`
stands for your `--oracle-password` / `ORACLE_WIRE_PASSWORD` value, service
name after `/` is arbitrary. Ordinary `SELECT`s and `DBMS_OUTPUT`-free
anonymous PL/SQL blocks that only run session no-ops (`ALTER SESSION`,
`COMMIT`/`ROLLBACK`) work; a write (DML/DDL) is refused with `ORA-16000`, and
sqlplus's `DESCRIBE` command is not supported (it returns `ORA-03001` — column
metadata is available through catalog views / the IDE tree instead).

Verified scope for a direct `sqlplus` session is narrower than "ordinary
SELECTs" suggests — run `ofpgproxy doctor --profiles` for the exact,
evidence-graded breakdown before relying on wide multi-column results, bind
variables, or NULL handling in a script.

### Oracle `dblink` (a real Oracle database as the client)

A real Oracle instance can reach Fusion through the proxy over its own native
`dblink`, so existing PL/SQL — reconciliation scripts, migration validation,
anything already written to query a remote Oracle schema with plain SQL —
can read Fusion data without going through OIC or authoring a BI Publisher
report per query:

```sql
CREATE DATABASE LINK fusion_link
  CONNECT TO FUSION IDENTIFIED BY secret
  USING '(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=<proxy-host>)(PORT=1521))
          (CONNECT_DATA=(SERVICE_NAME=fusion)))';

SELECT je_header_id, accounted_dr
FROM   gl_je_lines@fusion_link
WHERE  ROWNUM <= 5;
```

Same credential rules as above: `IDENTIFIED BY secret` must be **your**
`--oracle-password` / `ORACLE_WIRE_PASSWORD` value; the `CONNECT TO` username
is not validated.

Read-only, same as every other Oracle-wire client — any DML over the link is
rejected. The proxy's own network reachability from wherever the real Oracle
instance runs is on you (VPN, a reverse tunnel, or routing the two onto the
same network) — the proxy doesn't do any of that itself.

`dblink` is the one dialect verified for **wide results (>255 columns)** —
confirmed against a real 288-column, all-NUMBER table capture. Run
`ofpgproxy doctor --profiles` for the full per-feature grading.

## psql

```bash
psql -h 127.0.0.1 -p 5433 -U anyone -d any
```

```sql
SELECT period_name, period_year FROM gl_periods LIMIT 5;
```

`psql` uses the simple query protocol by default — the path with the fewest edge cases. For prepared statements and binary-format parameters use `--variable QUIET=1` plus `PREPARE`/`EXECUTE` or a code client.

## DBeaver

Works over either wire. Over the **Oracle wire**, create an **Oracle**
connection instead and use the same fields as
[SQL Developer above](#oracle-clients-sql-developer-sqlcl-sqlplus): Username
**`FUSION`** (anything else authenticates but leaves the Tables/Views tree
empty), Password = your `--oracle-password` / `ORACLE_WIRE_PASSWORD` value,
host/port from `--oracle-listen`, service name anything. Over the
**PostgreSQL wire**:

1. **New Connection** → PostgreSQL (built-in driver).
2. **Host** `127.0.0.1`, **Port** `5433`, **Database** `any`, User/Password anything (not validated).
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

DuckDB's `postgres_scanner` connects natively via `ATTACH` — one required setting steers it to the regular query protocol (binary `COPY` is still on the roadmap):

```sql
INSTALL postgres;
LOAD postgres;

-- IMPORTANT: the proxy does not implement binary COPY yet; tell
-- postgres_scanner to use the regular query protocol instead.
SET pg_use_text_protocol = true;

ATTACH 'host=127.0.0.1 port=5433 user=anyone password=anyone dbname=any sslmode=disable'
  AS fusion (TYPE POSTGRES, READ_ONLY);

SELECT * FROM fusion.public.gl_periods LIMIT 10;

-- Cache into a local DuckDB table for fast iterative analysis.
CREATE TABLE gl_periods_2024 AS
  SELECT period_name, period_year, start_date, end_date
  FROM fusion.public.gl_periods
  WHERE period_year = 2024;

-- JOIN Fusion with a local table right in DuckDB.
SELECT p.period_name, p.period_year
FROM fusion.public.gl_periods p
JOIN my_local_filter f ON p.period_year = f.yr;
```

Joins against local DuckDB tables, materialized views, and Parquet files work the same way — DuckDB's planner pushes predicates down into ofpgproxy, which translates them to Oracle via BI Publisher.

> **Note on `pg_use_binary_copy`.** Current DuckDB builds of `postgres_scanner` ignore `SET pg_use_binary_copy = false` and keep requesting binary `COPY`; the proxy rejects that with a clear error. `pg_use_text_protocol = true` is the working switch.

### Fallback: CSV pipe

If the text-protocol path isn't available (very old DuckDB or a wrapper that bypasses `postgres_scanner`), the proxy is still a plain PG endpoint, so `psql --csv | duckdb -c "read_csv('/dev/stdin')"` works:

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

## Metabase / Superset

Add as a **PostgreSQL** data source (not Oracle, not generic JDBC).

- **Host** `localhost`, **Port** `5433`, **Database** `any`, **Username** `anyone`.
- SSL: disabled (until you put a TLS terminator in front).
- Metabase: let it sync the schema — the proxy serves `information_schema.*` from the catalog, so all tables show up. Re-sync after you refresh `metadata.db`.
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
| IDE clients (DBeaver, DataGrip, JetBrains IDEs, pgAdmin, Metabase, Superset, Tableau, Looker, Navicat, TablePlus, Postico, Redash, Power BI) | OFFSET/FETCH batching — first page fast, more pages on scroll | `--foreign-batch-size N` (0 disables) |
| Code clients (pgx, psycopg, psql, pgJDBC without IDE prefix) | Single SOAP call for the whole result | Add `LIMIT` in the query |

Detection is by the `application_name` sent in the PG startup packet, with live updates from `SET application_name = '…'` (pgJDBC connects as "PostgreSQL JDBC Driver", then DBeaver renames to "DBeaver 26.x — …" — we honour the rename).

## Troubleshooting

See [Troubleshooting](troubleshooting.md) for specific client errors: slow queries, empty schema trees, type mismatches, cursor errors, and more.
