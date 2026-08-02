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

Five lines are enough to start. Pick the auth mode you already have:

**With a Fusion username and password** — nothing opens, nothing to click:

```
FUSION_HOST=fa-xxxx.oraclecloud.com
FUSION_AUTH_TYPE=password
FUSION_USER=your.fusion.user
FUSION_PASSWORD=your-fusion-password
OFPG_ORACLE_LISTEN=127.0.0.1:1521
ORACLE_WIRE_PASSWORD=changeme
```

**With single sign-on** — a browser window opens once per run for your usual
Fusion login:

```
FUSION_HOST=fa-xxxx.oraclecloud.com
FUSION_AUTH_TYPE=sso
OFPG_ORACLE_LISTEN=127.0.0.1:1521
ORACLE_WIRE_PASSWORD=changeme
```

Four more modes exist for unattended running — see
[Authentication](auth.md).

**Two different passwords live in that file, and mixing them up is the
commonest first stumble.** `FUSION_PASSWORD` is your real Fusion account
password, used to reach the tenant. `ORACLE_WIRE_PASSWORD` is a value **you
invent** here and now: it is what every Oracle client will log in to the
proxy with, and it is checked against nothing in your tenant.

Keep `.env` beside the binary. The proxy reads it on its own; there is nothing
to export or source first.

## 2. Run it

```bash
./oratofusionproxy            # macOS / Linux
```

```powershell
.\oratofusionproxy.exe        # Windows
```

With `FUSION_AUTH_TYPE=sso` a Chrome window opens for your usual Fusion
login; with `password` there is nothing to click. Either way:

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

### Where each connection field comes from

Every Oracle client asks for the same four things. Two of them come straight
out of your `.env`, and two are free:

| Client field | Where it comes from |
|---|---|
| **Hostname** / **Port** | `OFPG_ORACLE_LISTEN` — `127.0.0.1:1521` in the example above |
| **Password** | **`ORACLE_WIRE_PASSWORD`, exactly.** This is the one that catches people: it is not your Fusion password and not your Oracle password — it is the value you invented in `.env`. A wrong one is rejected at login. |
| **Username** | Anything. `fusion` reads best, but the value is not checked and does not affect what you see — every object is reported under one logical schema, `FUSION`, whoever you log in as. |
| **Service name** | Anything. It is ignored. Pick **Service name**, though, not SID. |

Real access control is the Fusion session the proxy holds underneath — the
username and password above are the proxy's own door, not the tenant's.

Read-only, by design: BI Publisher cannot write, so the proxy rejects anything
that would.

## Beyond the first query

Everything above is the short path. The rest of this page is for when you need
more than it.

### Other clients

**SQL Developer / DBeaver** — New Connection → Oracle, then fill the four
fields from [the table above](#where-each-connection-field-comes-from). Both
use the pure-Java ojdbc thin driver, so no Oracle Instant Client is involved.
Double-click a table and rows stream into the grid.

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
