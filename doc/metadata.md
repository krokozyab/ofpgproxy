# Metadata catalog

`metadata.db` is a DuckDB file that describes your Fusion tenant's schema. The proxy reads it at startup to serve `pg_catalog` and `information_schema` queries — what DBeaver's tree, `\d`, and any tool that does a catalog sync need.

## What's in it

Four tables, all populated from Fusion's `FND_TABLES` / `FND_COLUMNS` / `FND_VIEWS` / module registry:

| Table | Rows | Purpose |
|---|---|---|
| `cached_tables` | ~30 000 | one row per table/view (name, module, type) |
| `cached_columns` | ~1 200 000 | one row per column (name, Oracle type, width, nullability) |
| `cached_primary_keys` | ~80 000 | PK columns for join discovery |
| `cached_modules` | a few hundred | Fusion module index used for schema naming |

Sizes are typical — your tenant may differ.

The proxy opens this file **read-only**. Multiple proxy instances on the same machine can share one file.

## Oracle type codes inside `cached_columns.column_type`

The dumper uses a compact form for the common cases:

| Code | Oracle type |
|---|---|
| `V` | `VARCHAR2` / `NVARCHAR2` |
| `N` | `NUMBER` |
| `I` | `INTEGER` |
| `D` | `DATE` |
| `CHAR`, `NCHAR`, `CLOB`, `NCLOB`, `BLOB`, `RAW`, `TIMESTAMP`, `TIMESTAMP_WITH_TIMEZONE`, `XMLTYPE`, `BFILE`, `UROWID` | full Oracle name |

The proxy maps these to PG OIDs on the fly for `pg_attribute` results, and uses the same information for the type-aware boolean rewrite (`= TRUE` on VARCHAR2 columns becomes `= 'Y'`, on NUMBER becomes `= 1`).

## Refreshing

When Fusion adds tables (module install, patch rollout, custom-DFF deployment) the shipped catalog gets out of date. Options:

### Atomic reload (preferred)

1. Regenerate the file with the accompanying catalog dumper tool against your tenant.
2. Overwrite `metadata.db` in place.
3. `kill -HUP $(pgrep ofpgproxy)`

The proxy swaps the new file in atomically. Connected clients see the new schema on their next query — no reconnect. The previous catalog is held briefly so in-flight queries finish cleanly.

### Restart

If you prefer a full restart, simply replace the file and bounce the proxy. Clients re-authenticate (SSO) on the new process.

## Selecting by module

The tenant catalog ships with every reachable table by default; large deployments may prefer to filter to a subset of Fusion modules (GL, AP, AR only, say). The dumper accepts module filters — consult its documentation.

## What happens without a catalog

If `--metadata-path` is omitted, the proxy still boots and:

- Foreign SELECTs against known table names still work (the SQL goes to BIP as-is).
- `SELECT 1`, session SET / SHOW / BEGIN / COMMIT all work.
- `pg_catalog` / `information_schema` queries return empty. DBeaver's tree appears empty, `\d` returns nothing, Metabase's schema sync finds zero tables.

Useful for basic handshake / smoke testing. Production deployments should always mount a catalog.
