# Quick Start

Three files in one folder, and a query running against Fusion.

```
oratofusionproxy(.exe)     the binary
metadata-cache.db          the Fusion catalog, so browsing works immediately
.env                       four lines: your tenant and your password
```

Download the archive for your platform from the
[latest release](https://github.com/krokozyab/oracle-fusion-tns-proxy/releases/latest),
extract it, and everything above is already there — `.env.example` included, to
copy as `.env`.

## 1. Fill in `.env`

Four lines are enough to start:

```
FUSION_HOST=fa-xxxx.oraclecloud.com
FUSION_AUTH_TYPE=sso
OFPG_ORACLE_LISTEN=127.0.0.1:1521
ORACLE_WIRE_PASSWORD=changeme
```

`ORACLE_WIRE_PASSWORD` is a value **you invent**. It is what every Oracle
client will log in with — it is not a Fusion password and it is not checked
against anything in your tenant.

Keep `.env` beside the binary. The proxy reads it on its own; there is nothing
to export or source first.

## 2. Run it

```bash
./oratofusionproxy            # macOS / Linux
```

```powershell
.\oratofusionproxy.exe        # Windows
```

With `FUSION_AUTH_TYPE=sso` a Chrome window opens for your usual Fusion login.
Then:

```
Oracle-wire (TNS) listening on 127.0.0.1:1521
```

That is the whole setup.

## 3. Run your first query

Any Oracle client, pointed at that host and port. [SQLcl](https://www.oracle.com/database/sqldeveloper/technologies/sqlcl/download/)
is the quickest to get — one download, needs only a Java runtime:

```bash
sql FUSION/changeme@//127.0.0.1:1521/fusion
```

```sql
SELECT invoice_num, invoice_date, invoice_amount
FROM   ap_invoices_all
WHERE  ROWNUM <= 10;
```

The first query of a session takes seconds: it is a SOAP call to BI Publisher.
Later ones are quicker.

Use **`FUSION`** as the username, in uppercase. Any username authenticates —
real access control is the Fusion session the proxy holds underneath — but the
object tree in SQL Developer and DBeaver filters by owner, and every object is
reported under the single schema `FUSION`. The service name after the `/` is
ignored; any value works.

Read-only, by design: BI Publisher cannot write, so the proxy rejects anything
that would.

## Beyond the first query

Everything above is the short path. The rest of this page is for when you need
more than it.

### Other clients

**SQL Developer / DBeaver** — New Connection → Oracle, host `127.0.0.1`, port
`1521`, **Service name** `fusion` (pick Service name, not SID), username
`FUSION`, password your `ORACLE_WIRE_PASSWORD`. Both use the pure-Java ojdbc
thin driver, so no Oracle Instant Client is involved. Double-click a table and
rows stream into the grid.

`sqlplus` works too, and is the one client that does need a full Instant
Client. SQLcl is its modern replacement.

- [A real Oracle database's own `dblink`](clients.md#oracle-dblink-a-real-oracle-database-as-the-client) — reconciliation, migration checks, cross-database queries
- [ojdbc / python-oracledb and other code clients](clients.md#code-clients)
- [Connecting clients](clients.md) — every tool, with its connection fields

### Check the setup without starting the proxy

`doctor` reads the same `.env`, opens the catalog, reports whether it is
populated, and — unless you pass `--offline` — runs one bounded
`SELECT 1 FROM DUAL` against Fusion. It never binds a listener.

```bash
./oratofusionproxy doctor --offline
```

A clean run prints `Result: PASS`. Warnings are usually fine; unverified
client/dialect combinations are flagged rather than hidden.
[What each check does](configuration.md#oratofusionproxy-doctor) ·
`doctor --profiles` prints exactly which client/feature combinations this
build has verified.

### The metadata catalog

`metadata-cache.db` is what makes a schema tree appear instantly instead of
each expansion waiting on a SOAP call. The release ships one so the first
experience is a fast one.

It is not required. Delete it and the proxy creates its own, filling it from
your tenant as queries arrive — the first browse is simply slower. It is also
yours to rebuild: your tenant has objects no shipped catalog can know about
(flexfields, custom tables), and a full warm is one command. See
[Metadata catalog](metadata.md).

### Authentication

`sso` opens a browser and holds the token in memory until the proxy exits.
Five other modes exist for unattended running — `password`, `token-file`,
`token-refresh`, `client-credentials`, `jwt-assertion`. See
[Authentication](auth.md).

### Prerequisites, in full

- macOS (Apple Silicon), Windows (x86_64), or Linux (x86_64).
- Chrome or Chromium on `PATH`, for `sso` mode only.
- The `RP_ARB.xdo` BI Publisher report deployed in your tenant
  ([download](https://github.com/krokozyab/ofjdbc/tree/master/otbireport)).
  `/Custom/Financials/RP_ARB.xdo` is the default; set
  `FUSION_SQL_REPORT_PATH` if yours is elsewhere. See
  [Fusion prerequisites](fusion-prerequisites.md).

On macOS, if Finder dropped the executable bit or Gatekeeper flagged the
download:

```bash
chmod +x oratofusionproxy
xattr -d com.apple.quarantine oratofusionproxy
```

Verifying the download (optional) — `SHA256SUMS` is on the release page:

```
shasum -a 256 -c SHA256SUMS --ignore-missing      # macOS / Linux
Get-FileHash *.zip -Algorithm SHA256              # Windows PowerShell
```

### More

- **[Configuration](configuration.md)** — every flag and env var, listeners, SOAP tuning, metrics
- **[Troubleshooting](troubleshooting.md)** — the first time you meet an `ORA-…`
- **[Testing & verification](testing.md)** — what is verified, against which clients
