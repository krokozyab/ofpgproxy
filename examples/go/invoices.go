// Read ten invoices from Oracle Fusion through ofpgproxy.
//
// sijms/go-ora is a pure-Go Oracle driver: no cgo, no Instant Client, so this
// builds and runs anywhere Go does. Point it at the proxy instead of a
// database and nothing else changes.
//
//	ORACLE_WIRE_PASSWORD=... go run invoices.go
package main

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"os"
	"time"

	_ "github.com/sijms/go-ora/v2"
)

const query = `
	SELECT invoice_num, invoice_date, invoice_amount
	FROM ap_invoices_all
	WHERE ROWNUM <= 10`

func env(name, fallback string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return fallback
}

func main() {
	dsn := fmt.Sprintf("oracle://%s:%s@%s:%s/%s",
		env("OFPG_USER", "fusion"),
		env("ORACLE_WIRE_PASSWORD", ""),
		env("OFPG_HOST", "127.0.0.1"),
		env("OFPG_PORT", "1521"),
		env("OFPG_SERVICE", "FUSION"))

	db, err := sql.Open("oracle", dsn)
	if err != nil {
		log.Fatalf("open: %v", err)
	}
	defer db.Close()

	// The first statement of a session waits on a BI Publisher call, which
	// takes seconds. Without a deadline a stalled backend looks like a hung
	// program.
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	rows, err := db.QueryContext(ctx, query)
	if err != nil {
		log.Fatalf("query: %v", err)
	}
	defer rows.Close()

	fmt.Printf("%-24s %-12s %16s\n", "INVOICE_NUM", "INVOICE_DATE", "INVOICE_AMOUNT")
	fmt.Println("------------------------------------------------------")

	count := 0
	for rows.Next() {
		// Every column can be NULL in real tenant data, and a demo that fails
		// to scan one demonstrates nothing — hence the nullable types.
		var (
			number sql.NullString
			date   sql.NullTime
			amount sql.NullFloat64
		)
		if err := rows.Scan(&number, &date, &amount); err != nil {
			log.Fatalf("scan: %v", err)
		}
		count++

		shownDate := ""
		if date.Valid {
			shownDate = date.Time.Format("2006-01-02")
		}
		shownAmount := ""
		if amount.Valid {
			shownAmount = fmt.Sprintf("%.2f", amount.Float64)
		}
		// "%-24.24s" pads AND truncates; the amount deliberately uses plain
		// "%16s". INVOICE_NUM is VARCHAR2(50) and a long one would push every
		// column right, which reads as a bug in the proxy. A truncated AMOUNT
		// would be worse than a ragged table: a wrong figure on screen.
		fmt.Printf("%-24.24s %-12s %16s\n", number.String, shownDate, shownAmount)
	}
	if err := rows.Err(); err != nil {
		log.Fatalf("rows: %v", err)
	}
	fmt.Printf("\n%d row(s)\n", count)
}
