# Troubleshooting

When something unexpected comes back from the proxy, check here before chasing into logs.

**Run `oratofusionproxy doctor` first.** Before digging into any of the sections
below, `oratofusionproxy doctor` — a live `SELECT 1 FROM DUAL` check by default, or
pass `--offline` for zero network calls — validates config, metadata.db, and
Fusion reachability using the exact same code path the server uses — often
narrowing "something's wrong" down to one specific check in seconds.
`oratofusionproxy doctor --profiles` additionally prints exactly which Oracle-wire
client/dialect/feature combinations this build has verified support for, and
a real connection's diagnostic bundle/logs report which profile it resolved
to via `compat_profile`/`compat_support`.

## `sqlplus` / OCI (thick / Instant Client) connection problems

`sqlplus` and other OCI "thick" clients (including a real Oracle database's
own `dblink`) are supported against a **modern (23ai-era) Instant Client**,
each verified only for a specific, narrower-than-"fully supported" scope —
see [Oracle clients → sqlplus](clients.md#sqlplus) and [Oracle
`dblink`](clients.md#oracle-dblink-a-real-oracle-database-as-the-client)
(run `oratofusionproxy doctor --profiles` for the full breakdown). If one still
won't connect:

- **Login fails / the connection drops right at the password step** — the
  Oracle wire has one shared password: whatever you set in
  `--oracle-password` / `ORACLE_WIRE_PASSWORD`. A wrong value fails the
  O5LOGON mutual handshake, which some clients report unhelpfully (a dropped
  connection rather than a clean "invalid password"). The **username** is
  *not* validated — but use `FUSION` (uppercase) so IDE tree-browsing works
  (see [the empty-tree item below](#sql-developer--vs-code-extension-tree-expands-with-no-error-but-no-tablesviews-listed)).
- `ORA-12537: TNS:connection closed` / `ORA-12541: TNS:no listener`-class
  errors, or a login that hangs then drops right after the data-type
  negotiation — almost always means the client asked for **encryption or
  native network services beyond plain negotiation** (`SQLNET.ENCRYPTION_CLIENT
  =REQUIRED`, TCPS in `sqlnet.ora`). The proxy only ever answers ANO with a
  "no native security" selection and speaks unencrypted TCP TNS — a client
  that insists on encryption still fails at that step. Drop the encryption
  requirement client-side, or terminate TLS in front of the proxy if you need
  transport security.
- Client trace (`sqlnet.ora` `TRACE_LEVEL_CLIENT=16`) showing `nsnactl: error
  exit` with `ns=12534` points at the same ANO/encryption mismatch.
- A **very old** Instant Client (pre-dates the 23ai TTC dialect this listener
  implements) may not negotiate the shape the proxy expects — if you hit this,
  a thin driver (**SQLcl**, the drop-in `sqlplus` replacement) sidesteps the
  whole OCI/ANO path entirely and is worth trying as a quick check that the
  proxy itself is reachable and configured correctly.

## How Fusion's errors reach your client

BI Publisher reports failures as a SOAP fault wrapping a large XML document.
You never see that. The proxy digs the Oracle error out of it and puts a real
Oracle error on the wire, so a client shows what it would show against a real
database:

| What happened in Fusion | What your client sees |
|---|---|
| The SQL failed with an Oracle error (bad table, bad column, bad syntax) | That exact error, code and text — `ORA-00942: table or view does not exist` in DBeaver's error dialog, in SQLcl's output, in `SQLException.getErrorCode()` |
| A write was attempted | `ORA-16000: database open for read-only access`, refused by the proxy before it ever reached Fusion |
| The client cancelled (Ctrl-C, Cancel button) | `ORA-01013: user requested cancel of current operation` |
| A statement the proxy doesn't implement | `ORA-03001: unimplemented feature`, session kept alive |
| Cursors exhausted on one connection | `ORA-01000: maximum open cursors exceeded` |
| The call failed with no Oracle error in it — transport, auth, a BIP-level fault | `ORA-00604` carrying the underlying message verbatim |

That last row is the honest fallback: rather than inventing a plausible-looking
code, the proxy says "something below this failed" and hands you the real text.
If you see `ORA-00604`, read its message — it will name the actual problem
(HTTP status, auth failure, malformed report response).

There is no generic `500` and no opaque wrapper: if a code is available, you
get that code. The full message is always preserved — don't strip it, it's the
quickest clue to the actual problem.

### `ORA-16000: database open for read-only access`

**Expected, not a bug.** You ran a write (INSERT / UPDATE / DELETE / MERGE /
CREATE / DROP / ALTER … ). The proxy is read-only by construction — BI
Publisher can only SELECT — so every DML/DDL statement is refused with this
error, on every client wire. The session stays open; keep querying.

### `ORA-03001: unimplemented feature (Oracle-wire call N not supported by this proxy)`

**Expected, not a bug.** A client issued a call this proxy doesn't implement —
most commonly sqlplus's `DESCRIBE` command (which asks for column metadata over
its own protocol call, TTC func 119, rather than a query). The `DESCRIBE`
command isn't supported yet; get column metadata from catalog views instead
(`SELECT column_name, data_type FROM all_tab_columns WHERE table_name = '…'`),
or from your IDE's object tree. The session is kept alive after the error.

### `ORA-00942: table or view does not exist`

Fusion can't find the table. Two possibilities:

- **The table isn't in your catalog.** DBeaver's tree might list it (some probes go to Fusion directly), but `metadata.db` doesn't have it, so the proxy rejects the query up front. Use a refreshed `metadata.db` from the latest release.
- **The table genuinely doesn't exist in the tenant** — typo, or a module that isn't installed. Check with a Fusion admin.

### `ORA-00904: "COL_NAME": invalid identifier`

Your SQL references a column Oracle can't resolve.

- **Quoted lowercase identifier** (`"period_name"`): Oracle stores unquoted identifiers uppercased, so `"period_name"` looks for a literal lowercase column and fails. Drop the quotes or uppercase inside them.
- **Spelling mismatch**: Fusion column names are UPPERCASE. Check the catalog with `DESCRIBE <table>` or `SELECT column_name FROM all_tab_columns WHERE table_name = '<TABLE>'`.

### `ORA-30485: missing ORDER BY expression in the window specification`

`ROW_NUMBER()` / `RANK()` / `DENSE_RANK()` / `NTILE()` require an `ORDER BY` inside `OVER(…)` on Oracle. Add one — `ORDER BY NULL` or `ORDER BY 1` if the order genuinely doesn't matter.

### `ORA-30484: missing window specification for this function`

Named windows (`WINDOW w AS (…)` + `OVER w`) aren't supported by Oracle. Inline the spec at every `OVER`.

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

- `SELECT` against Fusion tables.
- `SELECT` against the data dictionary (`ALL_TABLES`, `ALL_TAB_COLUMNS`, `ALL_OBJECTS`, `USER_*`, `DBA_*`, `DUAL`).
- Session verbs: `ALTER SESSION`, `SET`, `COMMIT`, `ROLLBACK` — accepted as no-ops.
- `SELECT 1 FROM DUAL` and similar handshake probes.

Everything else (DDL, DML, PL/SQL blocks) is rejected. Writes come back as `ORA-16000` — BI Publisher is read-only; if you need write-back, target a downstream database.

If you think a query *should* be classifiable (a new client probe, a dictionary view we don't emulate yet), capture the SQL from `--log-queries` output and file it — these are cheap to add.

> **Tip:** start the proxy with `--translate-http 127.0.0.1:8080` and paste the offending SQL into the playground at <http://127.0.0.1:8080>. It shows the router decision and the exact rewrite without touching Fusion — useful for narrowing down whether the issue is in routing, translation, or BIP itself. See [Configuration → SQL Translator playground](configuration.md#sql-translator-playground).

## Client-side surprises

### DBeaver hangs on a large table

- **Row limit in DBeaver** (the UI `1000` field at the bottom of the result grid) is a **display cap**, not a SQL limit. DBeaver still asks for *everything* and throws most away. The proxy auto-batches via OFFSET/FETCH for DBeaver, so the first 200 rows appear quickly anyway — but a `SELECT COUNT(*)` on a million-row table still hits BIP hard.
- Write `LIMIT 1000` in the SQL if you want to bound BIP-side work.

### A long query blocks every other tab / connection

The proxy serialises `SELECT`s against Fusion through a single SOAP slot by default (`--soap-concurrency=1`) — one call in flight at a time. While a heavy `SELECT` is running on tab A, every new query on tab B waits. Data-dictionary queries (IDE introspection) bypass this and stay responsive — only Fusion-table queries queue up.

Raise the cap for interactive use:

```bash
./oratofusionproxy … --soap-concurrency 4
```

Sizing notes are in [Configuration → SOAP concurrency](configuration.md#soap-concurrency). The default stays low because BI Publisher accumulates server-side sessions; pushing it too high makes the tenant start refusing logins.

### SQL Developer / VS Code extension: tree expands with no error but no tables/views listed

The connection's **username** isn't `FUSION`. SQL Developer's own "Tables"/"Views"
tree nodes run a `SYS.DBA_OBJECTS`-style query filtered `WHERE OWNER = <your
connected username>` — the client fills this in itself, client-side, from
whatever username it authenticated with; it never asks the proxy what the
"real" schema is. The proxy reports every object as owned by the single
logical schema `FUSION` (see [Connecting clients → Oracle
clients](clients.md#oracle-clients-sqlcl-dbeaver-sql-developer)), so any other
username makes the tree query run successfully (no `ORA-` error) and just
return zero rows.

Fix: edit the connection and set **Username** to `FUSION` (the *password*
stays your `--oracle-password` value — username isn't otherwise validated).
Ad-hoc `SELECT`s you type yourself aren't affected either way — this only
breaks the auto-generated tree-browsing queries.

### Empty schema tree, or a table with no columns

Run `oratofusionproxy doctor` — `metadata.counts` says outright whether the catalog is
populated, and an empty one answers every query with nothing, which otherwise
looks like success.

On a cold cache this is expected for a minute or two: the table list is fetched
in the background on first use (the log says so, and
`orawire_metadata_bootstrap_running` on `/metrics` reads 1 while it runs). A
single table showing no columns should fix itself the moment a client asks for
it — watch for a `metadata: cached N columns of <TABLE>` log line. If instead
you see `has no columns in the tenant catalog`, that object genuinely isn't in
`FND_COLUMNS`, which is also true of views. See [Metadata catalog](metadata.md).

### SSO keeps reopening Chrome

Refresh tokens are not persisted to disk, and the proxy automatically relaunches Chrome whenever silent refresh fails past expiry — so Chrome popping back up is the intended recovery path, not a bug.

If it happens more often than every few hours, the refresh grant is failing silently every time — either your IdP's refresh-token lifetime is very short (talk to the admin) or the tenant has restricted silent refresh for API apps. Consider `--auth=token-file` or `--auth=password` for long-running deployments.

### `build auth header: token expired and refresh failed for sso`

This error is from an older build where browser re-auth was not automatic. Current builds reopen Chrome on expiry instead of surfacing this error. Upgrade the proxy.

### `Connection refused` from inside a Docker container

Inside a container, `127.0.0.1` is the container itself, not the host. Use `host.docker.internal` (macOS / Windows Docker Desktop) or the host's LAN IP (Linux Docker).

### An Oracle-wire connection (especially `dblink`) just hangs

Most commonly a real Oracle database's own `dblink` shadow session stalling
right after the session-sync (`0x44`) exchange — the client silently never
sends the round-2 `EXECUTE` it's protocol-obligated to send next, and
without the watchdog the connection would sit open forever with no trace of
what happened.

By default (`--oracle-protocol-timeout 30s`), this now surfaces as one
structured warning instead of a silent hang:

```
orawire: protocol watchdog timeout conn_id=42 remote=... dialect=dblink
  state=dblink.await_execute_round2 expected="OCI EXECUTE round 2"
  elapsed=30s describe_columns=262 diagnostic_bundle=/.../orawire-...zip
```

The connection is closed; every *other* connection on the proxy keeps
running unaffected. `state`/`expected` say exactly which step the client
never completed — see [Configuration → Protocol watchdog &
diagnostics](configuration.md#protocol-watchdog--diagnostics) for the full
list of strict states this can fire on (only handshake steps and dblink's
own round1→sync3→round2→fetch chain — never an ordinary idle interactive
session).

If you hit this against a live dblink session, turn on
`--oracle-diagnostic-dir` (and, only while actively reproducing, add
`--oracle-diagnostic-raw`) so the next occurrence produces an attachable
bundle instead of just a log line — see the next section.

## How to read the log

With `--log-queries=true` (default) each incoming statement produces one line like:

```
time=... level=INFO msg=parse kind=foreign_select parse_took=237ms sql="SELECT ..." table=GL_JE_HEADERS
```

- `kind` — router decision (`foreign_select`, `catalog`, `declare_cursor`, `session_noop`, `unsupported`, …).
- `parse_took` — router + translator time, no SOAP yet.
- `table` — inferred target table (empty for catalog / session / cursor-all).
- `sql` — statement text, truncated at ~2000 chars.

Streaming SELECT completions log a follow-up `msg="exec done"` with `rows` and `took` for the full round trip.

If a foreign SELECT fails once translated SQL reaches Fusion, the proxy logs a `msg="foreign exec failed"` WARN line alongside the `ORA-…` the client sees, with the *exact* Oracle SQL that was sent (`oracle_sql`), not the statement as `msg=parse` logged it. This is the line to grab when a query works in `sqlplus` but fails from a GUI client: tool-generated SQL is often not what you'd expect from reading the UI. Both `sql` fields truncate at ~2000 chars by default; set `OFPG_LOG_FULL_SQL=1` (see [Configuration → Debug / development](configuration.md#debug--development)) to capture them whole when a wide `SELECT *` or a 100+-column BI import is the suspect.

## Still stuck?

Capture:

1. The exact SQL — the `oracle_sql` field from a `msg="foreign exec failed"` line if the query reached Fusion and errored there; otherwise the `sql` field from the `msg=parse` line. Set `OFPG_LOG_FULL_SQL=1` first if the query is long (BI-tool imports routinely are) — the default ~2000-char truncation cuts off exactly the part you need for wide `SELECT *` queries.
2. The error message (with `SQLSTATE` if your client shows it).
3. A single-row sample of the offending table (`SELECT * FROM …_ LIMIT 1`) — types + values often pinpoint the issue.

That triple identifies almost every reproducible case in minutes.

For an Oracle-wire hang/timeout specifically, also grab the `orawire:
protocol watchdog timeout` log line itself (it names the exact state/expected
step) and, if `--oracle-diagnostic-dir` was set, the matching
`orawire-<timestamp>-conn-<id>-<trigger>.zip` bundle — attach it as-is, no
need to unzip or extract anything from it first.
