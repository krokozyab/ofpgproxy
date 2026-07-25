# Fusion prerequisites

Everything on this page happens **inside your Fusion tenant**, before the proxy
is any use. It is the single most common reason a first run fails, and the
failure looks like a broken proxy (`401`, a SOAP fault, an empty result) when
it is really a missing report or a missing privilege.

There are exactly three requirements. Nothing else in Fusion needs changing.

## 1. The report and its data model must be deployed

> **Already running [ofjdbc](https://github.com/krokozyab/ofjdbc)? Then this
> step is done.** It uses the same two artefacts. Point `--report-path` at the
> same report you already gave the driver and upload nothing — the two tools
> share the deployment happily, and neither knows the other is there.
>
> Deploy a second copy only if you *want* them separated: a different folder so
> the two have their own permissions and their own audit trail, or so a change
> to one can't affect the other. That is a deliberate choice, not a
> requirement.

**Yes, a data model has to exist up front** — the proxy does not generate BI
Publisher artefacts and does not create one per query. It calls **one**
universal report that takes the SQL as a parameter, and everything every client
asks for goes through it.

Two catalog files, from
[krokozyab/ofjdbc/otbireport](https://github.com/krokozyab/ofjdbc/tree/master/otbireport):

| File | What it is |
|---|---|
| `DM_ARB.xdm.catalog` | the data model — the SQL data set that takes your statement as a parameter |
| `RP_ARB.xdo.catalog` | the report that renders it |

Upload both in **Tools → Reports and Analytics → Browse Catalog**, into a
folder you control under `/Shared Folders/Custom/…`.

Then note the report's absolute path and pass it as `--report-path` /
`FUSION_SQL_REPORT_PATH`. `/Custom/Financials/RP_ARB.xdo` is the proxy's
default because that is where ofjdbc's setup guide puts it, but tenants differ
— `/Custom/sql/…` and `/Custom/CloudSQL/…` are both seen in the wild. **A
wrong path fails every query**, and it fails in a way that looks like a
permission problem, so check this before suspecting roles.

Uploading into the catalog is an authoring action: the account doing it needs
BI authoring rights (a job role carrying the *BI Author* duty, or a BI
administrator). One-off, and it does not have to be the account the proxy later
runs as.

## 2. The account the proxy uses must be able to run that report

This is the one people miss. The proxy authenticates as whoever you configure
in `--auth` and runs the report as that user, so that account needs the right
to **execute a BI Publisher report through the web service** — a job role
carrying the *BI Consumer* duty is the usual answer.

What it does **not** need:

- **No per-table grants.** The report's data model runs its SQL through BI
  Publisher's own application data source, not as your user. If the report
  runs at all, every table it can see is readable. Do not go hunting for
  `SELECT` privileges on `AP_INVOICES_ALL`.
- **No DBA or APPS-equivalent account.** There is no such thing in a SaaS
  tenant, and nothing here needs one.
- **No separate data model per table.** One report serves everything.

Role names differ between tenants and pillars, so rather than matching a
string, **verify it directly** — see below.

## 3. The SOAP endpoint must be reachable from wherever the proxy runs

```
https://<your-tenant>/xmlpserver/services/ExternalReportWSSService
```

That is the only Fusion endpoint the proxy calls for data. `--fusion-host` is
just the hostname (`fa-xxxx.oraclecloud.com`) — no scheme, no path; the proxy
builds the URL itself.

If you authenticate with SSO or an OAuth grant, your IdP's endpoints must be
reachable too (browser-based for SSO, `--oauth-token-url` for the token
grants). See [Authentication](auth.md).

Corporate proxies and egress filtering are the usual obstacles: this is
ordinary outbound HTTPS, but to a host your firewall may not know.

## Shortcut: prove the Fusion side with `ofjdbc` first

If you already use ofjdbc, skip this — you have your answer, and the proxy
needs nothing new in Fusion.

Otherwise: [**ofjdbc**](https://github.com/krokozyab/ofjdbc) — same author, same
`RP_ARB.xdo`, same web service, same credentials — is a single JDBC jar you
drop into DBeaver. Setting Fusion up for the first time, it is the fastest way
to find out whether the tenant side is right, with the fewest moving parts: no
listener, no metadata cache, no wire protocol, just a driver and a connection
string. It is also a gentler way to get a feel for what querying a SaaS tenant
this way is actually like before wiring anything into a database.

1. Grab `orfujdbc-x.x.jar` from
   [ofjdbc releases](https://github.com/krokozyab/ofjdbc/releases).
2. In DBeaver, add it as a driver with class `my.jdbc.wsdl_driver.WsdlDriver`
   and URL
   `jdbc:wsdl://<host>/xmlpserver/services/ExternalReportWSSService?WSDL:/Custom/Financials/RP_ARB.xdo`
   (your report path).
3. Connect with your Fusion username and password and run a `SELECT`.
   Its [setup guide](https://github.com/krokozyab/ofjdbc/blob/master/docs/setup_guide.md)
   has screenshots for each step.

**If that works, all three requirements on this page are met** — the artefacts
are deployed at the path you used, your account can run the report through the
web service, and the endpoint is reachable. Point `ofpgproxy` at the same host
and report path and it will work too.

What the proxy adds on top: any Oracle client rather than a JVM one — `sqlplus`,
SQL Developer, SQLcl, python-oracledb — and, the reason most people are here,
another Oracle database reading Fusion over its own
[`dblink`](r12-dblink.md).

## Verify all three in one command

Do not guess at role names. `doctor` runs a real, bounded
`SELECT 1 FROM DUAL` through your tenant, using the same config the proxy
will:

```bash
set -a; source .env; set +a
./ofpgproxy doctor
```

- `fusion.auth_and_report` **PASS** — the account authenticated, the report
  path resolved, and BI Publisher executed it. All three requirements are met.
- `fusion.auth_and_report` **FAIL** with a `401` — authentication, not
  authorization. Wrong credentials, an expired token, or an auth mode the
  tenant doesn't allow for API access.
- `fusion.auth_and_report` **FAIL** with a SOAP fault mentioning the report
  path — the report is not at that path, or this account cannot run it.
  Check `--report-path` first; it is far more often the path than the role.
- `fusion.query` **PASS** — SQL actually executes end to end.

Add `--offline` to check config and the local catalog without touching the
tenant at all.

## What "read-only" means here

BI Publisher cannot write, so neither can the proxy — a write attempt is
refused before it reaches Fusion, and the client gets `ORA-16000`. Nothing you
do through this tool can modify tenant data, which is usually the first
question a Fusion administrator asks.

## Related

- [Authentication](auth.md) — the six ways the proxy can authenticate to Fusion
- [Configuration](configuration.md) — `--fusion-host`, `--report-path` and the rest
- [Limits & guardrails](limits.md) — what one query costs the tenant
- [Troubleshooting](troubleshooting.md) — what each error actually means
