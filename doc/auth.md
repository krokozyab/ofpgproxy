# Authentication

`ofpgproxy` supports six auth modes for the **Fusion backend**, selected via `--auth` / `FUSION_AUTH_TYPE`:

- `sso` — browser-based federated login (most common on real tenants)
- `password` — basic auth
- `token-file` — pre-issued bearer token, re-read each call
- `token-refresh` — long-lived OAuth refresh token, access token auto-refreshed
- `client-credentials` — headless app-only OAuth grant
- `jwt-assertion` — headless service account impersonating a user (the BIP-compatible cloud grant)

The last three are OAuth grants for **headless cloud deployments** — no browser. For running reports (which BI Publisher does *as a user*), `jwt-assertion` is the one to reach for; see [Headless cloud](#headless-cloud-oauth) below.

Client-side credentials (what you type into SQLcl, DBeaver or SQL Developer) are a **separate, much simpler story** than the Fusion modes above:

- Any username is accepted (use `FUSION`, uppercase, so IDE tree-browsing works — see [Connecting clients](clients.md#oracle-clients-sqlcl-dbeaver-sql-developer)), but the password **must be** the shared value you set with `--oracle-password` / `ORACLE_WIRE_PASSWORD`.

All actual authentication against Oracle happens on the Fusion side using whichever mode you configured.

---

## SSO

```bash
FUSION_HOST=fa-xxxx.oraclecloud.com \
FUSION_AUTH_TYPE=sso \
./ofpgproxy
```

### How it works

1. On startup (or at first SOAP call after token expiry) the proxy launches Chrome via CDP pointed at your tenant's `/xmlpserver/` endpoint.
2. Your IdP's login page appears. Log in as you would in the browser.
3. The proxy captures the session cookie + access token via the Chrome DevTools protocol.
4. Chrome is closed; the token lives in-process for the lifetime of the proxy.

### Refresh

- Fusion tokens typically last one hour; the proxy refreshes them silently using the refresh-token grant when 80% of the lifetime has elapsed.
- When the refresh token itself expires (usually 8–12 hours, tenant-configurable) or silent refresh fails, the proxy **reopens Chrome automatically** on the next SOAP call. Concurrent calls share one browser window; the query that triggered the re-login blocks until you finish the IdP flow, then completes.
- Tokens are **never** persisted to disk. A fresh start always means a fresh login.

### Requirements

- Chrome or Chromium on `PATH`.
- The machine running the proxy must be able to complete the IdP flow — for MFA-gated IdPs this means the operator is physically present. For hands-off deployments, consider `token-file` or `password` mode.

### Timeout

`--sso-timeout` (default 300 seconds) bounds how long the proxy waits for you to finish logging in. Adjust if your IdP chain takes longer.

---

## Password

Basic auth with a static Fusion user:

```bash
FUSION_AUTH_TYPE=password \
FUSION_USER=bip.integration \
FUSION_PASSWORD=... \
./ofpgproxy
```

The credentials are sent on every SOAP call directly — no browser, no refresh logic. Simple but less common on modern tenants that require SAML/OIDC.

Prefer the env-var form over `--auth-password` to keep the secret out of the process table (`ps` / `/proc/*/cmdline`).

---

## Token file

For out-of-band-authenticated setups:

```bash
./ofpgproxy \
  --auth token-file \
  --auth-token-file /run/secrets/fusion-token \
 
```

- The file contents are read on every SOAP call, so you can rotate tokens externally without restarting the proxy.
- File format: the bearer token as plain text, with or without a trailing newline.
- Permissions: restrict to the proxy user — the token grants API access to your tenant.

Useful behind an ESS-managed token vault, in CI pipelines that already have a Fusion token in a secret store, or for headless environments where no browser is available. `token-file` is static — nothing auto-refreshes it; rotate the file out of band.

---

## Headless cloud (OAuth)

For unattended cloud deployments the proxy speaks OAuth directly, reusing an
expiry-aware token cache. All three read secrets from flags **or** env — prefer
env so they stay out of `ps`.

### token-refresh

A long-lived OAuth refresh token; the proxy exchanges it for short access
tokens against Fusion's token-relay endpoint and refreshes automatically
(proactively before expiry, and again if it lapses while idle).

```bash
FUSION_AUTH_TYPE=token-refresh \
FUSION_REFRESH_TOKEN=... \
./ofpgproxy --fusion-host fa-xxxx.oraclecloud.com
```

The refresh token is the credential you supply; the initial access token is
fetched at startup if not given (`FUSION_ACCESS_TOKEN`). You still need to
obtain the refresh token once (today that means the browser SSO flow).

### client-credentials

The classic service-account grant: `client_id` + `client_secret` → token from
the IdP, cached to expiry and re-fetched — no user, no browser, no refresh
token. Config is validated by an eager fetch at startup.

```bash
FUSION_AUTH_TYPE=client-credentials \
FUSION_OAUTH_TOKEN_URL=https://<idcs-host>/oauth2/v1/token \
FUSION_OAUTH_CLIENT_ID=... FUSION_OAUTH_CLIENT_SECRET=... \
FUSION_OAUTH_SCOPE=... \
./ofpgproxy --fusion-host fa-xxxx.oraclecloud.com
```

> ⚠️ A client-credentials token is **app-only** — it carries no user identity.
> BI Publisher runs reports *as a user*, so this grant may be rejected by BIP
> or run with no user context. For BIP, use **`jwt-assertion`** instead.

### jwt-assertion

The OAuth 2.0 JWT bearer grant (RFC 7523) — a service account **impersonating a
user**. The proxy mints a short JWT whose `sub` is the service-account user,
signs it with the app's registered RSA key, and exchanges it for a *user-scoped*
access token that BI Publisher accepts.

```bash
FUSION_AUTH_TYPE=jwt-assertion \
FUSION_OAUTH_TOKEN_URL=https://<idp-host>/oauth2/v1/token \
FUSION_OAUTH_CLIENT_ID=<app-client-id> \
FUSION_JWT_SUBJECT=svc.reporting@corp \
FUSION_JWT_KEY_FILE=/run/secrets/assertion-key.pem \
FUSION_JWT_KEY_ID=<kid> \
FUSION_JWT_AUDIENCE=https://<idp-host>/ \
./ofpgproxy --fusion-host fa-xxxx.oraclecloud.com
```

Setup (a **tenant-admin** task):

1. Register a confidential OAuth client in IDCS / OCI IAM (or OAM) with the JWT
   user-assertion grant enabled.
2. Upload the **public** cert of an RSA keypair to the app; note its `kid`.
3. Grant the client the right to impersonate the service-account user and to
   access the Fusion/BIP resource (scope).
4. Give the proxy the **private** key (PEM, PKCS#1 or #8), `client_id`, `kid`,
   token URL, audience, and the service-account username (`sub`).

The proxy signs the assertion in-process (RS256); the private key never leaves
the host and is never sent over the wire — only the signed, short-lived
assertion is.

---

## Multi-tenant proxy (not currently supported)

Today one proxy instance = one Fusion tenant. To front multiple tenants, run one proxy per tenant on different ports. Each proxy holds its own SSO session or credentials.

Per-connection authentication (tenant picked by the client's username or service name) is on the roadmap; until it ships, the one-per-tenant split is the clean path.

---

## Security notes

- The Oracle-wire listener's only credential is the shared `--oracle-password`. Bind it to loopback (`127.0.0.1`) or reach it over a tunnel / private network; if it must listen on a routable address, treat that password as the whole of your access control and pick it accordingly.
- TLS (TCPS) is not implemented. If you need the wire encrypted, terminate TLS in front of the proxy (stunnel, nginx stream module) and forward plaintext to it on loopback.
- SOAP calls to Fusion go over HTTPS with server-certificate validation by default — no action needed.
