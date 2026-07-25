<div align="center">
  <img src="assets/hero.png" alt="ofpgproxy" width="800" />

  <h1>✨ ofpgproxy</h1>
  <p><strong>Query Oracle Fusion Cloud with the Oracle clients you already have — <code>sqlplus</code>, SQL Developer, SQLcl, ojdbc, python-oracledb, and a real Oracle database's own <code>dblink</code>.</strong></p>
  <p>One binary between your existing Oracle tooling and a SaaS tenant that only speaks SOAP.</p>

  <br />

  <img src="assets/oracle-wire.png" alt="Oracle clients and another Oracle database's dblink reach Oracle Fusion Cloud through ofpgproxy over TNS/TTC" width="850" />

  <br />
  <br />

  <a href="https://github.com/krokozyab/ofpgproxy/releases/latest"><img src="https://img.shields.io/badge/download-latest-success?style=flat-square&logo=github" alt="Latest release" /></a>
  <img src="https://img.shields.io/badge/Oracle_Net-TNS%2FTTC-F80000?style=flat-square&logo=oracle&logoColor=white" alt="Oracle Net (TNS/TTC)" />
  <img src="https://img.shields.io/badge/Oracle_Fusion-Supported-F80000?style=flat-square&logo=oracle&logoColor=white" alt="Oracle Fusion" />
  <!--[![GitHub Downloads](https://img.shields.io/github/downloads/krokozyab/ofpgproxy/total?style=for-the-badge&logo=github)](https://github.com/krokozyab/ofpgproxy/releases)-->

  <br />
  <br />
</div>

Oracle Fusion Cloud's BI Publisher is the only sanctioned read-path out of a SaaS tenant. It speaks SOAP, returns base64-wrapped XML — and every Oracle client you already own (`sqlplus`, a reconciliation script over `dblink`, SQL Developer, an ojdbc-based service) expects the real Oracle wire protocol instead.

**`ofpgproxy` sits between them and makes them agree.**

```text
                    Oracle clients
        (sqlplus, SQL Developer, SQLcl, ojdbc,
         python-oracledb, another database's dblink)
                          │
                          │  Oracle Net (TNS/TTC)
                          ▼
                  ┌───────────────┐
                  │   ofpgproxy   │
                  └───────────────┘
                          │
                          │  SOAP (BI Publisher · RP_ARB.xdo)
                          ▼
                 Oracle Fusion Cloud tenant
```

### 🎯 Native Oracle, no rewrite required

Point existing Oracle tooling straight at Fusion — nothing to port:
*   **`sqlplus` / SQL Developer / SQLcl** — point them at Fusion and run your `SELECT`s, no rewrite. Tree navigation, autocomplete and result grids intact.
*   **A real Oracle database's own `dblink`** — reconciliation scripts, migration validation, anything already written to query a remote Oracle schema keeps working unchanged. Verified from both 19c and 23ai/26ai initiators.
*   **ojdbc / python-oracledb** — service code that already speaks the Oracle driver connects without touching a line.
*   *Read-only by construction — BI Publisher can't write, so DML is rejected regardless of client.*

## 💡 Why you need this

* **Reuse the Oracle stack you already have:** reconcile an EBS (or any Oracle) database against Fusion straight over its own `dblink`, pull Fusion data into OIC flows and existing PL/SQL, run your `sqlplus` scripts and SQL Developer habits — all unchanged. Cut out the staging tables, nightly exports, and throwaway integrations you'd otherwise build just to move the data around.
* **Stop fighting the reporting bottleneck:** query the tenant directly from the client you already have, instead of waiting weeks for a custom pipeline or authoring a BI Publisher report per question.
* **Keep the tools you already know:** every Oracle client you own connects natively. Nothing new to learn, no SDK, no custom integration.

## 🚀 60-Second Magic Start

**Prerequisite:** Deploy the `RP_ARB.xdo` BI Publisher report to your Oracle Fusion tenant. You can download the report catalog from [krokozyab/ofjdbc/otbireport](https://github.com/krokozyab/ofjdbc/tree/master/otbireport).

```bash
# 1. Grab the binary from the latest release:
#    https://github.com/krokozyab/ofpgproxy/releases/latest
./ofpgproxy --version

# 2. Point it at your Oracle Fusion tenant
FUSION_HOST=fa-xxxx.oraclecloud.com FUSION_AUTH_TYPE=sso \
  ./ofpgproxy --oracle-listen 127.0.0.1:1521 --oracle-password secret

# 3. Connect with a real Oracle client
sqlplus FUSION/secret@//127.0.0.1:1521/fusion
```

*First run opens your IdP in Chrome; the SSO token is then held in-process. If your tenant supports it, standard basic authentication (`--auth=password`) is also available.*

The data-dictionary catalog needs no setup: the proxy keeps its own cache next to the binary and fills it from your tenant as you use it — the table list on first use, a table's columns the first time a client asks. One BI Publisher call each, kept for good. `ofpgproxy warm-metadata` fills it in one go if you'd rather not wait.

👉 **[Read the Full Quick Start Guide](doc/quickstart.md)** · **[Connecting Oracle clients](doc/clients.md#oracle-clients-sql-developer-sqlcl-sqlplus)**

## 🦸‍♂️ What you get out of the box

* 🔌 **Zero custom glue.** No specialized SDKs or custom integrations — if your tool speaks to an Oracle database, it already speaks to Fusion.
* 🔶 **The real wire protocol (TNS/TTC).** `sqlplus`, SQL Developer, SQLcl, ojdbc, python-oracledb and a real Oracle database's own `dblink` connect over the actual protocol bytes — a from-scratch implementation, not an emulation layer bolted onto a driver.
* 📚 **A catalog that builds itself.** Schema browsing is answered locally from a DuckDB catalog the proxy fills from your tenant on demand, so an IDE's tree never costs a slow round trip twice.
* 🌊 **Memory-efficient streaming.** Results flow through the proxy as they arrive. It doesn't buffer massive datasets, keeping its footprint tiny.
* 🔒 **Read-only by design.** BI Publisher can't write, and neither will the proxy. No accidental DML. Sleep soundly.
* 🩺 **Built-in `doctor`.** `ofpgproxy doctor` validates config, catalog health and Fusion reachability — and reports exactly which Oracle client/dialect combinations this build has verified — before you ever point a real client at it. [Details](doc/configuration.md#ofpgproxy-doctor).

## 📖 Documentation

| Guide | Description |
|---|---|
| 🏎️ [**Quick Start**](doc/quickstart.md) | Zero to your first `SELECT` in 5 minutes |
| 🤝 [**Connecting clients**](doc/clients.md) | Recipes for `sqlplus`, SQL Developer, SQLcl, `dblink`, ojdbc, python-oracledb |
| ⚙️ [**Configuration**](doc/configuration.md) | Flags, env vars, ports, `ofpgproxy doctor`, and signals |
| 🔑 [**Authentication**](doc/auth.md) | SSO, password, token-file, and OAuth (refresh / client-credentials / JWT-assertion) modes |
| 🧪 [**Testing & verification**](doc/testing.md) | What's actually verified, against which Oracle versions and clients — and what isn't |
| 📈 [**Observability**](doc/observability.md) | Prometheus `/metrics`, `/healthz`, `/readyz` |
| 🗂️ [**Metadata catalog**](doc/metadata.md) | What the catalog holds, how it fills itself, and how to refresh it |
| 🚑 [**Troubleshooting**](doc/troubleshooting.md) | Common errors, what they mean, and how to fix them |

## 🕹️ How it feels in practice

You run the binary. You get an Oracle listener on `:1521` — except the tables behind it are Oracle Fusion's.

Everything that speaks to an Oracle database just connects: `sqlplus`, SQL Developer, SQLcl, a real Oracle database's `dblink`, a Python script using python-oracledb, a JVM service on ojdbc. Each query transparently becomes a BI Publisher SOAP call under the hood; rows stream back as the XML arrives.

**Your tools never find out it isn't a real database.**

*Actively developed. Expect rough edges on exotic SQL shapes and unverified client/dialect combinations — `ofpgproxy doctor --profiles` shows exactly what's covered today, and [Testing & verification](doc/testing.md) has the full matrix. Open an issue when you hit one.*

## ⚖️ Independence & trademarks

`ofpgproxy` is an independent, third-party tool. It is **not affiliated with, endorsed by, sponsored by, or supported by Oracle Corporation.**

"Oracle", "Oracle Fusion Cloud", "Oracle Net", "SQL Developer", and "SQLcl" are trademarks or registered trademarks of Oracle and/or its affiliates. They are used here only descriptively — to state what `ofpgproxy` interoperates with — and no affiliation or endorsement is implied.

`ofpgproxy` reads your tenant **only through Oracle's own documented BI Publisher web service** — the interface Oracle provides for this — authenticating with credentials **you** supply. It bundles no Oracle software and copies no Oracle source code; the Oracle Net (TNS/TTC) endpoint is an independent implementation whose sole purpose is protocol interoperability.

You are responsible for using `ofpgproxy` in accordance with your own Oracle Cloud subscription terms, license agreements, and applicable law. Nothing here is legal advice — if you have doubts, talk to your own counsel.
