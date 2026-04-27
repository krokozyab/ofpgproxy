# Configuration

`ofpgproxy` takes configuration from two places:

1. Command-line flags (`./ofpgproxy --listen :5433 …`).
2. Environment variables (or a `.env` file sourced before launch).

Flags always win over environment variables. A few parameters are available only one way — noted below.

## Command-line flags

| Flag | Default | Description |
|---|---|---|
| `--listen` | `127.0.0.1:5433` | `host:port` the PG-wire listener binds to. |
| `--fusion-host` | — | Fusion tenant hostname. **No protocol, no path.** Example: `fa-xxxx.oraclecloud.com`. |
| `--report-path` | `/Custom/Financials/RP_ARB.xdo` | BI Publisher report absolute path. Most tenants use `/Custom/sql/RP_ARB.xdo`. |
| `--auth` | — | Authentication mode: `password`, `token-file`, `sso`. Required when `--fusion-host` is set. See [Authentication](auth.md). |
| `--auth-user` | — | Fusion username, only for `--auth=password`. |
| `--auth-password` | — | Fusion password, only for `--auth=password`. Consider using env var instead. |
| `--auth-token-file` | — | Path to bearer-token file, only for `--auth=token-file`. |
| `--sso-timeout` | `300` | SSO browser-login timeout in seconds. |
| `--metadata-path` | — | Path to `metadata.db`. Without it `pg_catalog` queries return empty — DBeaver trees look empty, `\d` returns nothing, but foreign SELECTs still work. |
| `--foreign-batch-size` | `200` | Rows per SOAP call when an **IDE client** runs a foreign SELECT without `LIMIT`. `0` disables auto-pagination entirely (code clients always get a single shot). See [Clients — pagination](clients.md#pagination). |
| `--log-queries` | `true` | Emit one structured log entry per incoming query (kind, table, duration). Set `--log-queries=false` in production after tuning. |
| `--translate-http` | — *(off)* | `host:port` for the built-in SQL Translator playground. Off by default; suggested value `127.0.0.1:8080`. Loopback only — see [SQL Translator playground](#sql-translator-playground). |

## Environment variables

Recognised on every launch; overridden by flags of the same purpose.

| Variable | Equivalent flag | Notes |
|---|---|---|
| `FUSION_HOST` | `--fusion-host` | |
| `FUSION_SQL_REPORT_PATH` | `--report-path` | |
| `FUSION_AUTH_TYPE` | `--auth` | Values match the flag. |
| `FUSION_USER` | `--auth-user` | |
| `FUSION_PASSWORD` | `--auth-password` | Preferred over the flag — keeps password out of the process table. |

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
