# Setting it up, step by step

A walkthrough for someone who would rather not assemble a command line. The setup
is the same whatever you connect afterwards — a SQL editor, a BI tool, a
spreadsheet or a script — so it is described once here, and the client you happen
to use is the last step rather than the frame.

You need three things, in this order:

1. the program,
2. a small text file beside it that holds your settings,
3. one client to prove it works.

---

## 1. Download and unpack

Take the latest archive from
[Releases](https://github.com/krokozyab/oracle-fusion-tns-proxy/releases/latest)
and unpack it into a folder of its own — `C:\Users\you\oratofusionproxy\`,
`~/oratofusionproxy/`, whatever suits.

A folder of its own matters: the program keeps its settings file and its metadata
cache beside itself, and unpacking into Downloads mixes them with everything else
you have downloaded.

> **Windows:** SmartScreen may say the publisher is unknown, because the binary
> is not code-signed. *More info → Run anyway*.
>
> **macOS:** Gatekeeper may refuse the first launch. *System Settings → Privacy
> & Security → Open anyway*, or
> `xattr -d com.apple.quarantine ./oratofusionproxy`.

---

## 2. Write the settings file

In that same folder create a file called **`.env`** — the name is exactly that,
starting with a dot and with nothing after it.

```ini
# Your Fusion tenant, host name only — no https://
FUSION_HOST=fa-xxxx-dev1-saasfaprod1.fa.ocs.oraclecloud.com

# Where you uploaded the RP_ARB.xdo report in BI Publisher.
# Folder names differ between tenants — check yours.
FUSION_SQL_REPORT_PATH=/Custom/Financials/RP_ARB.xdo

# How the proxy signs in to Fusion. sso opens a browser once and keeps
# no password in this file.
FUSION_AUTH_TYPE=sso

# Where your clients will connect.
OFPG_ORACLE_LISTEN=127.0.0.1:1521

# The password your clients will use. You invent this here and now —
# it is NOT a Fusion password. See "Two credential rules" below.
ORACLE_WIRE_PASSWORD=choose-something-here
```

That is the whole configuration for a first run. Everything else has a working
default — see [Configuration](configuration.md) when you need one, and
[Authentication](auth.md) for the sign-in modes other than `sso`.

> **Windows, in Notepad:** *Save as*, set *Save as type* to *All files*, and type
> `.env` as the name — otherwise you get `.env.txt`, which is not read.

**Prefer the command line?** The same thing, without the file:

```bash
./oratofusionproxy --oracle-listen 127.0.0.1:1521 --oracle-password choose-something-here
```

Flags and real environment variables both win over the file, so you can keep a
`.env` and override one value for a single run.

---

## 3. Start it

Run the program — double-click it, or start it from a terminal. Either way a
console window opens and stays open: **that window is the proxy**, and closing it
stops the proxy.

With `FUSION_AUTH_TYPE=sso` your browser opens once for the Fusion login. After
you sign in, the console settles into:

```
time=... level=INFO msg="Oracle-wire (TNS) listening on 127.0.0.1:1521"
```

That line is the one to look for. Until it appears, nothing can connect.

> 📷 **Screenshot 1** — the console just after startup, showing the
> "listening on 127.0.0.1:1521" line.

**Logging.** By default you get a line per query. For a bug report, start it with
`--log-level debug`, which adds every protocol step.

---

## 4. Check it before blaming the client

```bash
./oratofusionproxy doctor
```

It reads the same `.env`, checks your settings, the metadata catalog and the
listen addresses, and prints a line per check. `doctor --deep` additionally runs
a couple of harmless probe queries against Fusion.

Run this first whenever something does not work: it answers "is the proxy the
problem" without involving a client at all.

---

## 5. Connect a client

Every client needs the same three values, whatever it is:

| | |
|---|---|
| **Server** | `127.0.0.1:1521/fusion` |
| **User name** | `FUSION` |
| **Password** | the `ORACLE_WIRE_PASSWORD` value from your `.env` |

### Two credential rules, because they are not the ones you expect

- **Username `FUSION`, uppercase.** Any username authenticates, but every object
  the proxy reports belongs to the single logical schema `FUSION`, and client
  trees filter by owner. Connect as anything else and the table list renders
  successfully and *empty*.
- **The password is yours, not Fusion's.** It is the value you invented in
  step 2. Your Fusion credentials are what the proxy uses on its own side, and
  clients never see them.

The **service name** — the part after the `/` — is ignored; put anything there.
Most clients also let you leave the port out when it is 1521, so
`127.0.0.1/fusion` works too.

### The same three values, per client

**SQL editors — DBeaver, SQL Developer, DataGrip.** Create an Oracle connection:
host `127.0.0.1`, port `1521`, service name `fusion`, and the user and password
above. The Tables tree fills from the `FUSION` schema.

**Command line — SQLcl.** One download, no Instant Client needed.

```bash
sql FUSION/choose-something-here@//127.0.0.1:1521/fusion
```

**Python — python-oracledb, thin mode.** No Oracle client libraries.

```python
import oracledb
con = oracledb.connect(user="FUSION", password="choose-something-here",
                       dsn="127.0.0.1:1521/fusion")
```

**Java / Kotlin — ojdbc.**

```
jdbc:oracle:thin:@//127.0.0.1:1521/fusion
```

**BI and spreadsheets — Power BI, Excel.** *Get Data → From Database → From
Oracle Database*, server `127.0.0.1:1521/fusion`. When asked how to sign in,
choose **Database** (not Windows) and give the user and password above. Then pick
a table in the Navigator, or use *Advanced options → SQL statement*:

```sql
SELECT invoice_id, invoice_num, invoice_date, invoice_amount
FROM ap_invoices_all
WHERE ROWNUM < 100
```

**A real Oracle database** can reach Fusion through a database link — see
[Oracle dblink](r12-dblink.md).

> 📷 **Screenshot 2** — a connection dialog with the three values filled in.
>
> 📷 **Screenshot 3** — the table list, or a loaded result, proving it works.

Full per-client detail, including the quirks of each: [Connecting
clients](clients.md).

---

## When it does not work

| What you see | What it means |
|---|---|
| `ORA-12541: TNS:no listener` | Nothing is listening on that address. The proxy is not running, or `OFPG_ORACLE_LISTEN` is not set — check the console for the "listening on" line. |
| The connection is refused with an unhelpful driver error | The password does not match `ORACLE_WIRE_PASSWORD`. It is the one value that *is* checked. |
| The table list is empty, but the connection succeeded | The username is not `FUSION`. Clients filter the tree by owner. |
| `ORA-00942: table or view does not exist` on a real table | The catalog has not learned that table yet. Run `oratofusionproxy warm-metadata`, or just query it once — the proxy fetches and caches it. |
| The console window vanishes instantly | It exited with an error you did not get to read. Start it from a terminal in that folder instead, so the message stays on screen. |

More in [Troubleshooting](troubleshooting.md).
