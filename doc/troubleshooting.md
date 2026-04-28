# Troubleshooting

When something unexpected comes back from the proxy, check here before chasing into logs.

## Oracle errors coming through

Every `ORA-…` is forwarded from Fusion with a matching PostgreSQL SQLSTATE. The text is the original Oracle message — don't strip it, it's the quickest clue to the actual problem.

### `ORA-00942: table or view does not exist`

Fusion can't find the table. Two possibilities:

- **The table isn't in your catalog.** DBeaver's tree might list it (some probes go to Fusion directly), but `metadata.db` doesn't have it, so the proxy rejects the query up front. Re-run the catalog dumper and `SIGHUP`.
- **The table genuinely doesn't exist in the tenant** — typo, or a module that isn't installed. Check with a Fusion admin.

### `ORA-00904: "COL_NAME": invalid identifier`

Your SQL references a column Oracle can't resolve.

- **Quoted lowercase identifier** (`"period_name"`): Oracle stores unquoted identifiers uppercased, so `"period_name"` looks for a literal lowercase column and fails. Drop the quotes or uppercase inside them. [Full note](sql-compat.md#lowercase-quoted-identifiers).
- **Qualified `USING`-column**: `SELECT t.col_in_using FROM a JOIN b USING (col_in_using)` is valid PG but rejected by Oracle. [Full note](sql-compat.md#qualified-reference-to-a-using-column).
- **Spelling mismatch**: Fusion column names are usually UPPERCASE; psql and DBeaver lower-case them in display but the catalog stores uppercase. Inspect `pg_attribute` via `\d <table>` to confirm.

### `ORA-00920: invalid relational operator`

The SQL contains a PG-only operator the translator doesn't rewrite.

- **Bare `WHERE TRUE` / `WHERE FALSE`**: Oracle has no Boolean predicate type. Use `WHERE 1=1` / `WHERE 1=0` explicitly.
- **`IS DISTINCT FROM` / `IS NOT DISTINCT FROM`**: see [workaround](sql-compat.md#is-distinct-from--is-not-distinct-from).
- **PG-specific arrays / JSONB operators** (`@>`, `<@`, `->>`, `?`): not rewritten. Use Oracle-native syntax (`JSON_VALUE`, `JSON_EXISTS`, …) or cache into DuckDB/a real PG.

### `ORA-30485: missing ORDER BY expression in the window specification`

`ROW_NUMBER()` / `RANK()` / `DENSE_RANK()` / `NTILE()` require an `ORDER BY` inside `OVER(…)` on Oracle. See [workaround](sql-compat.md#row_number-over--without-order-by).

### `ORA-30484: missing window specification for this function`

Named windows (`WINDOW w AS (…)` + `OVER w`) aren't supported by Oracle. Inline the spec at every `OVER`. [Note](sql-compat.md#named-window-clause).

### `ORA-00979: not a GROUP BY expression`

Usually means a target-list expression (e.g. `TRUNC(created_at, 'YYYY') AS yr`) is used as `GROUP BY yr`, and the positional form `GROUP BY 1` is already auto-rewritten. If you still hit this, the column alias was referenced from a `GROUP BY` — write the full expression instead:

```sql
-- Wrong (on Oracle):
SELECT TRUNC(created_at, 'YYYY') AS yr FROM t GROUP BY yr

-- Right:
SELECT TRUNC(created_at, 'YYYY') AS yr FROM t GROUP BY TRUNC(created_at, 'YYYY')
```

### `ORA-19202: Error occurred in XML processing`

BI Publisher wraps every query result in an XML pipeline. When the inner SQL causes a type error (usually a silent `TO_NUMBER` / `TO_DATE` failure on the result stream), BIP surfaces it as XML-processing failure. Common trigger: comparing a VARCHAR2 Y/N column to a numeric literal — `WHERE flag = 1` on a `VARCHAR2(1)` column.

The type-aware boolean rewrite handles this automatically for `= TRUE` / `= FALSE`. If you wrote the numeric literal yourself, switch to `= 'Y'` / `= 'N'`.

### `ORA-01722: invalid number`

A string failed to convert to a number. Inspect the failing expression for implicit casts — usually a literal on the wrong side of `=`, or a column with dirty data that the caller assumed was numeric.

## Proxy-side errors

### `unsupported query: "..."`

The router couldn't classify the statement. Supported kinds:

- SELECT against foreign (Fusion) tables.
- SELECT against `pg_catalog.*` / `information_schema.*`.
- Session verbs: `BEGIN`, `COMMIT`, `ROLLBACK`, `SAVEPOINT`, `SET`, `RESET`, `DISCARD`, `SHOW`.
- Cursor verbs: `DECLARE … CURSOR FOR …`, `FETCH`, `CLOSE`.
- `COPY (SELECT …) TO STDOUT` — text format only (DuckDB `postgres_scanner` with `pg_use_binary_copy=false`, `psql \copy`, `pg_dump --data-only`).
- `SELECT 1`, `SELECT '<literal>'` — handshake fast paths.

Everything else (DDL, DML, binary `COPY`, `LISTEN`/`NOTIFY`, large-object verbs) is rejected. Writes are an intentional `42501 permission_denied` — BI Publisher is read-only; if you need write-back, target a downstream database.

If you think a query *should* be classifiable (new PG idiom, new BI-tool probe), capture the SQL from `--log-queries` output and file it — translator passes are cheap to add.

> **Tip:** start the proxy with `--translate-http 127.0.0.1:8080` and paste the offending SQL into the playground at <http://127.0.0.1:8080>. It shows the router decision and the exact rewrite without touching Fusion — useful for narrowing down whether the issue is in routing, translation, or BIP itself. See [Configuration → SQL Translator playground](configuration.md#sql-translator-playground).

### `DISTINCT ON form not supported`

The `DISTINCT ON` rewrite handles simple top-level SELECTs. More exotic forms (inside UNIONs, with CTEs, with `*` target) are rejected. Rewrite manually with `ROW_NUMBER() OVER (PARTITION BY … ORDER BY …)` and filter on the partition rank. [Full note](sql-compat.md#distinct-on-form-not-supported).

## Client-side surprises

### DBeaver hangs on a large table

- **Row limit in DBeaver** (the UI `1000` field at the bottom of the result grid) is a **display cap**, not a SQL limit. DBeaver still asks for *everything* and throws most away. The proxy auto-batches via OFFSET/FETCH for DBeaver, so the first 200 rows appear quickly anyway — but a `SELECT COUNT(*)` on a million-row table still hits BIP hard.
- Write `LIMIT 1000` in the SQL if you want to bound BIP-side work.

### A long query blocks every other tab / connection

The proxy serialises foreign `SELECT`s through a single SOAP slot by default (`--soap-concurrency=1`) — one call in flight at a time. While a heavy `SELECT` is running on tab A, every new query on tab B waits. `pg_catalog` / `information_schema` queries (IDE introspection) bypass this and stay responsive — only Fusion-table queries queue up.

Raise the cap for interactive use:

```bash
./ofpgproxy … --soap-concurrency 4
```

Sizing notes are in [Configuration → SOAP concurrency](configuration.md#soap-concurrency). The default stays low because BI Publisher accumulates server-side sessions; pushing it too high makes the tenant start refusing logins.

### Empty `\d <table>` or empty Metabase schema sync

Catalog isn't mounted. Check the startup log for `pg_catalog emulation enabled, reading …` — if missing, relaunch with `--metadata-path`.

If the line is there but a specific table has zero columns (`\d gl_je_categories` empty), that particular table is missing from `cached_columns` in your catalog. Re-dump with the catalog tool; some custom DFF tables aren't in the dumper's default filter.

### SSO keeps reopening Chrome

Refresh tokens are not persisted to disk, and the proxy automatically relaunches Chrome whenever silent refresh fails past expiry — so Chrome popping back up is the intended recovery path, not a bug.

If it happens more often than every few hours, the refresh grant is failing silently every time — either your IdP's refresh-token lifetime is very short (talk to the admin) or the tenant has restricted silent refresh for API apps. Consider `--auth=token-file` or `--auth=password` for long-running deployments.

### `build auth header: token expired and refresh failed for sso`

This error is from an older build where browser re-auth was not automatic. Current builds reopen Chrome on expiry instead of surfacing this error. Upgrade the proxy.

### `postgres_fdw` fails with `unsupported query: DECLARE ... CURSOR FOR ...`

You're on an old proxy build. Current builds support cursor protocol natively (streaming, not buffered). Upgrade.

### DuckDB: `Failed to prepare COPY "COPY (...) TO STDOUT (FORMAT \"binary\")"`

DuckDB's `postgres_scanner` defaults to binary `COPY`, which the proxy doesn't implement yet. Current DuckDB builds silently ignore `SET pg_use_binary_copy = false` — use the text-protocol switch instead, before the first query:

```sql
SET pg_use_text_protocol = true;
```

That makes `postgres_scanner` issue regular `SELECT` queries (which the proxy fully supports) instead of `COPY`. If you can't flip it (older DuckDB, third-party tool wrapping `postgres_scanner`), fall back to the [CSV pipeline or `dblink`](clients.md#duckdb).

### `Connection refused` from inside a Docker container

Inside a container, `127.0.0.1` is the container itself, not the host. Use `host.docker.internal` (macOS / Windows Docker Desktop) or the host's LAN IP (Linux Docker).

## How to read the log

With `--log-queries=true` (default) each incoming statement produces one line like:

```
time=... level=INFO msg=parse kind=foreign_select parse_took=237ms sql="SELECT ..." table=GL_JE_HEADERS
```

- `kind` — router decision (`foreign_select`, `catalog`, `declare_cursor`, `session_noop`, `unsupported`, …).
- `parse_took` — router + translator time, no SOAP yet.
- `table` — inferred target table (empty for catalog / session / cursor-all).
- `sql` — statement text, truncated at ~2000 chars.

Streaming SELECT completions log a follow-up `msg=exec done` with `rows` and `took` for the full round trip.

## Still stuck?

Capture:

1. The exact SQL (copy from the `--log-queries` line).
2. The error message (with `SQLSTATE` if your client shows it).
3. A single-row sample of the offending table (`SELECT * FROM …_ LIMIT 1`) — types + values often pinpoint the issue.

That triple identifies almost every reproducible case in minutes.
