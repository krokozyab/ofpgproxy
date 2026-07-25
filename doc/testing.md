# Testing & verification

`ofpgproxy` ships as a single obfuscated binary — the source is closed. This
page exists so you can judge what the proxy actually does without reading it:
every capability listed here is backed either by a byte-exact protocol test
that runs on every build, or by a live run against a real Oracle Fusion Cloud
tenant with the results compared value-by-value against an independent path to
the same data.

Nothing on this page is "should work in theory". Where something is only
partially covered, it says so, and the gaps are listed at the end.

## Tested against

| Component | Version actually exercised |
|---|---|
| Backend | Oracle Fusion Cloud — BI Publisher universal report (`RP_ARB.xdo`) |
| Oracle Database as dblink initiator | **19c** (19.3.0.0, Enterprise Edition) |
| Oracle Database as dblink initiator | **26ai** (Oracle Database Free) |
| Thin driver (Python) | **python-oracledb 4.0.1**, thin mode |
| Thin driver (Java) | **ojdbc11 26.1.0** — the driver behind DBeaver, SQL Developer, SQLcl |
| OCI / thick client | **Oracle Instant Client 23.3** (`sqlplus` 23.0.0.0.0) |

Two different TTC wire dialects are covered by that matrix: the 19c-era
protocol (TTC version 6) and the modern 23ai/26ai protocol (TTC version 8).
They differ in handshake replies, describe framing and end-of-call
bookkeeping, and the proxy implements both — see
[Oracle version coverage](#oracle-version-coverage) below.

## How it's verified

**1. Byte-exact protocol tests.** Over 400 Go tests cover the Oracle-wire
implementation alone. Most of them are not "does it round-trip" tests — they
assert the exact bytes the proxy puts on the socket against captures taken
from a **real Oracle server** answering the same query. Where a client's
behaviour depends on a field nobody documents, the field's value came from a
capture, not from a guess. Handshake, authentication (O5LOGON), describe,
row data, LOB descriptors, multi-round fetch, break/reset markers, packet
splitting at the negotiated SDU and error frames all have this kind of
coverage.

**2. Live end-to-end runs.** A harness drives each client family
(thin, ojdbc, `sqlplus`, and a real Oracle instance opening a database link)
against a proxy connected to a live Fusion tenant, runs a corpus of
representative `SELECT`s, and compares every returned value against the same
query executed through a completely separate Fusion access path. A row that
merely *arrives* is not a pass; it has to match.

## Client compatibility

| Client | Wire | Status |
|---|---|---|
| python-oracledb (thin) | TNS/TTC | Verified end-to-end against live Fusion |
| SQL Developer / SQLcl / DBeaver (ojdbc thin) | TNS/TTC | Verified end-to-end, incl. IDE tree browsing and result-grid scrolling |
| `sqlplus` (Instant Client, OCI) | TNS/TTC | Verified end-to-end — login, describe, single- and multi-row fetch, LOB read |
| Oracle Database via `CREATE DATABASE LINK` | TNS/TTC | Verified end-to-end from both a 19c and a 26ai initiator |

Connection details and per-client caveats live in
[Connecting clients](clients.md).

## Oracle version coverage

An Oracle server opening a database link to the proxy negotiates a TTC version
and then expects that dialect's framing exactly — an initiator that gets a
23ai-shaped reply from what it believes is a 19c peer disconnects rather than
complains. Both dialects are implemented and both were driven from a real
server, not a simulator:

| Initiator | TTC version | Verified |
|---|---|---|
| Oracle 19c (19.3.0.0 EE) | 6 | Handshake, session sync, describe, fetch, teardown |
| Oracle 26ai (Free) | 8 | Same, plus wide projections and LOB paths |

The version-specific branches are selected from the version the client itself
advertises during the protocol handshake, so a 19c fix cannot regress a 26ai
session and vice versa — the 23ai/26ai templates are asserted byte-for-byte
unchanged by the 19c tests.

## Data-type fidelity

Every scalar Oracle type the backend can produce has an encoder with tests
against real captures:

| Type | Covered |
|---|---|
| `NUMBER` | Integers, scale/precision, negatives, zero, `NULL` |
| `VARCHAR2`, `CHAR`, `NVARCHAR2` | Incl. values longer than a single wire chunk (long-value chunking, per client dialect) |
| `DATE` | Value-exact against the backend |
| `TIMESTAMP`, `TIMESTAMP WITH TIME ZONE`, `TIMESTAMP WITH LOCAL TIME ZONE` | Incl. fractional seconds and zone offsets |
| `INTERVAL YEAR TO MONTH`, `INTERVAL DAY TO SECOND` | Encoded |
| `BINARY_FLOAT`, `BINARY_DOUBLE` | Encoded |
| `RAW`, `LONG RAW` | Incl. the byte/char length distinction in describe |
| `LONG` | Streaming path, one row per fetch round |
| `CLOB` | Native LOB locator and descriptor; length counted in UTF-16 code units, so text outside the BMP (emoji, rare CJK) reports the same length Oracle reports |
| `BLOB` | Locator read path |
| `NULL` in any position | Single-column, mixed, all-`NULL` rows, and zero-row results |

The UTF-16 detail is not pedantry: getting it wrong truncates every CLOB that
contains an astral character, and only for those rows.

## Query & feature coverage

- **Result shapes** — zero rows, one row, many rows; one column, two columns,
  and wide projections beyond 255 columns (a real table with 280+ columns is
  part of the live corpus, because the >255 case changes the wire framing).
- **Multi-round fetch** — result sets larger than the client's array size,
  including the intermediate-vs-terminal end-of-call distinction that decides
  whether a client keeps fetching or disconnects.
- **Bind variables** — positional and named, single and multiple.
- **Statement reuse** — re-execute of an already-described cursor, and
  fetch-by-cursor-id, so IDE workflows (run a query, run a count, scroll back
  through the first grid) behave.
- **Oracle SQL passes through unchanged.** Queries against Fusion tables are
  sent to the backend as Oracle SQL — `DECODE`, `(+)` outer joins, `BITAND`,
  `ROWNUM`, analytic functions and the rest are evaluated by Oracle itself,
  not reinterpreted by the proxy. There is no dialect to learn and no rewrite
  layer to be surprised by on the data path.
- **Data dictionary** — `ALL_*` / `USER_*` / `DBA_*` queries are answered
  locally from the cached metadata database rather than sent to Fusion, so
  connecting an IDE costs no backend calls. That local layer is where a small
  amount of SQL rewriting does happen, and it has its own tests. See
  [Metadata](metadata.md).
- **Session lifecycle** — logon, logoff, graceful shutdown while a call is in
  flight, client-side cancel (break/reset) mid-query, and the corresponding
  `ORA-01013`.

## Read-only, guaranteed

The backend physically cannot write — BI Publisher serves reports. Write
attempts are refused at the proxy with a normal Oracle error (a dblink write,
for instance, surfaces `ORA-16000`), so a tool that probes for writability
gets a truthful answer instead of a hang or a silent no-op. There is no
code path from a client statement to a mutation, and the read-only refusals
are themselves tested.

## What isn't covered

Stated plainly, so nobody discovers these the hard way:

- **Thick-client Advanced Networking (ANO/native encryption).** `sqlplus` and
  other OCI clients work in the configurations listed above; a client
  *forcing* Oracle Advanced Networking negotiation is not supported.
- **Object types, collections, `XMLTYPE`, `REF CURSOR`, PL/SQL blocks.** Not
  implemented — scalar `SELECT` result sets only.
- **Database-link teardown under host-level NAT.** When the proxy runs
  outside the container network of the initiating Oracle server, the
  redial the Oracle connection pool performs after a link is closed can be
  blocked by the network, not by the proxy. Verified clean when both sides
  can reach each other directly.
- **Load and concurrency at scale.** Correctness is verified thoroughly;
  published throughput numbers are not something this page claims. The
  backend is the bottleneck by orders of magnitude — see
  [Troubleshooting](troubleshooting.md) for what slow means in practice.

If you hit something that this page implies should work and it doesn't, that
is a bug worth reporting — the gap list above is meant to be exhaustive.
