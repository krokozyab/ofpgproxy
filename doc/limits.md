# Limits & guardrails

Everything here is a real, checkable number from the shipping build — what one
query costs your tenant, when the proxy gives up, and where the hard ceilings
are. If you run a database for a living, this is the page to read before
pointing anything production-shaped at it.

## The one thing to internalise

**Every query is a BI Publisher report execution.** Not a database session, not
a cursor against a real instance — a SOAP call that runs a report, waits for
Fusion to render the result as XML, and parses it. That call takes hundreds of
milliseconds to several seconds *before* any row count matters.

So the cost model is: **latency per call, not rows per second.** Ten small
queries cost ten calls; one query returning 50 000 rows may cost one. Design
for the former being the expensive shape, which is the opposite of the
instinct a DBA has about a real database.

## Concurrency

| | Default | Flag |
|---|---|---|
| Concurrent SOAP calls | **1** | `--soap-concurrency` |

One call in flight at a time, by default. A heavy query on one connection
makes every other connection wait. This is deliberate and matches `ofjdbc`:
BI Publisher accumulates server-side sessions faster than it releases them,
and an aggressive client pushes the tenant into refusing logins — which
affects the humans using Fusion, not just you.

Raise it to 2–4 for interactive use. Above 4, test on your own tenant before
committing; some releases throttle hard and you will see `WSM-07501` or login
refusals before you see a speedup.

Data-dictionary queries (an IDE's schema tree, autocomplete, column lists)
never touch this gate — they are answered from the local catalog.

## Result size

| | Default | Flag / limit |
|---|---|---|
| Rows per SOAP call for GUI clients | 200 | `--foreign-batch-size` (0 disables paging) |
| Rows per SOAP call for code clients, `sqlplus`, `dblink` | the whole result | add `ROWNUM <= n` / `FETCH FIRST n ROWS ONLY` |
| Rows one FETCH may materialise | 10 000 | hard cap |
| Bytes in one FETCH response | 16 MiB | hard cap |
| Inbound TNS packet | 16 MiB | hard cap |

The three hard caps are not tuneable: they bound what a client can make the
proxy allocate. Hitting the row and byte caps is invisible — the client simply
fetches again.

**Rows are paged, not streamed end to end.** Each backend call reads and parses
one complete BI Publisher response before the proxy hands rows to the client.
For a GUI client that means one page at a time (200 rows by default). For a
code client, `sqlplus` or `dblink`, an unbounded query is one call — so that
whole result is materialised once, in the proxy and in your client. This is the
reason to bound big queries, and the reason the row window above matters.

**A practical ceiling for a single query is tens of thousands of rows.**
Nothing in the proxy stops more, but the whole result passes through one BI
Publisher rendering, and Fusion's own report timeouts become the binding
constraint long before the proxy's do. If
you need millions of rows, extract them in ranges (by date, by ledger, by ID
band) rather than in one statement.

## Timeouts

| What | Value | Notes |
|---|---|---|
| SOAP connect | 30 s | |
| SOAP read | 120 s | a report that renders longer than this fails the call |
| SOAP retries | 3 attempts | exponential backoff from 1 s, capped at 30 s, ±20 % jitter; only for retryable failures |
| Protocol watchdog | 30 s (`--oracle-protocol-timeout`, 0 disables) | **never** fires while a backend call is in flight |

The watchdog deserves the emphasis: it only trips on a strict, already-obligated
protocol transition — a client that started a handshake and stopped mid-way. An
ordinary idle session is not timed out, and neither is a slow Fusion query. A
long-running query ends by the SOAP read timeout above, not by the watchdog.

Retry tuning is available through `FUSION_SOAP_RETRY_MAX_ATTEMPTS`,
`FUSION_SOAP_RETRY_BASE_DELAY_MS` and `FUSION_SOAP_RETRY_MAX_DELAY_MS` if your
tenant's behaviour warrants it.

## Cancellation

Ctrl-C in `sqlplus` / SQL Developer's Cancel really cancels: the proxy answers
the client's break with `ORA-01013` and aborts the in-flight SOAP call rather
than letting it finish into a discarded buffer. The BI Publisher side may still
complete its own work — Fusion has no notion of the cancel — so a cancelled
heavy query does not immediately free tenant capacity.

## Large types

| Type | Behaviour |
|---|---|
| `CLOB` / `NCLOB` | Native LOB locators; the client reads them over `LOB_OP`, no truncation. Length is counted in UTF-16 code units, matching Oracle's own `LENGTH()`, so text outside the BMP is not truncated |
| `BLOB` | Native locator, same path |
| `XMLTYPE` | Treated as large text (`CLOB`) |
| `LONG` / `LONG RAW` | Streamed inline, one row per fetch round |
| `RAW` | Inline, byte length taken from the catalog |

**A `CLOB` column stops Excel and Power BI Desktop.** Both use **unmanaged**
ODP.NET — that is what Oracle's own
[Client for Microsoft Tools](https://www.oracle.com/database/technologies/appdev/ocmt.html)
installs for them — and on that path a table with a `CLOB` column does not
load: the query runs, the first batch goes out, and the client then sends
nothing and times out. Project the column away and the same table loads.

Not a CLOB problem in general. Every other client reads the same column of the
same table fine, including ojdbc and **managed** ODP.NET — which is what OCMT
installs for SSIS, SSRS and SSDT, so those are unaffected. See
[Troubleshooting](troubleshooting.md#excel--power-query-a-refresh-fails-with-no-message-on-one-particular-table)
for the symptom as it appears in Excel.

One exception worth knowing: ojdbc-family drivers can be switched to an
inline-`VARCHAR2` fallback for LOBs (`OFPG_ORACLE_OJDBC_LOB_DOWNGRADE=1`),
which **truncates at 32 767 bytes**. That is off by default — native locators
are the default path — but if you turn it on because a driver misbehaves, know
what you are trading.

## Read-only, structurally

BI Publisher cannot write. Any `INSERT` / `UPDATE` / `DELETE` / `MERGE` / DDL
is refused by the proxy with `ORA-16000` before it reaches Fusion, on every
client including `dblink`. There is no flag to relax this and no code path that
could.

## What this is not

- **Not an ETL engine.** Moving whole modules out of Fusion nightly is what
  BICC and OCI Data Integration exist for. This is for reading what you need,
  when you need it, with the tools you already have.
- **Not a low-latency source.** A dashboard refreshing every 30 seconds against
  it will be unhappy, and so will your tenant.
- **Not multi-tenant.** One proxy process holds one Fusion session and one set
  of credentials; every client through it sees the same tenant with the same
  access.
