# Reading Fusion from EBS R12 over `dblink`

The case this was built for: an E-Business Suite R12 database that needs to
read Fusion — reconciliation during a coexistence period, validating a
migration, or joining Fusion data to something that still lives in EBS. No
staging tables, no nightly extract, no BI Publisher report per question.

```sql
SELECT h.segment1, f.invoice_num
FROM   ap_invoices_all h,                       -- your EBS table
       ap_invoices_all@fusion_saas f            -- the same table in Fusion
WHERE  h.invoice_num = f.invoice_num
AND    ROWNUM <= 100;
```

## You do not need a Database Gateway

Start here, because it is the assumption that costs people a day.

`dg4odbc`, `dg4msql`, Heterogeneous Services, `HS_FDS_CONNECT_INFO`,
`init<sid>.ora` gateway entries — **none of that applies.** Those exist to
reach *non-Oracle* targets, and they change how SQL is written and what comes
back.

The proxy speaks the Oracle wire protocol itself. To your R12 database it is
an Oracle database: a plain `CREATE DATABASE LINK`, a plain TNS descriptor,
ordinary `table@link` syntax. Nothing to install on the database host.

## 1. Run the proxy where the EBS database can reach it

This is the only genuinely fiddly part, and it is a networking question, not
an Oracle one. The EBS database opens an outbound TCP connection to the
proxy's listener, so the proxy needs an address and port the database host can
actually reach.

Typical arrangements:

- **Same network** — the proxy runs on a server in the same segment as the
  database host. Simplest.
- **On the database host itself** — nothing to route at all; the proxy needs
  only outbound HTTPS to Fusion.
- **Across a boundary** — VPN, or an SSH reverse tunnel terminating somewhere
  the database can see.

The proxy does none of this for you. Bind it accordingly:

```bash
./oratofusionproxy \
  --oracle-listen 0.0.0.0:1521 --oracle-password 'changeme' \
  --fusion-host fa-xxxx.oraclecloud.com --auth=jwt-assertion
```

Two warnings. First, `--oracle-password` is the **only** credential on that
listener — obviously not literally `changeme`: if the port is reachable from
more than the database host, that value is your entire access control. Second,
a long-lived service needs an auth mode that does not involve a browser: see [Authentication](auth.md) for
`token-file`, `token-refresh`, `client-credentials` and `jwt-assertion`.

## 2. Add a TNS entry (or inline the descriptor)

On the EBS database host, in `$TNS_ADMIN/tnsnames.ora`:

```
OFPGPROXY =
  (DESCRIPTION =
    (ADDRESS = (PROTOCOL = TCP)(HOST = proxy-host.example.com)(PORT = 1521))
    (CONNECT_DATA = (SERVICE_NAME = fusion))
  )
```

`SERVICE_NAME` is not validated by the proxy — any value works. Check it
resolves before touching the database:

```bash
tnsping OFPGPROXY
```

If you would rather not edit `tnsnames.ora` (change control, shared host), skip
this step and inline the same descriptor in the `USING` clause below.

## 3. Create the link

```sql
-- with a tnsnames entry:
CREATE DATABASE LINK fusion_saas
  CONNECT TO "FUSION" IDENTIFIED BY "changeme"
  USING 'OFPGPROXY';

-- or entirely self-contained, no tnsnames.ora change:
CREATE DATABASE LINK fusion_saas
  CONNECT TO "FUSION" IDENTIFIED BY "changeme"
  USING '(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=proxy-host.example.com)(PORT=1521))
          (CONNECT_DATA=(SERVICE_NAME=fusion)))';
```

Credential rules, which are not the ones you are used to:

- **The password is `--oracle-password` / `ORACLE_WIRE_PASSWORD`** — the value
  *you* chose when starting the proxy. It is not a Fusion password and not an
  EBS password.
- **The username is not validated**, and does not affect what you see: every
  object the proxy reports is owned by one logical schema, `FUSION`, whoever
  you connect as.
- Real access control is the Fusion session the proxy holds underneath — see
  [Fusion prerequisites](fusion-prerequisites.md).

## 4. Query it

```sql
SELECT invoice_id, invoice_num, invoice_amount
FROM   ap_invoices_all@fusion_saas
WHERE  ROWNUM <= 10;
```

A synonym makes existing code shorter, and lets you swap the source later
without touching the SQL:

```sql
CREATE SYNONYM fusion_ap_invoices FOR ap_invoices_all@fusion_saas;
```

## Which database versions work

| EBS DB version | Status |
|---|---|
| **19c** | Verified live — the common R12.2 and R12.1.3 platform today |
| **23ai / 26ai** | One protocol generation; verified live with 26ai |
| 12.1.0.2, 12.2.0.1, 18c, 11.2.0.4 | **Not tested.** They take the older of the two protocol generations the proxy implements, so there is a fair chance they work as-is — but nobody has run them, and one byte of version difference has broken a result shape before |

Check yours before planning around it:

```sql
SELECT banner_full FROM v$version;
```

If it is 19c, you are on the tested path. If it is older, treat it as unknown
and try it on a clone first — and please open an issue with the version either
way.

## Practical notes for a DBA

- **Every remote query is a BI Publisher report execution**, taking hundreds of
  milliseconds to seconds before the first row. Bound your queries (`ROWNUM`,
  a date range) and do not put an unbounded `SELECT *` over a large Fusion
  table inside a loop. See [Limits & guardrails](limits.md).
- **Read-only, enforced.** `INSERT`/`UPDATE`/`DELETE` over the link is refused
  with `ORA-16000`. You cannot write to Fusion through this, by construction.
- **Joins execute where you'd expect.** A join between a local EBS table and a
  remote Fusion table pulls the remote side across; there is no distributed
  optimiser doing anything clever on the Fusion end. Filter the remote side in
  the remote query, not after the join.
- **Change control.** Creating a database link on a production EBS instance is
  usually a governed act. Prove the whole path on a clone or a reporting copy
  first — the proxy behaves identically there.
- **The link holds no session when idle.** Oracle opens the remote session on
  first use and the proxy holds one Fusion session per process, not per link.

## When it doesn't work

- `ORA-12541` / `ORA-12170` on first use — TNS, not the proxy. `tnsping` from
  the database host, then check the firewall between it and the proxy's port.
- `ORA-01017: invalid username/password` — the `IDENTIFIED BY` value is not the
  proxy's `--oracle-password`.
- A real `ORA-00942: table or view does not exist` — the SQL reached Fusion and
  Fusion answered. The table name is wrong, or that object isn't in your
  tenant.
- Anything else: [Troubleshooting](troubleshooting.md) decodes the error shapes,
  and `oratofusionproxy doctor` proves the Fusion side independently of the link.
