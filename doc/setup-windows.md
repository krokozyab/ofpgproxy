# Setting it up on Windows (and connecting Excel)

A step-by-step walkthrough for someone who would rather not assemble a command
line. Everything here works the same on macOS and Linux; only the file paths and
the way you start the program differ.

You need three things, in this order:

1. the program,
2. a small text file next to it that holds your settings,
3. one client to prove it works.

---

## 1. Download and unpack

Take the latest `oratofusionproxy-*.zip` from
[Releases](https://github.com/krokozyab/oracle-fusion-tns-proxy/releases/latest)
and unpack it into a folder of its own — for example:

```
C:\Users\you\oratofusionproxy\
```

A folder of its own matters: the program keeps its settings file and its
metadata cache beside itself, and an unpacked-into-Downloads copy mixes them
with everything else you have downloaded.

> **Windows SmartScreen** may say the publisher is unknown, because the binary
> is not code-signed. *More info → Run anyway*.

---

## 2. Write the settings file

In that same folder create a file called **`.env`** — the name is exactly that,
starting with a dot and with no `.txt` on the end. In Notepad use *Save as*, set
*Save as type* to *All files*, and type `.env` as the name.

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

That is the whole configuration for a first run. Every other setting has a
working default; see [Configuration](configuration.md) when you need one.

**Prefer the command line?** The same thing, without the file:

```powershell
.\oratofusionproxy.exe --oracle-listen 127.0.0.1:1521 --oracle-password choose-something-here
```

Flags and real environment variables both win over the file, so you can keep a
`.env` and override one value for a single run.

---

## 3. Start it

Double-click `oratofusionproxy.exe`. A console window opens and stays open —
**that window is the proxy**. Closing it stops the proxy.

With `FUSION_AUTH_TYPE=sso` your browser opens once for the Fusion login. After
you sign in, the console settles into:

```
time=... level=INFO msg="Oracle-wire (TNS) listening on 127.0.0.1:1521"
```

That line is the one to look for. Until it appears, nothing can connect.

> 📷 **Screenshot 1** — the console window just after startup, showing the
> "listening on 127.0.0.1:1521" line.

**Logging.** By default you get a line per query. For a bug report, start it
with `--log-level debug`, which adds every protocol step.

---

## 4. Check it before blaming the client

```powershell
.\oratofusionproxy.exe doctor
```

It reads the same `.env`, checks your settings, the metadata catalog and the
listen addresses, and prints a line per check. `doctor --deep` additionally runs
a couple of harmless probe queries against Fusion.

Run this first whenever something does not work. It answers "is the proxy the
problem" without involving Excel at all.

---

## 5. Connect Excel

**Data → Get Data → From Database → From Oracle Database.**

> 📷 **Screenshot 2** — Excel's ribbon, Get Data menu open on
> *From Database → From Oracle Database*.

Fill the dialog in:

| Field | What to type |
|---|---|
| **Server** | `127.0.0.1:1521/fusion` |
| **Data Connectivity mode** | Import (or DirectQuery in Power BI) |

> 📷 **Screenshot 3** — the Oracle database dialog with the server filled in.

Excel then asks how to sign in. Choose **Database** (not Windows), and enter:

| Field | What to type |
|---|---|
| **User name** | `FUSION` |
| **Password** | the `ORACLE_WIRE_PASSWORD` value from your `.env` |

> 📷 **Screenshot 4** — the credentials dialog, Database tab selected, user name
> `FUSION`.

Then pick a table in the Navigator, or use **Advanced options → SQL statement**
to paste a query such as:

```sql
SELECT invoice_id, invoice_num, invoice_date, invoice_amount
FROM ap_invoices_all
WHERE ROWNUM < 100
```

> 📷 **Screenshot 5** — the Navigator listing Fusion tables, or the loaded
> worksheet.

### Two credential rules, because they are not the ones you expect

- **Username `FUSION`, uppercase.** Any username authenticates, but every object
  the proxy reports belongs to the single logical schema `FUSION`, and client
  trees filter by owner. Connect as anything else and the table list renders
  successfully and *empty*.
- **The password is yours, not Fusion's.** It is the `ORACLE_WIRE_PASSWORD`
  value you invented in step 2. Your Fusion credentials are what the proxy uses
  on its own side, and clients never see them.

The **service name** — the part after the `/` — is ignored; put anything there.
Most clients also let you leave the port out when it is 1521, which is why
`127.0.0.1/fusion` works too.

---

## 6. Other clients

Once Excel works, everything else is the same three values.

```bash
# SQLcl — one download, no Instant Client needed
sql FUSION/your-password@//127.0.0.1:1521/fusion
```

```python
# python-oracledb, thin mode — no Oracle client libraries
import oracledb
con = oracledb.connect(user="FUSION", password="your-password",
                       dsn="127.0.0.1:1521/fusion")
```

DBeaver, SQL Developer, Power BI and anything else that speaks to an Oracle
database work the same way — see [Connecting clients](clients.md).

---

## When it does not work

| What you see | What it means |
|---|---|
| `ORA-12541: TNS:no listener` | Nothing is listening on that address. The proxy is not running, or `OFPG_ORACLE_LISTEN` is not set — check the console for the "listening on" line. |
| The connection is refused with an unhelpful driver error | The password does not match `ORACLE_WIRE_PASSWORD`. It is the one value that *is* checked. |
| The table list is empty, but the connection succeeded | The username is not `FUSION`. Clients filter the tree by owner. |
| `ORA-00942: table or view does not exist` on a real table | The catalog has not learned that table yet. Run `oratofusionproxy warm-metadata`, or just query it once — the proxy fetches and caches it. |
| The console window vanishes instantly | It exited with an error you did not get to read. Open PowerShell in that folder and run `.\oratofusionproxy.exe` there, so the message stays on screen. |

More in [Troubleshooting](troubleshooting.md).
