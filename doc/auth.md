# Authentication

`ofpgproxy` supports three auth modes, selected via `--auth` / `FUSION_AUTH_TYPE`:

- `sso` — browser-based federated login (most common on real tenants)
- `password` — basic auth
- `token-file` — pre-issued bearer token

Clients connecting to the proxy itself **do not** need credentials: the proxy accepts any `user` / `password` in the PG wire startup message. All actual authentication happens on the Fusion side using whichever mode you configured.

---

## SSO

```bash
FUSION_HOST=fa-xxxx.oraclecloud.com \
FUSION_AUTH_TYPE=sso \
./ofpgproxy --metadata-path ./metadata.db
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
./ofpgproxy --metadata-path ./metadata.db
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
  --metadata-path ./metadata.db
```

- The file contents are read on every SOAP call, so you can rotate tokens externally without restarting the proxy.
- File format: the bearer token as plain text, with or without a trailing newline.
- Permissions: restrict to the proxy user — the token grants API access to your tenant.

Useful behind an ESS-managed token vault, in CI pipelines that already have a Fusion token in a secret store, or for headless environments where no browser is available.

---

## Multi-tenant proxy (not currently supported)

Today one proxy instance = one Fusion tenant. To front multiple tenants, run one proxy per tenant on different ports. Each proxy holds its own SSO session or credentials.

Per-connection authentication (tenant picked by the PG client's `user` / `dbname`) is on the roadmap; until it ships, the one-per-tenant split is the clean path.

---

## Security notes

- The proxy **does not** authenticate its own PG-wire clients. Bind it to loopback (`127.0.0.1`, the default) or put it behind an authenticating reverse proxy / SSH tunnel. Never expose `:5433` to a public network.
- TLS on the PG-wire side is not enabled by default. If you need it, terminate TLS at a fronting proxy (stunnel, nginx stream module, etc.).
- SOAP calls to Fusion go over HTTPS with server-certificate validation by default — no action needed.
