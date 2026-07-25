# Metadata catalog

The catalog is a DuckDB file describing your Fusion tenant's schema. Two
different sets of names meet in it, which is worth getting straight up front:

- **What fills it.** Fusion's own application dictionary —
  `FND_TABLES`, `FND_VIEWS`, `FND_COLUMNS`, `FND_PRIMARY_KEY_COLUMNS` and the
  module taxonomy. Those are the queries the proxy sends to BI Publisher, and
  their rows land in the `cached_*` tables below.
- **What it answers.** The Oracle data dictionary your client asks for —
  `ALL_TABLES`, `ALL_TAB_COLUMNS`, `ALL_OBJECTS`, `USER_*`, `DBA_*`, `DUAL`.
  Those are views over `cached_*`, computed locally.

So an IDE's schema tree, autocomplete and column lists resolve without a BI
Publisher round trip each. The catalog also gives every result column its real
Oracle type and width, which is how `TIMESTAMP`, `RAW` and `CLOB` are described
correctly in query results rather than guessed from the values.

(One thing it does not cover: sqlplus's own `DESCRIBE` command, which is a
separate protocol call the proxy does not implement — see
[Connecting clients](clients.md#sqlplus).)

## It fills itself

There is nothing to install. On first start the proxy creates
`metadata-cache.db` next to the binary and populates it from your tenant as it
is used:

- **The table list** is fetched in the background the first time a client asks
  for it — tens of BI Publisher calls, a couple of minutes, while the proxy
  keeps serving.
- **A table's columns** are fetched the first time a client asks for that
  table. One call, kept for good. Ten clients expanding the same tree node
  cost one call, not ten; a table your tenant doesn't have is remembered so a
  polling client can't generate a call per attempt.

Progress is in the log, and `orawire_metadata_*` counters on `/metrics` show
how many fetches happened and whether a bootstrap is running.

To fill it in one go instead — thousands of BI Publisher calls, so run it
deliberately and throttle it on a shared tenant:

```bash
ofpgproxy warm-metadata --fusion-host <host> --page-size 500 --interval 2s
```

## Flags

| Flag | Meaning |
|---|---|
| `--metadata-cache <path>` | where the writable catalog lives. Defaults to `metadata-cache.db` beside the binary |
| `--metadata-path <path>` | an existing catalog file. On its own it is used **read-only**; with `--metadata-cache` it seeds the cache once and is never written to |
| `--metadata-refresh <duration>` | re-read the tenant's table list on a timer. Off by default; `SIGHUP` always triggers one |

A pre-built catalog is attached to each release for anyone who wants a
populated cache without any backend calls. It is optional.

## What's in it

Four tables, all populated from Fusion's `FND_TABLES` / `FND_VIEWS` /
`FND_COLUMNS` / `FND_PRIMARY_KEY_COLUMNS` / module registry:

| Table | Rows | Purpose |
|---|---|---|
| `cached_tables` | ~30 000 | one row per table/view (name, module, type) |
| `cached_columns` | ~1 200 000 | one row per column (name, Oracle type, width, nullability) |
| `cached_primary_keys` | ~40 000 | PK columns for join discovery |
| `cached_modules` | a few hundred | Fusion module index used for schema naming |

Sizes are typical — your tenant may differ. A self-populated cache holds only
what has actually been asked for, which is normally far less.

## Oracle type codes inside `cached_columns.column_type`

The catalog uses a compact form for the common cases:

| Code | Oracle type |
|---|---|
| `V` | `VARCHAR2` / `NVARCHAR2` |
| `N` | `NUMBER` |
| `I` | `INTEGER` |
| `D` | `DATE` |
| `CHAR`, `NCHAR`, `CLOB`, `NCLOB`, `BLOB`, `RAW`, `TIMESTAMP`, `TIMESTAMP_WITH_TIMEZONE`, `XMLTYPE`, `BFILE`, `UROWID` | full Oracle name |

## Refreshing

Fusion's schema is static on our timescale, so nothing refreshes on a timer by
default. When your tenant does change — module install, patch rollout, custom
DFF deployment — new objects appear once the table list is re-read:

- **`SIGHUP`** (`kill -HUP $(pgrep ofpgproxy)`) reloads the catalog and, when
  it is writable, re-reads the table list from the tenant. Connected clients
  see the new schema on their next query; in-flight queries finish against the
  previous catalog.
- **`--metadata-refresh 24h`** does the same on a timer.
- **`ofpgproxy warm-metadata`** rebuilds everything, including primary keys
  and modules.

Columns of a table added since the last fetch are picked up on demand, with no
action at all.

## Health

`ofpgproxy doctor` reports whether the catalog is actually populated —
an empty one answers every query with nothing, which otherwise looks like
success — and how long ago the table list was read from the tenant:

```
PASS  metadata.open        metadata cache opened
PASS  metadata.schema      all 4 cached_* tables present
WARN  metadata.counts      cached_tables is EMPTY — no object will resolve
PASS  metadata.freshness   table list refreshed 2h13m ago
```

## What happens without a catalog

If the cache cannot be created (a read-only install directory, say) the proxy
warns and keeps running:

- `SELECT`s against known table names still work — the SQL goes to BI
  Publisher as-is.
- Session no-ops (`ALTER SESSION`, `SET`, `COMMIT`) still work.
- Data-dictionary queries return nothing, so an IDE's tree appears empty and
  column types fall back to being inferred from the values.

Point `--metadata-cache` at a writable path to fix it.
