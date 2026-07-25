# Connecting clients

Oracle clients connect over TNS/TTC on `--oracle-listen` (there is no default —
set it, plus `--oracle-password`). Examples below assume the proxy on
`127.0.0.1:1521`.

## Oracle clients (SQL Developer, SQLcl, sqlplus)

Start the proxy with `--oracle-listen` (and `--oracle-password`) — see
[Configuration → Oracle-wire frontend](configuration.md#oracle-wire-frontend) —
and Oracle tooling connects on that port. Read-only: the proxy rejects any
write.

**Credentials — what to enter.** There is one shared password, **you choose**
it and set it with `--oracle-password` / `ORACLE_WIRE_PASSWORD`, and the client
must send that exact value:

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

## GUI clients (DBeaver, DataGrip, JetBrains IDEs)

Create an **Oracle** connection and use the same fields as
[SQL Developer above](#sql-developer): Username **`FUSION`** (anything else
authenticates but leaves the Tables/Views tree empty), Password = your
`--oracle-password` / `ORACLE_WIRE_PASSWORD` value, host/port from
`--oracle-listen`, service name anything.

- Tables appear under the single logical schema `FUSION`.
- Column lists, `DESCRIBE` and autocomplete are answered from the local
  metadata catalog, so tree browsing costs no BI Publisher calls.
- Result grids paginate: the first page (default 200 rows) arrives in seconds
  even on large tables, and more pages fetch as you scroll. Adjust with
  `--foreign-batch-size`.

## Code clients

### python-oracledb (thin)

```python
import oracledb

dsn = oracledb.makedsn("127.0.0.1", 1521, service_name="fusion")
with oracledb.connect(user="FUSION", password="secret", dsn=dsn) as conn:
    cur = conn.cursor()
    cur.execute("SELECT period_name, period_year FROM gl_periods WHERE ROWNUM <= 5")
    for row in cur:
        print(row)
```

Thin mode only — no Instant Client needed. Bind variables (positional and
named) work.

### ojdbc (Java / Kotlin)

```java
var url = "jdbc:oracle:thin:@//127.0.0.1:1521/fusion";
try (var conn = DriverManager.getConnection(url, "FUSION", "secret");
     var st = conn.createStatement();
     var rs = st.executeQuery("SELECT period_name FROM gl_periods WHERE ROWNUM <= 5")) {
    while (rs.next()) System.out.println(rs.getString(1));
}
```

The thin driver is what DBeaver, SQL Developer and SQLcl use underneath, so
anything that works there works here.

## Pagination

| Client kind | Behaviour | How to change |
|---|---|---|
| GUI clients (DBeaver, DataGrip, SQL Developer, …) | OFFSET/FETCH batching — first page fast, more pages on scroll | `--foreign-batch-size N` (0 disables) |
| Code clients, `sqlplus`, `dblink` | Single SOAP call for the whole result | Add `ROWNUM <= n` or `FETCH FIRST n ROWS ONLY` in the query |

## Troubleshooting

See [Troubleshooting](troubleshooting.md) for specific client errors: slow
queries, empty schema trees, type mismatches, and more.
