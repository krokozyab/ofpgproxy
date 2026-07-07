# Configuration

`ofpgproxy` takes configuration from two places:

1. Command-line flags (`./ofpgproxy --listen :5433 …`).
2. Environment variables (or a `.env` file sourced before launch).

Flags always win over environment variables. A few parameters are available only one way — noted below.

## Command-line flags

### Listeners

| Flag | Env | Default | Description |
|---|---|---|---|
| `--listen` | `OFPG_LISTEN` | `127.0.0.1:5433` | `host:port` the PG-wire (Postgres) listener binds to. |
| `--oracle-listen` | `OFPG_ORACLE_LISTEN` | off | `host:port` for a second, read-only **Oracle-wire (TNS/TTC)** listener so Oracle clients (SQLcl, python-oracledb, ojdbc) connect too. Shares the SOAP backend + metadata catalog. See [Oracle-wire frontend](#oracle-wire-frontend). |
| `--oracle-password` | `ORACLE_WIRE_PASSWORD` | — | Shared password the Oracle-wire O5LOGON handshake accepts (any username). **Required** with `--oracle-listen`. |
| `--metrics-listen` | `OFPG_METRICS_LISTEN` | off | `host:port` for the ops HTTP server: Prometheus `/metrics` + `/healthz` + `/readyz`. Requires `--oracle-listen`. No auth — bind to loopback. See [Observability](observability.md). |

### Backend

| Flag | Env | Default | Description |
|---|---|---|---|
| `--fusion-host` | `FUSION_HOST` | — | Fusion tenant hostname. **No protocol, no path.** Example: `fa-xxxx.oraclecloud.com`. |
| `--report-path` | `FUSION_SQL_REPORT_PATH` | `/Custom/Financials/RP_ARB.xdo` | BI Publisher report absolute path. Most tenants use `/Custom/sql/RP_ARB.xdo`. |
| `--metadata-path` | — | — | Path to `metadata.db`. Without it `pg_catalog` queries return empty — DBeaver trees look empty, `\d` returns nothing, but foreign SELECTs still work. Enables metadata-driven column typing on the Oracle-wire side. |
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
a second, **read-only** listener speaking the Oracle TNS/TTC protocol, so
Oracle clients — SQLcl, python-oracledb, ojdbc/JDBC — connect alongside the
PG-wire port. It shares the same SOAP backend, auth and metadata catalog. With
`--metadata-path` set, columns describe with their real Oracle types
(NUMBER/DATE/TIMESTAMP/RAW/CLOB/BLOB/…) resolved from the catalog. Both
frontends run in one process and drain together on shutdown.

```bash
./ofpgproxy \
  --listen 127.0.0.1:5433 \
  --oracle-listen 127.0.0.1:1521 --oracle-password secret \
  --metadata-path ./metadata.db
# psql on :5433, sqlplus/sqlcl/python-oracledb on :1521
```

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
./ofpgproxy --listen 127.0.0.1:5433 --metadata-path ./metadata.db \
  --soap-concurrency 4 \
  --fusion-host fa-xxxx.oraclecloud.com --auth=sso
```

Or via env:

```bash
FUSION_SOAP_CONCURRENCY=4 make run
```

Sizing guide:

- **`1`** — conservative. Default. Use for batch / unattended runs (dbt, scheduled exports) where wall-clock matters less than not annoying the tenant.
- **`2`–`4`** — typical for interactive use. Single SSO/password session, BIP usually treats parallel calls under the same session-cookie as the same logical user.
- **`>4`** — test on your own tenant first. Some Fusion releases throttle hard above small concurrency; you may see intermittent `WSM-07501` / login refusals before any benefit shows up.

`pg_catalog` / `information_schema` queries (the noisy IDE introspection traffic) **never** hit this gate — they're answered locally from DuckDB. Only foreign `SELECT`s against Fusion tables are serialised.

The active value shows in the startup banner under `soap`:

```
soap       1 concurrent call (serialised — ofjdbc default)
soap       4 concurrent calls
```

## SQL Translator playground

A small built-in web page that shows, statement by statement, what the proxy *would* do with a given SQL — which router branch it lands in (catalog stub, foreign SELECT, cursor, session no-op, …) and the rewritten SQL that would go to BI Publisher or to the local DuckDB catalog. No connection to a Fusion tenant is required; translation is entirely offline.

```bash
./ofpgproxy --listen 127.0.0.1:5433 --translate-http 127.0.0.1:8080
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
