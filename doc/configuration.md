# Configuration

`ofpgproxy` takes configuration from two places:

1. Command-line flags (`./ofpgproxy --oracle-listen 127.0.0.1:1521 …`).
2. Environment variables (or a `.env` file sourced before launch).

Flags always win over environment variables. A few parameters are available only one way — noted below.

## Command-line flags

### Listeners

| Flag | Env | Default | Description |
|---|---|---|---|
| `--oracle-listen` | `OFPG_ORACLE_LISTEN` | off | `host:port` the read-only **Oracle-wire (TNS/TTC)** listener binds to, for SQLcl, DBeaver, SQL Developer, ojdbc, python-oracledb and another database's `dblink`. See [Oracle-wire frontend](#oracle-wire-frontend). |
| `--oracle-password` | `ORACLE_WIRE_PASSWORD` | — | Shared password the Oracle-wire O5LOGON handshake accepts (any username). **Required** with `--oracle-listen`. |
| `--metrics-listen` | `OFPG_METRICS_LISTEN` | off | `host:port` for the ops HTTP server: Prometheus `/metrics` + `/healthz` + `/readyz`. Requires `--oracle-listen`. No auth — bind to loopback. See [Observability](observability.md). |
| `--oracle-protocol-timeout` | `OFPG_ORACLE_PROTOCOL_TIMEOUT` | `30s` | Protocol watchdog timeout for the Oracle-wire frontend, `time.Duration` syntax. `0` disables it. See [Protocol watchdog & diagnostics](#protocol-watchdog--diagnostics). |
| `--oracle-diagnostic-dir` | `OFPG_ORACLE_DIAGNOSTIC_DIR` | off | Directory to write bounded diagnostic `.zip` bundles on a protocol timeout, panic, malformed message, or unsupported transition. Empty disables bundle writing (timeout logging still always happens). |
| `--oracle-diagnostic-raw` | `OFPG_ORACLE_DIAGNOSTIC_RAW` | `false` | Include bounded (256 KiB/direction) raw wire byte tails in diagnostic bundles. Off by default — raw bytes may include query result values. |

### Backend

| Flag | Env | Default | Description |
|---|---|---|---|
| `--fusion-host` | `FUSION_HOST` | — | Fusion tenant hostname. **No protocol, no path.** Example: `fa-xxxx.oraclecloud.com`. |
| `--report-path` | `FUSION_SQL_REPORT_PATH` | `/Custom/Financials/RP_ARB.xdo` | BI Publisher report absolute path. Most tenants use `/Custom/sql/RP_ARB.xdo`. |
| `--metadata-cache` | — | `metadata-cache.db` beside the binary | Writable catalog the proxy fills from your tenant on demand. See [Metadata catalog](metadata.md). |
| `--metadata-path` | — | — | An existing catalog file. On its own it is used **read-only**; with `--metadata-cache` it seeds the cache once and is never written to. |
| `--metadata-refresh` | — | `0s` (off) | Re-read the tenant's table list on a timer. `SIGHUP` always triggers one. |
| `--foreign-batch-size` | — | `200` | Rows per SOAP call when an **IDE client** runs a foreign SELECT without `LIMIT`. `0` disables auto-pagination (code clients always get a single shot). See [Clients — pagination](clients.md#pagination). |
| `--soap-concurrency` | `FUSION_SOAP_CONCURRENCY` | `1` | Max concurrent SOAP calls to BI Publisher. Default `1` serialises all foreign SELECTs. Raise to `2`–`4` for IDE-heavy use; too high and the tenant refuses logins. See [SOAP concurrency](#soap-concurrency). |
| `--log-queries` | — | `true` | One structured log entry per incoming query (kind, table, duration). Set `false` in production after tuning. |
| `--translate-http` | — | off | `host:port` for the built-in offline SQL Translator playground (dev tool). Loopback only. See [SQL Translator playground](#sql-translator-playground). |

### Authentication

`--auth` selects the mode; the rest are mode-specific. Full details in [Authentication](auth.md).

| Flag | Env | For mode | Description |
|---|---|---|---|
| `--auth` | `FUSION_AUTH_TYPE` | all | `password` \| `token-file` \| `token-refresh` \| `client-credentials` \| `jwt-assertion` \| `sso`. Required when `--fusion-host` is set. |
| `--auth-user` | `FUSION_USER` | password | Fusion username. |
| `--auth-password` | `FUSION_PASSWORD` | password | Fusion password (prefer env). |
| `--auth-token-file` | — | token-file | Path to a bearer-token file (re-read each call, so rotate externally). |
| `--auth-refresh-token` | `FUSION_REFRESH_TOKEN` | token-refresh | Long-lived OAuth refresh token; the proxy auto-refreshes the access token. |
| `--auth-access-token` | `FUSION_ACCESS_TOKEN` | token-refresh | Optional seed access token (saves one startup refresh). |
| `--oauth-token-url` | `FUSION_OAUTH_TOKEN_URL` | client-credentials, jwt-assertion | IdP token endpoint, e.g. `https://<idcs>/oauth2/v1/token`. |
| `--oauth-client-id` | `FUSION_OAUTH_CLIENT_ID` | client-credentials, jwt-assertion | OAuth client id. |
| `--oauth-client-secret` | `FUSION_OAUTH_CLIENT_SECRET` | client-credentials, jwt-assertion | OAuth client secret (prefer env). |
| `--oauth-scope` | `FUSION_OAUTH_SCOPE` | client-credentials, jwt-assertion | Scope, if the IdP requires one. |
| `--jwt-subject` | `FUSION_JWT_SUBJECT` | jwt-assertion | Service-account username to run reports as (JWT `sub`). |
| `--jwt-audience` | `FUSION_JWT_AUDIENCE` | jwt-assertion | Assertion audience (defaults to the token URL). |
| `--jwt-key-file` | `FUSION_JWT_KEY_FILE` | jwt-assertion | RSA private key (PEM, PKCS#1 or #8) signing the assertion. |
| `--jwt-key-id` | `FUSION_JWT_KEY_ID` | jwt-assertion | `kid` matching the cert registered with the OAuth app. |
| `--sso-timeout` | — | sso | Browser-login timeout in seconds (default `300`). |

## Environment variables

Every setting with an `Env` column above can be supplied via environment
variable (or `.env`). Flags win over env; env wins over the built-in default.
Prefer env for secrets so they don't appear in the process table (`ps`). A
copy-paste starting point lives in `.env.example`.

## Oracle-wire frontend

Set `--oracle-listen`/`OFPG_ORACLE_LISTEN` (plus `--oracle-password`) to expose
the **read-only** listener speaking the Oracle TNS/TTC protocol, so `sqlplus`,
SQLcl, DBeaver, SQL Developer, ojdbc, python-oracledb and another database's
`dblink` connect. Columns describe with their real Oracle types
(NUMBER/DATE/TIMESTAMP/RAW/CLOB/BLOB/…) resolved from the metadata catalog.

```bash
./ofpgproxy \
  --oracle-listen 127.0.0.1:1521 --oracle-password changeme \
  --fusion-host fa-xxxx.oraclecloud.com --auth=sso
# sqlplus / SQL Developer / SQLcl / python-oracledb on :1521
```

Oracle clients log in with **any username** and the shared `--oracle-password`
(the service name is ignored). See [Oracle clients](clients.md#oracle-clients-sqlcl-dbeaver-sql-developer) for the exact SQL Developer / SQLcl fields.

**Thin and thick drivers both work, each with its own verified scope.**
Supported clients include the thin (pure-Java / pure-Python) Oracle drivers —
SQL Developer, SQLcl, ojdbc, DBeaver's Oracle driver, python-oracledb (thin
mode) — as well as thick / OCI clients built on a modern (23ai-era) Instant
Client, including `sqlplus` itself and a real Oracle database's own `dblink`.
Thick clients' Advanced Networking (ANO) handshake is answered with a "no
native security" selection, which modern Instant Client accepts without any
client-side configuration. The transport is plain unencrypted TCP TNS only —
actually turning ON native encryption/TCPS (`SQLNET.ENCRYPTION_*`) is still
out of scope; terminate TLS in front of the proxy instead if you need that.

"Both work" does not mean every client/dialect works identically — coverage
varies by client and query shape. See [Oracle clients](clients.md#oracle-clients-sqlcl-dbeaver-sql-developer)
for connection strings. `ofpgproxy doctor` reports which profile a real
connection resolves to and warns (never blocks) on an unverified one; run
`ofpgproxy doctor --profiles` for the full, evidence-graded breakdown.

## `ofpgproxy doctor`

`ofpgproxy doctor` validates a concrete installation — config, `metadata.db`,
and Fusion reachability — before you ever point a real client at it. It's the
same `ofpgproxy` binary with `doctor` as its **first** argument; every other
flag and env var (`FUSION_HOST`, `FUSION_AUTH_TYPE`, `--metadata-path`, ...)
is identical to the server's own — nothing doctor-specific to set up beyond
whatever you'd already pass to run the proxy itself. It never starts a
PG-wire or Oracle-wire listener.

```bash
# Same env vars and --metadata-path you'd use to actually run the proxy
# (see the Quick Start above) — doctor reads them exactly the same way.
FUSION_HOST=fa-xxxx.oraclecloud.com FUSION_AUTH_TYPE=sso \
  ./ofpgproxy doctor --offline --metadata-path ./metadata.db
```

```bash
./ofpgproxy doctor --offline                 # config + metadata.db only, zero network calls
./ofpgproxy doctor --offline --format json   # same, machine-readable
./ofpgproxy doctor --profiles                # print the Oracle-wire compatibility registry, zero Fusion calls
./ofpgproxy doctor --deep --timeout 2m       # add bounded multi-type + wide-column Fusion probes
```

Without `--offline`, `doctor` also runs a real, bounded `SELECT 1 FROM DUAL`
through the same auth/backend path the server uses — including opening a
browser for `--auth=sso` if that's your configured mode. `--deep` adds one
multi-type literal probe and one wide-column (300-column) probe, both
`SELECT ... FROM DUAL` only — no tenant table or column names are ever
touched. `--require-online` turns a skipped online check (e.g. Fusion
unreachable) into a failure instead of a pass-with-warnings.

Each of the 14 checks reports `pass`/`warn`/`fail`/`skip`; exit code is `0`
with no failures, `1` with at least one, `2` for a bad flag or setup error.
`oracle.compatibility` summarizes the same registry `--profiles` prints in
full.

## Observability

`--metrics-listen`/`OFPG_METRICS_LISTEN` (requires `--oracle-listen`) starts a
small HTTP server exposing Prometheus `/metrics`, plus `/healthz` (liveness)
and `/readyz` (readiness). See [Observability](observability.md).

### `.env` file

The proxy itself does **not** load `.env` automatically — source it before launching:

```bash
set -a; source .env; set +a
./ofpgproxy --metadata-path ./metadata.db
```

A `make run`–style wrapper script that loads `.env` and passes `--metadata-path` is typically two lines — see [Quick Start](quickstart.md) for an example.

## Debug / development

These are **not** for production.

| Variable | Effect |
|---|---|
| `OFPG_SOAP_DUMP=/path/to/file.xml` | Write every raw SOAP envelope to disk before parsing. Buffers the full response — do not leave enabled on large tables. Used when diagnosing a SOAP-shape mismatch. |
| `OFPG_LOG_FULL_SQL=1` | Disable the ~2000-char truncation on SQL logged in `msg=parse` and `msg="foreign exec failed"` lines. BI-tool-generated queries (Power BI custom SQL, wide `SELECT *` imports) routinely blow past the default cap, hiding the part that actually errors. Leave off otherwise — full statements can be very large. |

## SOAP concurrency

By default the proxy runs **one** SOAP call to BI Publisher at a time — every other foreign `SELECT` waits its turn. This is deliberate (it mirrors the `ofjdbc` setting): BI Publisher accumulates server-side sessions faster than it drains them, and an aggressive client easily pushes the tenant into refusing fresh logins.

For IDE-heavy use (DBeaver, DataGrip, DBVis tabs running queries in parallel), `1` becomes painful — a long `SELECT` blocks every other window. Raise the cap when you care more about responsiveness than minimising session pressure:

```bash
./ofpgproxy --oracle-listen 127.0.0.1:1521 --oracle-password changeme \
  --soap-concurrency 4 \
  --fusion-host fa-xxxx.oraclecloud.com --auth=sso
```

Or via env:

```bash
FUSION_SOAP_CONCURRENCY=4 make run
```

Sizing guide:

- **`1`** — conservative. Default. Use for batch / unattended runs (scheduled exports, reconciliation jobs) where wall-clock matters less than not annoying the tenant.
- **`2`–`4`** — typical for interactive use. Single SSO/password session, BIP usually treats parallel calls under the same session-cookie as the same logical user.
- **`>4`** — test on your own tenant first. Some Fusion releases throttle hard above small concurrency; you may see intermittent `WSM-07501` / login refusals before any benefit shows up.

Data-dictionary queries (`ALL_TABLES`, `ALL_TAB_COLUMNS`, … — the noisy IDE introspection traffic) **never** hit this gate; they're answered locally from the catalog. Only `SELECT`s against Fusion tables are serialised.

The active value shows in the startup banner under `soap`:

```
soap       1 concurrent call (serialised — ofjdbc default)
soap       4 concurrent calls
```

## Retry and backoff

Every BI Publisher SOAP call retries with exponential backoff and jitter on
transient failures — a 5xx/408/429 response, a connection reset, or a
timeout. A real `ORA-…` error or an auth failure is never retried (retrying
a malformed query or bad credentials just reproduces the same failure).
Defaults mirror `ofjdbc`'s own retry logic: 3 attempts total, 1s base delay,
2x growth, capped at 30s, +/-20% jitter.

Env-only (no CLI flag — these are tune-once-per-deployment settings):

| Env | Default | Description |
|---|---|---|
| `FUSION_SOAP_RETRY_MAX_ATTEMPTS` | `3` | Total attempts including the first try. |
| `FUSION_SOAP_RETRY_BASE_DELAY_MS` | `1000` | Delay before the first retry. |
| `FUSION_SOAP_RETRY_MAX_DELAY_MS` | `30000` | Backoff cap. |
| `FUSION_SOAP_RETRY_MULTIPLIER` | `2.0` | Delay growth per attempt. |

```bash
FUSION_SOAP_RETRY_MAX_ATTEMPTS=5 FUSION_SOAP_RETRY_MAX_DELAY_MS=10000 make run
```

Streaming foreign SELECTs (large tables fetched row-by-row instead of
buffered) only retry a failure that happens **before any row reached the
client** — once even one row has streamed out, a retry is skipped even for an
otherwise-retryable error, since resending the whole SOAP call would
redeliver rows the client already has.

## Protocol watchdog & diagnostics

The Oracle-wire frontend (`--oracle-listen`) tracks an explicit protocol
state per connection and can time out a connection that's stuck waiting on a
**strict, already-obligated** request/response step — most notably a real
Oracle database's own `dblink` shadow session that stalls right after the
session-sync `0x44` exchange, never sending the round-2 `EXECUTE` it's
supposed to.

**What counts as "strict"** (armed by `--oracle-protocol-timeout`, default
`30s`): every handshake stage (Connect, TTIPRO, TTIDTY, AUTH_PHASE_ONE/TWO),
and dblink's own round1 → session-sync(`0x44`) → round2 `EXECUTE` →
FETCH/repeat-EXECUTE chain.

**What is never timed out, no matter how long it sits idle:** an ordinary
authenticated interactive session (SQL Developer, SQLcl, `sqlplus` sitting
between statements) — the client hasn't been handed any specific obligation
to respond to, so there's nothing to time out. Waiting on the Fusion/BI
Publisher backend is also never subject to this timeout; that has its own,
separate timeout, and a client's BREAK/Ctrl-C still cancels an in-flight
backend call normally regardless of this setting.

Set `0` to disable the watchdog entirely.

```bash
./ofpgproxy --oracle-listen 127.0.0.1:1521 --oracle-password changeme \
  --oracle-protocol-timeout 15s
```

### Diagnostic bundles

When a connection is dropped for a genuine protocol reason — a watchdog
timeout, a recovered panic, a malformed message, or an unsupported protocol
transition — the proxy can write a small, self-contained `.zip` bundle to
`--oracle-diagnostic-dir` (off by default; timeout logging happens either
way). A bundle contains:

- `summary.json` — connection id, remote address, dialect, protocol state,
  what was expected next, the trigger, build version, and (for a SQL-bearing
  state) the query's length and a SHA-256 fingerprint — **never the SQL text
  itself, and never row values or credentials**.
- `events.jsonl` — a bounded (≤200 entries) rolling log of recent
  packet-level events for that connection.
- `c2s.tail.bin` / `s2c.tail.bin` — **only** when `--oracle-diagnostic-raw`
  is also set: the last 256 KiB of raw wire bytes in each direction. Off by
  default because raw bytes may contain query result values — only enable
  this temporarily, while actively reproducing an issue.

No bundle is written for a clean `LOGOFF`, a normal shutdown, an ordinary
client disconnect, or a backend query error already returned to the client
as a proper `ORA-…` response — only genuine protocol-diagnostic events
produce one. A failed bundle write is only logged (and counted in
`orawire_diagnostic_bundle_errors_total`) — it never affects the connection's
own error handling.

```bash
mkdir -p /var/log/ofpgproxy/diagnostics
./ofpgproxy --oracle-listen 127.0.0.1:1521 --oracle-password changeme \
  --oracle-diagnostic-dir /var/log/ofpgproxy/diagnostics
```

**Attaching a bundle to a bug report:** find the newest
`orawire-<timestamp>-conn-<id>-<trigger>.zip` in the diagnostic directory
matching the log line's own `diagnostic_bundle=` path, and attach it as-is —
see [Troubleshooting](troubleshooting.md) for the matching log line shape.

## SQL Translator playground

A small built-in web page that shows, statement by statement, what the proxy *would* do with a given SQL — which router branch it lands in (catalog stub, foreign SELECT, cursor, session no-op, …) and the rewritten SQL that would go to BI Publisher or to the local DuckDB catalog. No connection to a Fusion tenant is required; translation is entirely offline.

```bash
./ofpgproxy --oracle-listen 127.0.0.1:1521 --oracle-password changeme --translate-http 127.0.0.1:8080
```

Then open <http://127.0.0.1:8080> in a browser. `make run` enables it by default on `127.0.0.1:8080`; disable with `make run TRANSLATE_HTTP=`.

The page also exposes a JSON endpoint suitable for scripting:

```bash
curl -sS http://127.0.0.1:8080/api/translate \
  -H 'Content-Type: application/json' \
  -d '{"sql":"SELECT * FROM gl_je_headers WHERE TRUE LIMIT 5"}'
```

Response shape: `{ "translated": "...", "kind": "foreign_select", "note": "Routed to Oracle BI Publisher (table: GL_JE_HEADERS).", "error": "" }`.

**Safety notes — read before exposing this beyond `localhost`:**

- The endpoint has **no authentication** and accepts arbitrary SQL strings. Bind only to loopback. If `--translate-http` resolves to a non-loopback address, the proxy logs a `WARNING` at startup.
- Each request is capped at **64 KB** of body. Per-IP rate limit is **10 req/sec** (token bucket, same refill rate).
- Translation is **purely textual** — the playground never opens a SOAP connection, never reads `metadata.db`, and never returns rows. It only shows the rewriting decision.
- Every translate call emits one structured log line (`msg=translate kind=… in_bytes=… out_bytes=…`) so operators can see what people are sending; treat the page as a developer tool, not a public service.

## Signals

| Signal | Action |
|---|---|
| `SIGHUP` | Atomically reload `metadata.db`. Previous catalog stays served for a short grace period so in-flight queries complete cleanly. |
| `SIGTERM`, `SIGINT` | Graceful shutdown. The listener stops accepting new connections; existing SOAP calls are allowed to finish with up to the configured SOAP read timeout. |

## Defaults you probably don't need to touch

- `--foreign-batch-size` — 200 is calibrated for DBeaver "Result Set Row Limit" ergonomics. Raise only if you routinely page past the first screen.
- SOAP connect/read timeouts (30 s / 120 s) are compiled in. If your BIP report genuinely needs more than two minutes, your query is probably too wide — add `LIMIT` first.
