// Read ten invoices from Oracle Fusion through ofpgproxy.
//
// The Oracle JDBC thin driver, unchanged, pointed at the proxy instead of a
// database. Java 21+ runs a single source file directly:
//
//     ORACLE_WIRE_PASSWORD=... java -cp ojdbc11.jar Invoices.java
//
// The jar ships with SQLcl, SQL Developer and DBeaver, or comes from Maven
// Central as com.oracle.database.jdbc:ojdbc11.

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;

public class Invoices {

    private static final String SQL = """
            SELECT invoice_num, invoice_date, invoice_amount
            FROM ap_invoices_all
            WHERE ROWNUM <= 10
            """;

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    public static void main(String[] args) throws Exception {
        String url = "jdbc:oracle:thin:@//%s:%s/%s".formatted(
                env("OFPG_HOST", "127.0.0.1"),
                env("OFPG_PORT", "1521"),
                env("OFPG_SERVICE", "FUSION"));

        var formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        try (Connection connection = DriverManager.getConnection(
                url, env("OFPG_USER", "fusion"), env("ORACLE_WIRE_PASSWORD", ""));
             PreparedStatement statement = connection.prepareStatement(SQL)) {

            // The first statement of a session waits on a BI Publisher call,
            // which takes seconds. Without this a stalled backend looks like a
            // hung program.
            statement.setQueryTimeout(120);

            try (ResultSet rows = statement.executeQuery()) {
                System.out.printf("%-24s %-12s %16s%n",
                        "INVOICE_NUM", "INVOICE_DATE", "INVOICE_AMOUNT");
                System.out.println("-".repeat(54));

                int count = 0;
                while (rows.next()) {
                    count++;
                    String number = rows.getString(1);
                    Timestamp date = rows.getTimestamp(2);
                    java.math.BigDecimal amount = rows.getBigDecimal(3);

                    // Every column can be NULL in real tenant data, and a demo
                    // that throws on one demonstrates nothing.
                    // "%-24.24s" pads AND truncates; the amount deliberately
                    // uses plain "%16s" instead. INVOICE_NUM is VARCHAR2(50)
                    // and a long one would push every column right, which
                    // reads as a bug in the proxy. A truncated AMOUNT would be
                    // worse than a ragged table: a wrong figure on screen.
                    System.out.printf("%-24.24s %-12s %16s%n",
                            number == null ? "" : number,
                            date == null ? "" : date.toLocalDateTime().format(formatter),
                            // Locale.ROOT so the decimal separator is a dot
                            // wherever this runs — the other three demos print
                            // locale-independently and the four outputs are
                            // meant to be comparable.
                            amount == null ? "" : String.format(java.util.Locale.ROOT, "%.2f", amount));
                }
                System.out.printf("%n%d row(s)%n", count);
            }
        }
    }
}
