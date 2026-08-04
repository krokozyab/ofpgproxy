# Security policy

## Reporting a vulnerability

Please report privately, not as a public issue:

**[Open a private security advisory](https://github.com/krokozyab/oracle-fusion-tns-proxy/security/advisories/new)**
— GitHub keeps it visible only to you and the maintainer until it is resolved.

Useful to include: the version (`oratofusionproxy --version`, also printed in
the startup banner), the client and driver involved, and what an attacker would gain. A diagnostic bundle
(`--oracle-diagnostic-dir`) helps for anything protocol-level — but **read it
before sending**, since with `--oracle-diagnostic-raw` it contains the actual
bytes of your session, including query text and row data.

This is a one-person project, not a vendor with a security team. Expect an
acknowledgement within a few days rather than within hours, and no bug bounty.

## Supported versions

Only the latest release. There are no backported fixes; a security fix ships as
a new release.

## Already known — please do not report these as vulnerabilities

These are documented design limitations, not oversights. They are listed here
so you can tell them apart from a real finding.

- **No transport encryption on the Oracle-wire side.** No TLS, no Oracle
  Advanced Networking, in any client profile. Traffic between an Oracle client
  and the proxy — including query text and result rows — crosses the network in
  the clear. The supported posture is a loopback listener or an already-trusted
  network; put SSH or a VPN in between otherwise. The binary warns at startup
  when its listener is not on loopback.
- **One shared password for all clients.** `ORACLE_WIRE_PASSWORD` is a single
  value you choose; the username is not validated. It gates access to the
  proxy, and it is not a Fusion credential.
- **One Fusion identity per process.** Every client through one proxy sees the
  tenant as whatever account that process authenticated with. There is no
  per-user access control, and the proxy is not a way around Fusion's own.
- **The ops endpoints have no authentication.** `/metrics`, `/healthz` and
  `/readyz` are unauthenticated by design; bind them to loopback or scrape them
  behind something that adds auth. The binary warns if they leave loopback.

## What the proxy does with your data

- Outbound connections go to two places only: your Fusion tenant's BI Publisher
  endpoint, and — if you configure an OAuth or SSO mode — your own identity
  provider's token endpoint. There is no update check and no telemetry.
- On disk it keeps a metadata catalog holding schema only: table, column,
  primary-key and module names. Query results are not written to disk, with the
  single exception of a diagnostic bundle you enable yourself.
- Fusion credentials stay in the proxy's configuration and are never sent to a
  client. SSO refresh tokens are held in memory only.

## Verifying a download

Every release ships `SHA256SUMS`:

```bash
sha256sum -c SHA256SUMS          # Linux
shasum -a 256 -c SHA256SUMS      # macOS
```

The binaries are not code-signed yet. On Windows, SmartScreen will warn about
an unrecognised publisher; on macOS, Gatekeeper will need the quarantine
attribute cleared. Check the hash before doing either.
