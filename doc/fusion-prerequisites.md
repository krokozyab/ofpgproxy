# Fusion prerequisites

Everything on this page happens **inside your Fusion tenant**, before the proxy
is any use. It is the single most common reason a first run fails, and the
failure looks like a broken proxy (`401`, a SOAP fault, an empty result) when
it is really a missing report or a missing privilege.

There are exactly three requirements. Nothing else in Fusion needs changing.

## 1. The `RP_ARB.xdo` report must be deployed

The proxy does not generate BI Publisher artefacts, and it does not create a
data model per query. It calls **one** report — a universal one that takes the
SQL as a parameter — and everything any client asks for goes through it.

- Download the catalog archive from
  [krokozyab/ofjdbc/otbireport](https://github.com/krokozyab/ofjdbc/tree/master/otbireport).
- Upload it in **Tools → Reports and Analytics → Browse Catalog**, into a
  folder you control — `/Custom/…`.
- Note the resulting absolute path. Two are common:
  `/Custom/Financials/RP_ARB.xdo` (the proxy's default) and
  `/Custom/sql/RP_ARB.xdo`. Whatever yours is, pass it as
  `--report-path` / `FUSION_SQL_REPORT_PATH` — **the default will not silently
  work if your path differs**, it will fail on every query.

Uploading a report into the catalog is an authoring action. The account doing
it needs BI authoring rights (a job role carrying the *BI Author* duty, or a
BI administrator). This is a one-off, and it does not have to be the same
account the proxy later runs as.

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
