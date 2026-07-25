# Quick Start

From zero to a working query against Fusion in five minutes, from any Oracle
client — SQLcl, DBeaver, SQL Developer, ojdbc, python-oracledb, or another
database's `dblink`.

## 1. Prerequisites

- macOS (Apple Silicon), Windows (x86_64), or Linux (x86_64).
- Chrome / Chromium on `PATH` (only needed for SSO auth mode).
- A Fusion Cloud tenant with the `RP_ARB.xdo` BI Publisher report deployed (download from [krokozyab/ofjdbc/otbireport](https://github.com/krokozyab/ofjdbc/tree/master/otbireport)) — `/Custom/Financials/RP_ARB.xdo` is the proxy's default, but use whatever path yours ended up at; see [Fusion prerequisites](fusion-prerequisites.md).
- An Oracle client — see the [full client recipes](clients.md). **SQLcl** is the easiest to get: a single download, needs only a Java runtime. SQL Developer and DBeaver work out of the box too — they use the pure-Java ojdbc thin driver, so no Oracle Instant Client is involved. Only `sqlplus` needs one.

## 2. Get the artefacts

Grab the archive for your platform from the [latest GitHub release](https://github.com/krokozyab/oracle-fusion-tns-proxy/releases/latest) — double-click to extract on macOS Finder or Windows Explorer, no `tar`, no `zstd`.

| Download | Contents |
|---|---|
| `ofpgproxy_<version>_darwin_arm64.zip` *(macOS)*, `ofpgproxy_<version>_windows_amd64.zip` *(Windows)*, or `ofpgproxy_<version>_linux_amd64.zip` *(Linux, incl. WSL)* | The binary and `.env.example` |
| `ofpgproxy-catalog_<version>.zip` *(optional)* | A pre-built Fusion catalog. Only worth taking if you'd rather not have the proxy build its own — it fills one on demand either way. See [Metadata catalog](metadata.md). |

On macOS, run `chmod +x ofpgproxy` if Finder dropped the executable bit, and `xattr -d com.apple.quarantine ofpgproxy` to clear the Gatekeeper flag on first run.

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
FUSION_SQL_REPORT_PATH=/Custom/Financials/RP_ARB.xdo

# Auth mode: sso | password | token-file | token-refresh | client-credentials | jwt-assertion
FUSION_AUTH_TYPE=sso
```

Turn on the Oracle listener — the proxy will not accept clients without it:

```
# Oracle listener (SQLcl, DBeaver, SQL Developer, ojdbc, dblink):
OFPG_ORACLE_LISTEN=127.0.0.1:1521
ORACLE_WIRE_PASSWORD=changeme   # YOU choose this value — every Oracle client logs in with it; required once OFPG_ORACLE_LISTEN is set

```

Full reference: [Configuration](configuration.md) (every flag/env var, including `OFPG_METRICS_LISTEN` and SOAP concurrency/retry tuning) · [Authentication](auth.md) (all six modes)

## 4. (Optional) Validate before launching

`./ofpgproxy doctor` checks your `.env` and `--oracle-listen` config, opens
the metadata catalog and reports whether it is populated, and — unless you
pass `--offline` — runs one real, bounded `SELECT 1 FROM DUAL` through Fusion,
all without starting the proxy itself. Same env vars and flags as step 5:

```bash
set -a; source .env; set +a
./ofpgproxy doctor --offline
```

A clean run prints `Result: PASS`. Warnings are usually fine — an empty
catalog on a first run is expected, and unverified client/dialect combinations
are flagged rather than hidden. See
[Configuration → `ofpgproxy doctor`](configuration.md#ofpgproxy-doctor) for
every flag and what each check does.

## 5. Launch

### macOS / Linux (incl. WSL)

```bash
set -a; source .env; set +a
./ofpgproxy
```

### Windows (PowerShell)

PowerShell doesn't source `.env` files natively — load the variables first, then launch:

```powershell
Get-Content .env | ForEach-Object {
  if ($_ -match '^\s*([^#=]+)=(.*)$') { Set-Item "Env:$($Matches[1].Trim())" $Matches[2].Trim() }
}
.\ofpgproxy.exe
```

Expected output:

```
time=... level=INFO msg="metadata cache opened at ./metadata-cache.db (writable, no seed; data-dictionary emulation on, SIGHUP reloads)"
time=... level=INFO msg="SSO: opening Chrome for Fusion login (300s timeout)"
time=... level=INFO msg="Oracle-wire (TNS) listening on 127.0.0.1:1521"
```

On first SSO run the Chrome window points at your IdP — log in as you normally would. The captured token is held in-process until the proxy exits.

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

SQL Developer and DBeaver connect the same way with no extra install — both
use the pure-Java ojdbc thin driver. See
[Connecting Oracle clients](clients.md#oracle-clients-sqlcl-dbeaver-sql-developer)
for their connection fields and `dblink` setup. `sqlplus` works too, but it is
the one client that does need a full Oracle Instant Client, and SQLcl above is
its modern replacement.

### DBeaver

1. New Connection → **Oracle**.
2. Host `127.0.0.1`, Port `1521` (your `OFPG_ORACLE_LISTEN` port),
   **Service name** `fusion` (any value works — just pick Service name, not SID).
3. Username **`FUSION`** — uppercase. Any username authenticates, but only
   `FUSION` makes the Tables/Views tree populate: the tree filters by
   `OWNER = <your username>` client-side, and every object the proxy reports
   is owned by the single logical schema `FUSION`.
4. Password — **your `ORACLE_WIRE_PASSWORD` value** from `.env` (`changeme` in
   the example above). A wrong password is rejected.
5. Test connection, finish. Tables appear under the `FUSION` schema.

Double-click any table → rows stream into the result grid. The same rules
apply to any other Oracle-driver tool (DataGrip, SQL Developer, custom JDBC) —
see [Connecting clients](clients.md).

### Other clients

- [A real Oracle database's own `dblink`](clients.md#oracle-dblink-a-real-oracle-database-as-the-client) — reconciliation scripts, migration validation, ad-hoc cross-database queries
- [ojdbc / python-oracledb](clients.md#code-clients) — code integration

## What's next

- **[`ofpgproxy doctor --profiles`](configuration.md#ofpgproxy-doctor)** — see exactly which Oracle client/dialect/feature combinations this build has verified.
- **[Testing & verification](testing.md)** — what is actually verified, against which Oracle versions and clients.
- **[Troubleshooting](troubleshooting.md)** — for the first time you hit an `ORA-…` message.
- **[Configuration](configuration.md)** — every flag and env var the proxy honours.
