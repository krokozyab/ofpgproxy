# Connecting to ofpgproxy

Four small programs, one per ecosystem, that do the same thing: connect to the
proxy, read ten invoices out of Oracle Fusion, print them. Nothing about them
is proxy-specific — each uses its language's ordinary Oracle driver, pointed at
the proxy's host and port instead of a database.

That is the whole claim this project makes, so these are the shortest honest
demonstration of it.

```sql
SELECT invoice_num, invoice_date, invoice_amount
FROM ap_invoices_all
WHERE ROWNUM <= 10
```

| | driver | run |
|---|---|---|
| [Python](python/) | `python-oracledb` (thin, pure Python) | `python invoices.py` |
| [Java](java/) | Oracle JDBC thin (`ojdbc11`) | `java -cp ojdbc11.jar Invoices.java` |
| [C#](csharp/) | `Oracle.ManagedDataAccess.Core` | `dotnet run` |
| [Go](go/) | `github.com/sijms/go-ora/v2` (pure Go) | `go run invoices.go` |

## Before you run

Start the proxy with its Oracle-wire listener and point it at your tenant —
see [../doc/quickstart.md](../doc/quickstart.md). The examples default to
`127.0.0.1:1521`, service `FUSION`, user `fusion`, and read the password from
`ORACLE_WIRE_PASSWORD`.

The username is not checked: real access control is the Fusion session the
proxy holds underneath. The password is the one you set with
`--oracle-password`. The service name is ignored.

## What to expect

Ten rows. The first query of a session is slower than the rest — it is a SOAP
call to BI Publisher, which takes seconds, not milliseconds. That is the
backend, not the proxy; see [what to expect of it](../doc/limits.md).

Read-only: the proxy rejects any write, by design, because BI Publisher cannot
perform one.
