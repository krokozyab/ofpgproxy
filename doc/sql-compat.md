# PostgreSQL → Oracle SQL compatibility

`ofpgproxy` speaks the PostgreSQL wire protocol but forwards queries to
Oracle Fusion Cloud through BI Publisher. The two dialects share roughly
90 % of SELECT syntax; the rest is handled by a translator pipeline
before the SQL reaches BIP. This document covers:

- what's rewritten automatically (you can use the PG idiom and it works);
- what's rejected with a clear error (feature not supported);
- what's left alone because the rewrite isn't safe without type
  information, and what workaround you should use.

The authoritative ordering is in
[`internal/translator/translator.go:TranslateFor`](../internal/translator/translator.go).
Each pass has its own file under `internal/translator/` and its own
unit tests.

## Automatic rewrites

### Parameter substitution

| PG form | Oracle form | Pass |
|---|---|---|
| `$1`, `$2`, … | value inlined as literal | `BindPositionalParams` |
| Positional OIDs inferred from the paired `ColumnRef` so binary-format clients (pgJDBC, pgx) get typed `ParameterDescription` | — | `InferParamOIDs` |

### Literals and type-aware boolean handling

| PG form | Oracle form | Notes |
|---|---|---|
| `col = TRUE` on `VARCHAR2` column | `col = 'Y'` | Fusion Y/N flag convention (requires catalog resolver) |
| `col = TRUE` on `NUMBER` column | `col = 1` | |
| `col = FALSE` | `col = 'N'` / `col = 0` | mirrors TRUE rule |
| `WHERE flag` (bare predicate) | `WHERE flag = 'Y'` / `= 1` | catalog-aware, see caveats below |
| `NOT flag`, `AND flag`, `OR flag` | `NOT flag = 'Y'`, … | recursed through `BoolExpr` |
| `'2024-01-15'::date` | `TO_DATE('2024-01-15', 'YYYY-MM-DD')` | ISO shape required |
| `'2024-01-15 12:00:00'::timestamp` / `'2024-01-15T12:00:00'::timestamp` | `TO_TIMESTAMP(…, 'YYYY-MM-DD HH24:MI:SS[.FF]')` | fractional seconds preserved |
| `'2024-01-15T12:00:00Z'::timestamptz` / `+hh:mm` suffix | `TO_TIMESTAMP_TZ(…, 'YYYY-MM-DD HH24:MI:SS TZH:TZM')` | `Z` normalised to `+00:00` |
| Non-ISO date strings (`'Jan 15'::date`) | left alone | falls through to implicit conversion; relies on Oracle `NLS_DATE_FORMAT` matching |

### Operators

| PG form | Oracle form | Pass |
|---|---|---|
| `ILIKE`, `NOT ILIKE` | `UPPER(x) LIKE UPPER(pat)` / `NOT UPPER(x) LIKE UPPER(pat)` | `rewriteILikeOperators` |
| `~` (regex match) | `REGEXP_LIKE(x, pat)` | `rewriteRegexOperators` |
| `~*` (case-insensitive regex) | `REGEXP_LIKE(x, pat, 'i')` | |
| `!~`, `!~*` | `NOT REGEXP_LIKE(...)` / `NOT REGEXP_LIKE(..., 'i')` | |
| `EXCEPT`, `EXCEPT ALL` | `MINUS` | `rewriteExceptToMinus` — token-level, any nesting |

### Functions

| PG form | Oracle form |
|---|---|
| `now()`, `pg_catalog.now()` | `SYSTIMESTAMP` |
| `current_timestamp`, `current_date`, `localtimestamp` | unchanged — Oracle understands these natively |
| `SUBSTRING(x FROM y FOR z)`, `SUBSTRING(x FROM y)`, `SUBSTRING(x FOR z)` | `SUBSTR(x, y, z)` etc. |
| `SUBSTRING(x, y, z)` (comma form) | `SUBSTR(x, y, z)` — Oracle has no `SUBSTRING` alias |
| `date_trunc('year'/'quarter'/'month'/'week'/'day'/'hour'/'minute', expr)` | `TRUNC(expr, 'YYYY'/'Q'/'MM'/'IW'/'DD'/'HH'/'MI')` |
| `date_trunc('millisecond', …)` / 3-arg timezone form | left alone — Oracle rejects, user sees clean error |

### Query-level rewrites

| PG form | Oracle form | Pass |
|---|---|---|
| `LIMIT n`, `OFFSET m`, `LIMIT n OFFSET m`, `OFFSET m LIMIT n` at any nesting depth | `OFFSET m ROWS FETCH FIRST n ROWS ONLY` | `rewriteLimitOffsetAST` — walks every `SelectStmt` |
| Positional `GROUP BY 1, 2` | `GROUP BY <expr1>, <expr2>` (expressions pulled from target list) | `rewritePositionalGroupBy` |
| Positional `ORDER BY 1, 2` | unchanged — Oracle supports it | |
| `SELECT 1`, `SELECT 'x' EXCEPT SELECT 'y'`, `… IN (SELECT 42)` | `… FROM DUAL` injected for any `SelectStmt` with targets but no `FROM` | `injectFromDual` |
| `WITH RECURSIVE r AS (…)` | `WITH r (c1, c2, …) AS (…)` — Oracle requires explicit column list for recursive CTEs | `injectRecursiveCTEColumnList` |
| `RECURSIVE` keyword | dropped — Oracle infers recursion from body | `stripCTEIncompatibleKeywords` |
| `AS MATERIALIZED (…)` / `AS NOT MATERIALIZED (…)` | dropped | same pass |
| `public.<table>` / `"public".<table>` | `<table>` | `stripPublicSchema` |
| `FROM t AS alias` | `FROM t alias` | `stripTableAliasAS` — Oracle rejects `AS` on table aliases |
| `::text`, `::int4`, `::numeric(10,2)` casts | stripped — Oracle handles implicit conversion for most cases | `stripCasts` |
| `SELECT DISTINCT ON (a) x, y FROM t ORDER BY a, c` | `SELECT x, y FROM (SELECT ofpg_src.*, ROW_NUMBER() OVER (PARTITION BY a ORDER BY a, c) AS ofpg_rn FROM (SELECT * FROM t) ofpg_src) WHERE ofpg_rn = 1 ORDER BY a, c` | `rewriteDistinctOn` |
| `SELECT DISTINCT a, b` (plain) | unchanged | |
| Trailing `;` | stripped | |

### Error mapping

Oracle `ORA-…` errors surface to the client with the matching PG
SQLSTATE so tools (dbt, pgJDBC retries, SQLAlchemy) react correctly:

| Oracle code | PG SQLSTATE | PG label |
|---|---|---|
| ORA-00942 | 42P01 | `undefined_table` |
| ORA-00904 | 42703 | `undefined_column` |
| ORA-00905, ORA-00906, ORA-00907, ORA-00920, ORA-00923, ORA-00933, ORA-00936 | 42601 | `syntax_error` |
| ORA-01722 | 22P02 | `invalid_text_representation` |
| ORA-01400 | 23502 | `not_null_violation` |
| ORA-00001 | 23505 | `unique_violation` |
| ORA-02291, ORA-02292 | 23503 | `foreign_key_violation` |
| ORA-01031 | 42501 | `insufficient_privilege` |
| ORA-19202 | 42804 (with type/conversion hints in message) / 22000 | `datatype_mismatch` / `data_exception` |
| ORA-12…, ORA-03… | 08000 | `connection_exception` |
| Other ORA- | 42000 | `syntax_error_or_access_rule_violation` |
| SOAP `AuthError` | 28000 | `invalid_authorization_specification` |
| SOAP `Fault` | 08000 | `connection_exception` |

PG-only features we refuse with `0A000 feature_not_supported`:

- `DISTINCT ON` in combination with `UNION`, `INTERSECT`, CTE body,
  `SELECT *`, or an anonymous target expression.

## Known limitations (no auto-rewrite)

These queries reach Oracle verbatim because the rewrite would be
ambiguous or require per-column metadata we don't currently thread
through. They emit an honest Oracle error; the user-side fix is short.

### `ROW_NUMBER() OVER ()` without `ORDER BY`

```sql
-- Fails: ORA-30485 "missing ORDER BY expression in the window specification"
SELECT period_name, ROW_NUMBER() OVER () FROM gl_periods
```

**Workaround:** add any deterministic ordering inside `OVER`:

```sql
SELECT period_name, ROW_NUMBER() OVER (ORDER BY period_name) FROM gl_periods
```

For "don't care" ordering use `ORDER BY NULL` — Oracle treats it as a
no-op sort but satisfies the grammar.

PG allows `ROW_NUMBER() OVER ()` because it falls back to "natural
order of the subselect". Oracle's ranking functions (`ROW_NUMBER`,
`RANK`, `DENSE_RANK`, `NTILE`) make the `ORDER BY` inside `OVER`
mandatory.

### Named `WINDOW` clause

```sql
-- Fails: ORA-30484 "missing window specification for this function"
SELECT period_name,
       FIRST_VALUE(period_name) OVER w
FROM   gl_periods
WINDOW w AS (PARTITION BY period_year ORDER BY period_num)
```

**Workaround:** inline the window specification at each reference:

```sql
SELECT period_name,
       FIRST_VALUE(period_name) OVER (PARTITION BY period_year ORDER BY period_num)
FROM   gl_periods
```

Oracle doesn't support the SQL-standard `WINDOW` clause; PG does.
Automatic inlining would require rewriting every `OVER w` reference
consistently, which we haven't implemented.

### `IS DISTINCT FROM` / `IS NOT DISTINCT FROM`

```sql
-- Fails: ORA-00908 "missing NULL keyword"
SELECT period_name FROM gl_periods WHERE adjustment_period_flag IS DISTINCT FROM 'Y'
```

**Workaround:** expand the operator manually:

```sql
SELECT period_name
FROM   gl_periods
WHERE  adjustment_period_flag <> 'Y'
       OR (adjustment_period_flag IS NULL AND 'Y' IS NOT NULL)
       OR (adjustment_period_flag IS NOT NULL AND 'Y' IS NULL)
```

Or use `DECODE` for the `IS NOT DISTINCT FROM` (null-safe equality)
direction:

```sql
-- a IS NOT DISTINCT FROM b
DECODE(a, b, 1, 0) = 1
```

### Qualified reference to a `USING` column

```sql
-- Fails: ORA-25154 "column part of USING clause cannot have qualifier"
SELECT
    gjl.je_header_id,
    gjh.description,
    gjl.accounted_dr,
    DENSE_RANK() OVER (
        PARTITION BY gjl.je_header_id
        ORDER BY gjl.accounted_dr
    ) AS dr_rank
FROM   gl_je_lines   gjl
INNER JOIN gl_je_headers gjh USING (je_header_id)
```

**Workaround:** either drop the qualifier on the shared column
(`je_header_id` without `gjl.`) or switch `USING` for an explicit `ON`
clause:

```sql
-- (a) unqualified USING-column
SELECT je_header_id, gjh.description, gjl.accounted_dr,
       DENSE_RANK() OVER (PARTITION BY je_header_id ORDER BY gjl.accounted_dr) AS dr_rank
FROM   gl_je_lines gjl
INNER JOIN gl_je_headers gjh USING (je_header_id)

-- (b) explicit ON
SELECT gjl.je_header_id, gjh.description, gjl.accounted_dr,
       DENSE_RANK() OVER (PARTITION BY gjl.je_header_id ORDER BY gjl.accounted_dr) AS dr_rank
FROM   gl_je_lines gjl
INNER JOIN gl_je_headers gjh ON gjh.je_header_id = gjl.je_header_id
```

PostgreSQL tolerates `gjl.je_header_id` even when the column appears in
`USING (...)`. Oracle insists the USING-merged column is unqualified
everywhere it's referenced (SELECT list, WHERE, PARTITION BY, ORDER BY).
This is an Oracle-side grammar rule, not something the translator
can rewrite without substantially reshaping the query.

### Lowercase quoted identifiers

```sql
-- Fails: ORA-00904 "\"period_name\": invalid identifier"
SELECT "period_name" FROM gl_periods
```

**Workaround:** either drop the quotes (Oracle folds unquoted
identifiers to UPPER, which matches what's stored in the data
dictionary) or uppercase the quoted name:

```sql
SELECT period_name   FROM gl_periods  -- preferred
SELECT "PERIOD_NAME" FROM gl_periods  -- also works
```

PG folds unquoted identifiers to lowercase and stores them that way;
Oracle folds to upper. Some hand-written SQL (or `\d`-style DBeaver
probes) emits `"period_name"` assuming PG semantics — Oracle then
looks for a column literally named `period_name` and fails.

Auto-detecting "this lowercase quoted identifier is actually a table
or column name, not a case-sensitive string" needs catalog
consultation per identifier, which we don't do yet.

### `COPY`, `LISTEN`/`NOTIFY`, prepared-statement reuse across sessions,
cursors, large objects, any write path

Explicitly out of scope — BI Publisher is read-only, and the proxy is
designed around that fact. See [`ofpgproxy-architecture.md`](ofpgproxy-architecture.md)
for the full non-goals list.

## How to report a new gap

When a query you care about lands as `unsupported query` or an
unexpected `ORA-…`:

1. Run the proxy with `--log-queries=true` (default) — it logs every
   statement with parse duration and routing decision.
2. Capture the failing SQL from the log and the Oracle error message.
3. Check whether the shape appears in this document already.
4. If not, open an issue or PR with: the minimal repro query, the
   tool that generated it (DBeaver / Metabase / etc.), and the Oracle
   error. For common PG idioms we typically add an AST pass
   following the pattern in `internal/translator/*_rewrite.go`.
