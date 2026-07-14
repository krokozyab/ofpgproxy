# Quick Start

From zero to a working query against Fusion in five minutes — over Oracle
SQL*Net (`sqlplus`, SQL Developer, SQLcl, `dblink`), PostgreSQL wire, or both
at once.

## 1. Prerequisites

- macOS (Apple Silicon), Windows (x86_64), or Linux (x86_64).
- Chrome / Chromium on `PATH` (only needed for SSO auth mode).
- A Fusion Cloud tenant with the `RP_ARB.xdo` BI Publisher report deployed (download from [krokozyab/ofjdbc/otbireport](https://github.com/krokozyab/ofjdbc/tree/master/otbireport)) — typically under `/Custom/sql/RP_ARB.xdo` or `/Custom/Financials/RP_ARB.xdo`.
- At least one client: an Oracle client and/or a PG-wire client (`psql`, DBeaver) — see the [full client recipes](clients.md) either way. For Oracle, **SQLcl** is the easiest to get (a single download, needs only a Java runtime); `sqlplus` and SQL Developer need a full Oracle Instant Client install separately.

## 2. Get the artefacts

Grab them from the [latest GitHub release](https://github.com/krokozyab/ofpgproxy/releases/latest). Two zip files, double-click to extract on macOS Finder or Windows Explorer — no `tar`, no `zstd`.

| Download | Contents |
|---|---|
| `ofpgproxy_<version>_darwin_arm64.zip` *(macOS)*, `ofpgproxy_<version>_windows_amd64.zip` *(Windows)*, or `ofpgproxy_<version>_linux_amd64.zip` *(Linux, incl. WSL)* | The binary, `.env.example`, `LICENSE`, mini-README |
| `ofpgproxy-catalog_<version>.zip` | `metadata.db` — pre-built Fusion catalog (~30 000 tables, ~160 MB uncompressed). Same file across platforms. |

Drop both extracted folders into the same working directory; move `metadata.db` next to the binary. On macOS, run `chmod +x ofpgproxy` if Finder dropped the executable bit, and `xattr -d com.apple.quarantine ofpgproxy` to clear the Gatekeeper flag on first run.

Verify the download (optional but recommended) — `SHA256SUMS` is on the same release page:

```
shasum -a 256 -c SHA256SUMS --ignore-missing      # macOS / Linux
Get-FileHash *.zip -Algorithm SHA256              # Windows PowerShell
```

## 3. Configure

The release ships a complete `.env.example` — every listener, all six auth
modes, SOAP tuning, debug switches. Copy it rather than typing from scratch:

```bash
cp .env.example .env
```

Then edit `.env` down to what you actually need. At minimum, the tenant and
auth mode:

```
# Tenant
FUSION_HOST=fa-xxxx.oraclecloud.com
FUSION_SQL_REPORT_PATH=/Custom/sql/RP_ARB.xdo

# Auth mode: sso | password | token-file | token-refresh | client-credentials | jwt-assertion
FUSION_AUTH_TYPE=sso
```

Pick which wire(s) to turn on — both can run from the same process:

```
# Oracle SQL*Net (sqlplus, SQL Developer, SQLcl, dblink):
OFPG_ORACLE_LISTEN=127.0.0.1:1521
ORACLE_WIRE_PASSWORD=changeme   # YOU choose this value — every Oracle client logs in with it; required once OFPG_ORACLE_LISTEN is set

# PostgreSQL wire (psql, DBeaver, Metabase, dbt, ...):
# OFPG_LISTEN=127.0.0.1:5433   # this is already the default; only set it to override
```

Full reference: [Configuration](configuration.md) (every flag/env var, including `OFPG_METRICS_LISTEN` and SOAP concurrency/retry tuning) · [Authentication](auth.md) (all six modes)

## 4. (Optional) Validate before launching

`./ofpgproxy doctor` checks your `.env`/`--metadata-path`/`--oracle-listen`
config, opens `metadata.db`, and — unless you pass `--offline` — runs one
real, bounded `SELECT 1 FROM DUAL` through Fusion, all without starting the
proxy itself. Same env vars and flags as step 5 below:

```bash
set -a; source .env; set +a
./ofpgproxy doctor --offline --metadata-path ./metadata.db
```

A clean run prints `Result: PASS` (warnings are fine — they flag unverified
Oracle-wire client combinations, not broken config). See
[Configuration → `ofpgproxy doctor`](configuration.md#ofpgproxy-doctor) for
every flag and what each of its 14 checks does.

## 5. Launch

### macOS / Linux (incl. WSL)

```bash
set -a; source .env; set +a
./ofpgproxy --metadata-path ./metadata.db
```

### Windows (PowerShell)

PowerShell doesn't source `.env` files natively — load the variables first, then launch:

```powershell
Get-Content .env | ForEach-Object {
  if ($_ -match '^\s*([^#=]+)=(.*)$') { Set-Item "Env:$($Matches[1].Trim())" $Matches[2].Trim() }
}
.\ofpgproxy.exe --metadata-path .\metadata.db
```

Expected output (with `OFPG_ORACLE_LISTEN` set — omit that line if you left it off):

```
time=... level=INFO msg="pg_catalog emulation enabled, reading ./metadata.db (SIGHUP reloads)"
time=... level=INFO msg="SSO: opening Chrome for Fusion login (300s timeout)"
time=... level=INFO msg="ofpgproxy listening on 127.0.0.1:5433"
time=... level=INFO msg="Oracle-wire (TNS) listening on 127.0.0.1:1521"
```

On first SSO run the Chrome window points at your IdP — log in as you normally would. The captured token is held in-process and shared by both wires until the proxy exits.

## 6. Run your first query

### SQLcl

The quickest Oracle client to get — a single download, no separate Instant
Client install needed (it bundles its own JDBC driver; only a Java runtime is
required):

```bash
sql FUSION/changeme@//127.0.0.1:1521/fusion
```

```sql
SELECT period_name, period_year
FROM   gl_periods
WHERE  period_year = 2024
AND    ROWNUM <= 5;
```

The three parts of that connect string:

- **`FUSION`** — the username. Any username authenticates (access control is
  the Fusion session the proxy holds underneath), but use `FUSION` (uppercase)
  as your habit: IDE catalog browsing (the SQL Developer / DBeaver object
  tree) only lines up when the connected username matches the single logical
  schema `FUSION` that every object is reported under.
- **`changeme`** — the password: **exactly what you set `ORACLE_WIRE_PASSWORD`
  to** in `.env` (step 3). A wrong value is rejected at login.
- **`fusion`** after the `/` — the service name; any value works, it is ignored.

`sqlplus` and SQL Developer connect the same way once you've installed a full
Oracle Instant Client (a separate download from Oracle, not bundled with
either tool) — see [Connecting Oracle clients](clients.md#oracle-clients-sql-developer-sqlcl-sqlplus)
for those connection fields and `dblink` setup.

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

Username and database are placeholders — the PG wire accepts any values and
never asks for a password. (Only the Oracle wire above validates its
password; the two wires' credential rules are different.)

### DBeaver

Works over either wire — pick one. The credentials differ between the two, so
double-check which driver you selected:

**Over the PostgreSQL wire** (simplest):

1. New Connection → **PostgreSQL** (built-in driver).
2. Host `127.0.0.1`, Port `5433`, Database `any`, User / Password — any values
   (the PG wire doesn't validate them).
3. **Driver properties** → `preferQueryMode` = `simple`. (Optional but avoids
   binary-format parameter edge cases.)
4. Test connection, finish. Tables appear under `public` (or per-module
   schemas — `fscm`, `hcm`, `crm` — when the module is present in `metadata.db`).

**Over the Oracle wire:**

1. New Connection → **Oracle**.
2. Host `127.0.0.1`, Port `1521` (your `OFPG_ORACLE_LISTEN` port),
   **Service name** `fusion` (any value works — just pick Service name, not SID).
3. Username **`FUSION`** — uppercase. Any username authenticates, but only
   `FUSION` makes the Tables/Views tree populate: the tree filters by
   `OWNER = <your username>` client-side, and every object the proxy reports
   is owned by the single logical schema `FUSION`.
4. Password — **your `ORACLE_WIRE_PASSWORD` value** from `.env` (`changeme` in
   the example above). Unlike the PG wire, the Oracle wire rejects a wrong
   password.
5. Test connection, finish. Tables appear under the `FUSION` schema.

Either way: double-click any table → rows stream into the result grid. The
same two credential rules apply to any other Oracle-driver tool (DataGrip,
SQL Developer, custom JDBC) — see [Connecting clients](clients.md).

### Other clients

- [A real Oracle database's own `dblink`](clients.md#oracle-dblink-a-real-oracle-database-as-the-client) — reconciliation scripts, migration validation, ad-hoc cross-database queries
- [postgres_fdw](clients.md#postgres_fdw) — expose Fusion tables inside another PG
- [pgx / psycopg / pgJDBC / ojdbc / python-oracledb](clients.md#code-clients) — code integration on either wire

## What's next

- **[`ofpgproxy doctor --profiles`](configuration.md#ofpgproxy-doctor)** — see exactly which Oracle client/dialect/feature combinations this build has verified.
- **[SQL compatibility](sql-compat.md)** — see which PG idioms get auto-rewritten and what edge cases to watch for.
- **[Troubleshooting](troubleshooting.md)** — for the first time you hit an `ORA-…` message.
- **[Configuration](configuration.md)** — every flag and env var the proxy honours.
