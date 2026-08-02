# Observability

The Oracle-wire frontend can expose an ops HTTP server with Prometheus metrics
and Kubernetes-style health probes. It's **off by default** — enable it with
`--metrics-listen` / `OFPG_METRICS_LISTEN` (which requires `--oracle-listen`):

```bash
./oratofusionproxy \
  --oracle-listen 127.0.0.1:1521 --oracle-password changeme \
  --metrics-listen 127.0.0.1:9101 \
 
```

The server has **no authentication** — bind it to loopback, or scrape it
through a reverse proxy / sidecar that adds auth. It shuts down with the rest
of the process on `SIGTERM`/`SIGINT`.

Three endpoints:

| Path | Purpose |
|---|---|
| `/metrics` | Prometheus text exposition. |
| `/healthz` | Liveness — `200` whenever the process is up. |
| `/readyz` | Readiness — `200` once a backend is configured, else `503`. |

## Metrics

Prometheus text format, no external client library. Currently scoped to the
Oracle-wire frontend.

| Metric | Type | Meaning |
|---|---|---|
| `orawire_connections_accepted_total` | counter | Oracle-wire connections accepted since start. |
| `orawire_connections_active` | gauge | Connections currently open. |
| `orawire_queries_total` | counter | Foreign SELECT executions attempted. |
| `orawire_query_errors_total` | counter | Executions that returned an error (e.g. ORA-00942). |
| `orawire_interrupts_total` | counter | Calls aborted by a client break/interrupt (Ctrl-C, call timeout). |
| `orawire_soap_duration_seconds` | histogram | BI Publisher call latency. Buckets 0.05 s → 30 s (the backend is slow); `_sum` and `_count` included. |
| `orawire_protocol_timeouts_total` | counter | Protocol watchdog timeouts — a strict request/response transition (handshake, dblink round1→sync3→round2→fetch) the client never completed. See [Configuration → Protocol watchdog & diagnostics](configuration.md#protocol-watchdog--diagnostics). |
| `orawire_diagnostic_bundles_total` | counter | Diagnostic `.zip` bundles written. |
| `orawire_diagnostic_bundle_errors_total` | counter | Diagnostic bundle writes that failed (logged, never fatal). |
| `orawire_metadata_fetches_total` | counter | On-demand metadata fetches attempted — one per table whose columns were missing from the catalog. |
| `orawire_metadata_fetch_errors_total` | counter | On-demand metadata fetches that failed. |
| `orawire_metadata_columns_cached_total` | counter | Column rows written into the local catalog by those fetches. |
| `orawire_metadata_absent_total` | counter | Tables a client asked about that the tenant catalog does not have. A climbing number here usually means a client polling for something that will never exist. |
| `orawire_metadata_bootstraps_total` | counter | Bulk table-list fetches started. |
| `orawire_metadata_bootstrap_running` | gauge | `1` while the bulk table-list fetch is running. This is the one to watch on a fresh cache — see [Metadata](metadata.md#it-fills-itself). |
| `orawire_compatibility_warnings_total` | counter | Connections whose resolved Oracle-wire compatibility profile is not fully verified (experimental/unsupported/unknown) — run `oratofusionproxy doctor --profiles` for the full breakdown. No driver-name/conn-ID labels; check connection logs or a diagnostic bundle's `compat_profile`/`compat_support` fields for which client. |

Example scrape:

```
# HELP orawire_soap_duration_seconds SOAP (BI Publisher) call latency in seconds.
# TYPE orawire_soap_duration_seconds histogram
orawire_soap_duration_seconds_bucket{le="2.5"} 1
orawire_soap_duration_seconds_bucket{le="+Inf"} 1
orawire_soap_duration_seconds_sum 1.855
orawire_soap_duration_seconds_count 1
orawire_queries_total 3
orawire_query_errors_total 1
```

Useful queries:

- Backend latency p95: `histogram_quantile(0.95, rate(orawire_soap_duration_seconds_bucket[5m]))`
- Error rate: `rate(orawire_query_errors_total[5m]) / rate(orawire_queries_total[5m])`
- Live connections: `orawire_connections_active`

A Prometheus scrape config stanza:

```yaml
scrape_configs:
  - job_name: oratofusionproxy
    static_configs:
      - targets: ['127.0.0.1:9101']
```

## Health & readiness

Both return a small JSON body reporting backend and catalog state; probes key
off the **status code**.

- **`/healthz`** — always `200` while the process serves. Wire it to a
  liveness probe / process supervisor: a hang or crash stops answering.
- **`/readyz`** — `200` only once a Fusion backend is configured; `503`
  otherwise (an unconfigured proxy can't answer real queries). Wire it to a
  readiness probe / load-balancer health check so traffic is held off until
  the proxy can serve.

```bash
$ curl -s -w ' [%{http_code}]\n' http://127.0.0.1:9101/readyz
{"status":"ready","backend":"configured","catalog":"loaded"} [200]

$ curl -s -w ' [%{http_code}]\n' http://127.0.0.1:9101/healthz
{"status":"ok","backend":"configured","catalog":"loaded"} [200]
```

### Kubernetes probes

```yaml
livenessProbe:
  httpGet: { path: /healthz, port: 9101 }
  initialDelaySeconds: 5
  periodSeconds: 10
readinessProbe:
  httpGet: { path: /readyz, port: 9101 }
  periodSeconds: 10
```
