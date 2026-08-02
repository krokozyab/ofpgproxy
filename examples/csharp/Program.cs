// Read ten invoices from Oracle Fusion through ofpgproxy.
//
// Oracle.ManagedDataAccess is the driver Power BI and SSIS use, unchanged and
// pointed at the proxy instead of a database.
//
//     ORACLE_WIRE_PASSWORD=... dotnet run

using System.Globalization;
using Oracle.ManagedDataAccess.Client;

static string Env(string name, string fallback)
{
    var value = Environment.GetEnvironmentVariable(name);
    return string.IsNullOrWhiteSpace(value) ? fallback : value;
}

const string Sql = """
    SELECT invoice_num, invoice_date, invoice_amount
    FROM ap_invoices_all
    WHERE ROWNUM <= 10
    """;

var descriptor =
    $"(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST={Env("OFPG_HOST", "127.0.0.1")})" +
    $"(PORT={Env("OFPG_PORT", "1521")}))" +
    $"(CONNECT_DATA=(SERVICE_NAME={Env("OFPG_SERVICE", "FUSION")})))";

var connectionString =
    $"User Id={Env("OFPG_USER", "fusion")};" +
    $"Password={Env("ORACLE_WIRE_PASSWORD", "")};" +
    $"Data Source={descriptor};";

await using var connection = new OracleConnection(connectionString);
await connection.OpenAsync();

await using var command = new OracleCommand(Sql, connection);
// The first statement of a session waits on a BI Publisher call, which takes
// seconds. Without this a stalled backend looks like a hung program.
command.CommandTimeout = 120;

await using var reader = await command.ExecuteReaderAsync();

Console.WriteLine($"{"INVOICE_NUM",-24} {"INVOICE_DATE",-12} {"INVOICE_AMOUNT",16}");
Console.WriteLine(new string('-', 54));

var rows = 0;
while (await reader.ReadAsync())
{
    rows++;
    // Every column can be NULL in real tenant data, and a demo that throws on
    // one demonstrates nothing.
    var number = reader.IsDBNull(0) ? "" : reader.GetString(0);
    // Truncated to its column, unlike the amount below. INVOICE_NUM is
    // VARCHAR2(50) and a long one would push every column right, which reads
    // as a bug in the proxy rather than a long invoice number. A truncated
    // AMOUNT would be worse than a ragged table: a wrong figure on screen.
    if (number.Length > 24) number = number[..24];
    var date = reader.IsDBNull(1) ? "" : reader.GetDateTime(1).ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
    var amount = reader.IsDBNull(2) ? "" : reader.GetDecimal(2).ToString("F2", CultureInfo.InvariantCulture);

    Console.WriteLine($"{number,-24} {date,-12} {amount,16}");
}

Console.WriteLine($"\n{rows} row(s)");
