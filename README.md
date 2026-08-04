<div align="center">
  <h1>✨ oratofusionproxy</h1>
  <p><strong>Query Oracle Fusion Cloud with the tools you already have — Excel and Power BI, SQL Developer, DBeaver, SQLcl, ojdbc, python-oracledb, and a real Oracle database's own <code>dblink</code>.</strong></p>
  <p>One binary between your existing tooling and a SaaS tenant that only speaks SOAP.<br />
  Nothing to install on the client side that Oracle does not already ship.</p>

  <br />

  <a href="https://github.com/krokozyab/oracle-fusion-tns-proxy/releases/latest"><img src="https://img.shields.io/badge/Download-Windows-0078D4?style=for-the-badge&logo=windows&logoColor=white" alt="Download for Windows" /></a>
  <a href="https://github.com/krokozyab/oracle-fusion-tns-proxy/releases/latest"><img src="https://img.shields.io/badge/Download-Linux-1793D1?style=for-the-badge&logo=linux&logoColor=white" alt="Download for Linux" /></a>
  <a href="https://github.com/krokozyab/oracle-fusion-tns-proxy/releases/latest"><img src="https://img.shields.io/badge/Download-macOS_Apple_Silicon-000000?style=for-the-badge&logo=apple&logoColor=white" alt="Download for macOS Apple Silicon" /></a>

  <br />

  <sub>Each archive is the binary, a pre-built metadata catalog and an <code>.env.example</code> — nothing else to install.<br />
  Checksums in <code>SHA256SUMS</code> · <a href="doc/quickstart.md">Quick Start</a> · <a href="#-which-clients-are-verified">Verified clients</a> · <a href="#-trust--security">Trust &amp; security</a></sub>

  <br />
  <br />

  <img src="https://img.shields.io/badge/Oracle_Net-TNS%2FTTC-F80000?style=flat-square&logo=oracle&logoColor=white" alt="Oracle Net (TNS/TTC)" />
  <img src="https://img.shields.io/badge/Oracle_Fusion-Supported-F80000?style=flat-square&logo=oracle&logoColor=white" alt="Oracle Fusion" />
  <img src="https://img.shields.io/badge/read--only-by_design-2e8b57?style=flat-square" alt="Read-only by design" />
  <br />
  <br />
</div>

 [![GitHub Downloads](https://img.shields.io/github/downloads/krokozyab/oracle-fusion-tns-proxy/total?style=for-the-badge&logo=github)](https://github.com/krokozyab/oracle-fusion-tns-proxy/releases)
Oracle Fusion Cloud's BI Publisher web service is the documented way to run ad-hoc SQL against a SaaS tenant. It speaks SOAP, returns base64-wrapped XML — and the Oracle clients you already own (SQLcl, DBeaver, a reconciliation script over `dblink`, an ojdbc-based service) expect the real Oracle wire protocol instead.

**`oratofusionproxy` sits between them and makes them agree.**

```mermaid
flowchart LR
    subgraph L["<b>Oracle clients you already have</b>"]
        A["<b>SQL editors &amp; IDEs</b><br/>SQL Developer · DBeaver · SQLcl · sqlplus"]
        B["<b>BI &amp; spreadsheets</b><br/>Excel · Power BI · SSIS<br/>via Oracle Client for Microsoft Tools"]
        C["<b>Your own code</b><br/>ojdbc · python-oracledb · ODP.NET · go-ora"]
        D["<b>Another Oracle database</b><br/>CREATE DATABASE LINK · EBS R12 · 19c · 23ai/26ai"]
    end

    A -->|"<b>Oracle Net (TNS/TTC)</b><br/>the real protocol, no shim"| P["<b>oratofusionproxy</b><br/>one binary · read-only<br/>listens on :1521"]
    B --> P
    C --> P
    D --> P
    P -->|"SOAP · one report call per query"| F["<b>Oracle Fusion Cloud</b><br/>BI Publisher · RP_ARB.xdo"]
    P -.->|"schema browsing,<br/>answered locally"| M[("<b>local catalog</b><br/>metadata-cache.db")]

    classDef client fill:#eef4fb,stroke:#5b8db8,stroke-width:1px,color:#12283a
    classDef proxy  fill:#e7f6ec,stroke:#2e8b57,stroke-width:2px,color:#0f2e1d
    classDef store  fill:#fff6e5,stroke:#d19a2f,stroke-width:1px,color:#3a2a08
    classDef cloud  fill:#fdeaea,stroke:#c0392b,stroke-width:2px,color:#3b1010
    class A,B,C,D client
    class P proxy
    class M store
    class F cloud
    style L fill:none,stroke:#9aa5b1,stroke-dasharray:4 4,color:#6b7280
```

Every client on the left is one this build has actually been driven with — the
[compatibility matrix](doc/testing.md#client-compatibility) says which, at what
version, and where the edges are.

### 🎯 Native Oracle, no rewrite required

Point existing Oracle tooling straight at Fusion — nothing to port:
*   **SQLcl, DBeaver, SQL Developer** — point them at Fusion and run your `SELECT`s, no rewrite. Tree navigation, autocomplete and result grids intact. (`sqlplus` works too.)
*   **A real Oracle database's own `dblink`** — reconciliation scripts, migration validation, anything already written to query a remote Oracle schema keeps working unchanged. Verified live from a 19c initiator and from the 23ai/26ai protocol generation (exercised with 26ai).
*   **ojdbc / python-oracledb** — service code that already speaks the Oracle driver connects without touching a line.
*   *Read-only by construction — BI Publisher can't write, so DML is rejected regardless of client.*

## 💡 Why you need this

* **Reuse the Oracle stack you already have:** reconcile an EBS (or any Oracle) database against Fusion straight over its own `dblink` — [step-by-step for R12](doc/r12-dblink.md), and no Database Gateway or `dg4odbc` involved — pull Fusion data into OIC flows and existing PL/SQL, run your `sqlplus` scripts and SQL Developer habits — all unchanged. Cut out the staging tables, nightly exports, and throwaway integrations you'd otherwise build just to move the data around.
* **Stop fighting the reporting bottleneck:** query the tenant directly from the client you already have, instead of waiting weeks for a custom pipeline or authoring a BI Publisher report per question.
* **Excel and Power BI, straight at the tenant:** no CSV exports, no OTBI Logical SQL, no per-question BI Publisher report. Install Oracle's own free [Client for Microsoft Tools](https://www.oracle.com/database/technologies/appdev/ocmt.html) — the one Oracle publishes for Power BI Desktop, Excel, SSAS, SSIS, SSRS and SSDT — point it at the proxy, and write ordinary `SELECT`s against Fusion tables. Nothing custom in between: it is Oracle's client talking a real Oracle port. ([How to connect](doc/clients.md#power-bi-excel-and-other-microsoft-tools))
* **Keep the tools you already know:** the Oracle client families in the [compatibility matrix](doc/testing.md#client-compatibility) connect natively — SQL Developer, DBeaver, SQLcl, ojdbc, python-oracledb, managed ODP.NET, `sqlplus`, `dblink`. Nothing new to learn, no SDK, no custom integration.

## 🚀 Five-minute local start

*Once the one-time Fusion setup below is done, going from the downloaded
binary to your first `SELECT` is three commands.*

**Prerequisite:** Deploy the `RP_ARB.xdo` BI Publisher report to your Oracle Fusion tenant (catalog: [krokozyab/ofjdbc/otbireport](https://github.com/krokozyab/ofjdbc/tree/master/otbireport)) and make sure the account you'll authenticate as can run it. Three requirements in total, all inside Fusion — **[Fusion prerequisites](doc/fusion-prerequisites.md)** spells them out and shows how to verify all three with one command.

```bash
# 1. Grab the binary from the latest release:
#    https://github.com/krokozyab/oracle-fusion-tns-proxy/releases/latest
./oratofusionproxy --version

# 2. Point it at your Oracle Fusion tenant.
#    --oracle-password is a value YOU choose here and now; it is not a Fusion
#    password. Every Oracle client will log in with this exact string.
FUSION_HOST=fa-xxxx.oraclecloud.com FUSION_AUTH_TYPE=sso \
  ./oratofusionproxy --oracle-listen 127.0.0.1:1521 --oracle-password changeme

# 3. Connect with a real Oracle client — SQLcl here, one download, no
#    Instant Client needed. user = FUSION, password = the --oracle-password
#    value above, service name = anything.
sql FUSION/changeme@//127.0.0.1:1521/fusion
```

**The two credential rules**, because they are not the ones you expect:

- **Username: anything.** It is not validated and does not change what you
  see. Every object the proxy reports is owned by one logical schema, `FUSION`,
  whoever you log in as — the SQL `USER` function answers `FUSION` too.
- **Password = your `--oracle-password` / `ORACLE_WIRE_PASSWORD` value.** This
  is the one thing that *is* validated. It is not your Fusion password: Fusion
  credentials are what the proxy uses on its own side (`--auth`), and clients
  never see them.

The service name is ignored — put anything after the `/`.

*First run opens your IdP in Chrome; the SSO token is then held in-process. If your tenant supports it, standard basic authentication (`--auth=password`) is also available.*

The data-dictionary catalog needs no setup: the proxy keeps its own cache next to the binary and fills it from your tenant as you use it — the table list on first use, a table's columns the first time a client asks. One BI Publisher call each, kept for good. `oratofusionproxy warm-metadata` fills it in one go if you'd rather not wait.

👉 **[Read the Full Quick Start Guide](doc/quickstart.md)** · **[Connecting Oracle clients](doc/clients.md#oracle-clients-sqlcl-dbeaver-sql-developer)**

## 🧭 Rather not use a command line?

Put a file called **`.env`** next to the binary and double-click it. The proxy
reads that file at startup, so a whole configuration is five lines of text:

```ini
FUSION_HOST=fa-xxxx-dev1-saasfaprod1.fa.ocs.oraclecloud.com
FUSION_SQL_REPORT_PATH=/Custom/Financials/RP_ARB.xdo
FUSION_AUTH_TYPE=sso
OFPG_ORACLE_LISTEN=127.0.0.1:1521
ORACLE_WIRE_PASSWORD=choose-something-here
```

A console window opens and stays open — that window *is* the proxy, and closing
it stops it. Wait for `Oracle-wire (TNS) listening on 127.0.0.1:1521`, then point
any Oracle client at `127.0.0.1:1521/fusion` with user `FUSION` and the password
you just invented — a SQL editor, a BI tool, a spreadsheet, a script, all the
same three values. `oratofusionproxy doctor` checks the whole setup without
involving a client at all.

👉 **[Setting it up, step by step](doc/setup.md)**

## 🦸‍♂️ What you get out of the box

* 🔌 **Zero custom glue.** No specialized SDKs or custom integrations — if your tool speaks to an Oracle database, it already speaks to Fusion.
* 🔶 **The real wire protocol (TNS/TTC).** SQLcl, DBeaver, SQL Developer, ojdbc, python-oracledb and a real Oracle database's own `dblink` connect over the actual protocol bytes — a from-scratch implementation, not an emulation layer bolted onto a driver.
* 📚 **A catalog that builds itself.** Schema browsing is answered locally from a DuckDB catalog the proxy fills from your tenant on demand, so an IDE's tree never costs a slow round trip twice.
* 🌊 **Paged, not piled up.** GUI clients get their results in pages, so a wide table's first screen arrives in seconds and the proxy holds one page at a time. An unbounded query from a code client is one backend call and is held whole — bound it with `ROWNUM` and the footprint stays small.
* 🔒 **Read-only by design.** BI Publisher can't write, and neither will the proxy. No accidental DML. Sleep soundly.
* 🩺 **Built-in `doctor`.** `oratofusionproxy doctor` validates config, catalog health and Fusion reachability — and reports exactly which Oracle client/dialect combinations this build has verified — before you ever point a real client at it. [Details](doc/configuration.md#oratofusionproxy-doctor).

## ✅ Which clients are verified

Not a wish list — each row was driven against a live Fusion tenant, and the
shipping binary can print this itself with `oratofusionproxy doctor --profiles`,
down to the capability and the evidence behind it.

| Client | Status | Worth knowing |
|---|---|---|
| SQL Developer · DBeaver · SQLcl · ojdbc | **Verified** | Tree browsing and result-grid scrolling included |
| Oracle Database via `dblink` — 19c and 23ai/26ai | **Verified** | Both TTC generations, driven from real servers |
| python-oracledb (thin) | **Verified** | |
| `sijms/go-ora` (pure Go) | **Verified** | Driven as a real client on every build |
| SSIS · SSRS · SSDT · .NET code — managed ODP.NET | **Verified** | Every bind type, 600-column results |
| Excel · Power Query — unmanaged ODP.NET | **Partial** | A table with a `CLOB` column will not load — project it away |
| Power BI Desktop | **Verified** | Same unmanaged path as Excel, same `CLOB` exception |
| `sqlplus` (Instant Client) | **Verified** | No ANO / native encryption |

Full matrix, versions and what is *not* covered: [Testing & verification](doc/testing.md).

## 🎯 Is this the right tool for your job?

| Good fit | Not a fit |
|---|---|
| Ad-hoc analysis and support investigations | Extracting millions of rows |
| EBS ↔ Fusion reconciliation over `dblink` | Replacing BICC for a data warehouse |
| Migration and cutover validation | A dashboard refreshing every 30 seconds |
| Existing Oracle SQL, scripts and applications | Many users needing *different* Fusion identities |
| Browsing tenant tables from an IDE | An untrusted network with no tunnel |

**What will people see through it?** One answer, so nobody is surprised by it
later: every client through one proxy process shares **one** Fusion identity —
the credentials that process was started with. Access is whatever that Fusion
account can read through BI Publisher. The proxy adds no row-level security of
its own, serves one tenant per process, and is not a way around Fusion's own
access control.

## 📖 Documentation

| Guide | Description |
|---|---|
| 🏎️ [**Quick Start**](doc/quickstart.md) | Zero to your first `SELECT` in 5 minutes |
| 🧭 [**Setting it up**](doc/setup.md) | Step by step, no command line: the `.env` file, starting it, connecting any client |
| 🤝 [**Connecting clients**](doc/clients.md) | Recipes for SQLcl, DBeaver, SQL Developer, `dblink`, ojdbc, python-oracledb |
| ⚙️ [**Configuration**](doc/configuration.md) | Flags, env vars, ports, `oratofusionproxy doctor`, and signals |
| 🔑 [**Authentication**](doc/auth.md) | SSO, password, token-file, and OAuth (refresh / client-credentials / JWT-assertion) modes |
| 🏛️ [**Fusion prerequisites**](doc/fusion-prerequisites.md) | What must exist in the tenant: the report, the account's rights, the endpoint — and how to verify them |
| 🔗 [**EBS R12 over `dblink`**](doc/r12-dblink.md) | Copy-paste recipe for reading Fusion from an R12 database, versions covered, DBA notes |
| 🚦 [**Limits & guardrails**](doc/limits.md) | Concurrency, result sizes, timeouts, cancellation, large types — what one query costs the tenant |
| 🧪 [**Testing & verification**](doc/testing.md) | What's actually verified, against which Oracle versions and clients — and what isn't |
| 📈 [**Observability**](doc/observability.md) | Prometheus `/metrics`, `/healthz`, `/readyz` |
| 🗂️ [**Metadata catalog**](doc/metadata.md) | What the catalog holds, how it fills itself, and how to refresh it |
| 🚑 [**Troubleshooting**](doc/troubleshooting.md) | Common errors, what they mean, and how to fix them |
| 💻 [**Examples**](examples/) | Four runnable programs — Python, Java, C#, Go — reading the same ten invoices out of Fusion |

## 🕹️ How it feels in practice

You run the binary. You get an Oracle listener on `:1521` — except the tables behind it are Oracle Fusion's.

Everything that speaks to an Oracle database just connects: SQLcl, DBeaver, SQL Developer, a real Oracle database's `dblink`, a Python script using python-oracledb, a JVM service on ojdbc — `sqlplus` too, if that is still what you reach for. Each query becomes a BI Publisher SOAP call under the hood, and the rows come back in bounded pages: the proxy reads one report response, hands you those rows, and fetches the next page when you ask for it.

**Your tools connect as though it were a real database — that is the whole trick.**

*Actively developed. Expect rough edges on exotic SQL shapes and unverified client/dialect combinations — `oratofusionproxy doctor --profiles` shows exactly what's covered today, and [Testing & verification](doc/testing.md) has the full matrix. Open an issue when you hit one.*

## 💻 Reading Fusion from your own code

Four small programs, one per ecosystem, that do the same thing: connect, read
ten invoices out of `ap_invoices_all`, print them.

| | driver | run |
|---|---|---|
| [Python](examples/python/) | `python-oracledb` (thin, pure Python) | `python invoices.py` |
| [Java](examples/java/) | Oracle JDBC thin (`ojdbc11`) | `java -cp ojdbc11.jar Invoices.java` |
| [C#](examples/csharp/) | `Oracle.ManagedDataAccess.Core` | `dotnet run` |
| [Go](examples/go/) | `github.com/sijms/go-ora/v2` (pure Go) | `go run invoices.go` |

**What matters is what is not in them.** No proxy-specific driver, no shim, no
custom connector, no SDK — each one takes its language's ordinary Oracle client
and hands it a host and a port. That is the entire claim this project makes,
and these are the shortest honest way to show it.

Two of those drivers are what real tools use underneath — ojdbc is SQL
Developer and DBeaver, ODP.NET is what Oracle's Client for Microsoft Tools puts
under Excel, Power BI and SSIS — so the demos also
stand in for the paths most people actually arrive on. All four were run
against a live Fusion tenant through the proxy and return the same rows.

👉 **[All four, with setup notes](examples/)**

## 🔐 Trust & security

This tool is handed your Fusion credentials, so the questions your security
review will ask are answered here rather than somewhere in the docs.

**What it is.** A single closed-source binary. There is no source release; what
you can verify instead is behaviour — `doctor` reports what it will do before
you point a client at it, `doctor --profiles` prints the build's own verified
capability list, and every release ships `SHA256SUMS`:

```bash
sha256sum -c SHA256SUMS          # Linux
shasum -a 256 -c SHA256SUMS      # macOS
```

**Where your data goes.** Two destinations, both yours: your tenant's BI
Publisher endpoint (`https://<your-host>/xmlpserver/services/…`), and — only if
you configure an OAuth or SSO mode — your own identity provider's token
endpoint. There is no other outbound connection, no update check, and **no
telemetry or analytics of any kind** anywhere in the binary.

**What touches the disk.** The metadata catalog beside the binary, which holds
*schema* only: table, column, primary-key and module names from your tenant's
`FND_*` dictionary. No query results are written to it, or anywhere else — with
one deliberate exception you have to switch on yourself, the diagnostic bundle
(`--oracle-diagnostic-dir`), which records protocol traffic for a bug report and
with `--oracle-diagnostic-raw` includes the raw bytes. Treat a bundle as
sensitive and read it before sending it.

**Credentials.** Fusion credentials live only in the proxy's own configuration
and are never sent to a client. What clients send is a separate password you
invent (`ORACLE_WIRE_PASSWORD`) — losing it exposes the proxy, not your tenant
login. SSO refresh tokens are held in memory and never written to disk.

**The real limitation, stated plainly: there is no transport encryption on the
Oracle-wire side.** No TLS, no Oracle Advanced Networking, in any client
profile. The supported posture is a loopback listener, or a network you already
trust — put SSH or a VPN in between if it is not. The proxy warns at startup
when its listener leaves loopback. Fusion side is ordinary HTTPS.

**Read-only, structurally.** BI Publisher cannot write, so no client can — DML
and DDL are refused with `ORA-16000` before reaching the tenant. There is no
flag that relaxes this.

**Licensing.** Free to evaluate — including a proof of concept inside a
company — and free for personal, academic and other non-commercial use, with no
key and no time limit. Running it in production or otherwise in the course of
commercial activity needs a paid licence; open an issue and ask. The full terms
are in [LICENSE](LICENSE), and reporting a vulnerability is in
[SECURITY.md](SECURITY.md).

**Access.** One process, one Fusion identity, one tenant — see
[Is this the right tool for your job?](#-is-this-the-right-tool-for-your-job)
above.

## ⚖️ Independence & trademarks

`oratofusionproxy` is an independent, third-party tool. It is **not affiliated with, endorsed by, sponsored by, or supported by Oracle Corporation.**

"Oracle", "Oracle Fusion Cloud", "Oracle Net", "SQL Developer", and "SQLcl" are trademarks or registered trademarks of Oracle and/or its affiliates. They are used here only descriptively — to state what `oratofusionproxy` interoperates with — and no affiliation or endorsement is implied.

`oratofusionproxy` reads your tenant **only through Oracle's own documented BI Publisher web service** — the interface Oracle provides for this — authenticating with credentials **you** supply. It bundles no Oracle software and copies no Oracle source code; the Oracle Net (TNS/TTC) endpoint is an independent implementation whose sole purpose is protocol interoperability.

You are responsible for using `oratofusionproxy` in accordance with your own Oracle Cloud subscription terms, license agreements, and applicable law. Nothing here is legal advice — if you have doubts, talk to your own counsel.
