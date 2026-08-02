"""Read ten invoices from Oracle Fusion through ofpgproxy.

python-oracledb in thin mode: no Oracle Instant Client, no wallet, nothing to
install beyond the driver itself. The only thing that differs from talking to a
real Oracle database is the host and port.

    pip install oracledb
    ORACLE_WIRE_PASSWORD=... python invoices.py
"""

import os
import oracledb

HOST = os.environ.get("OFPG_HOST", "127.0.0.1")
PORT = int(os.environ.get("OFPG_PORT", "1521"))
SERVICE = os.environ.get("OFPG_SERVICE", "FUSION")
USER = os.environ.get("OFPG_USER", "fusion")
PASSWORD = os.environ.get("ORACLE_WIRE_PASSWORD", "")

SQL = """
    SELECT invoice_num, invoice_date, invoice_amount
    FROM ap_invoices_all
    WHERE ROWNUM <= 10
"""


def main() -> None:
    with oracledb.connect(
        user=USER, password=PASSWORD, dsn=f"{HOST}:{PORT}/{SERVICE}"
    ) as connection:
        # The first statement of a session waits on a BI Publisher call, which
        # takes seconds. Without a timeout a stalled backend looks like a hung
        # program.
        connection.call_timeout = 120_000
        with connection.cursor() as cursor:
            cursor.execute(SQL)
            print(f"{'INVOICE_NUM':<24} {'INVOICE_DATE':<12} {'INVOICE_AMOUNT':>16}")
            print("-" * 54)
            rows = 0
            for number, date, amount in cursor:
                rows += 1
                # Every column can be NULL in real tenant data, and a demo that
                # crashes on one is not a demo of anything.
                shown_date = date.strftime("%Y-%m-%d") if date else ""
                shown_amount = f"{amount:,.2f}" if amount is not None else ""
                # The number is truncated to its column; the amount is NOT.
                # INVOICE_NUM is VARCHAR2(50) and a long one would push every
                # column right, which reads as a bug in the proxy rather than
                # a long invoice number. A truncated AMOUNT would be worse
                # than a ragged table: it is a wrong figure on screen.
                print(f"{(number or '')[:24]:<24} {shown_date:<12} {shown_amount:>16}")
            print(f"\n{rows} row(s)")


if __name__ == "__main__":
    main()
