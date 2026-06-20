# Operate the transaction-traceability ledger

This guide shows operators how to run the trace ledger: turn on ingest, authorise
producers and readers, watch its health, and prune old events. It assumes you already run
`cashu-ledger-web`.

## Turn on ingest

The serving (read) path is always on; the ingest path is opt-in so a read-only replica opens
no relay connections. Enable it and list the relays and authorised producers:

```yaml
trace:
  ingest:
    enabled: true
    allow-historical: false        # accept events older than the retention window
    producers:
      - mint-url: https://mint.imani.casa
        pubkeys:
          - <producer-x-only-pubkey-hex>
ledger:
  web:
    relays:
      - wss://relay.imani.casa      # private, authenticated relays only
```

On startup the sync engine subscribes for kind-9079 trace events and the voucher watcher for
kind-30078 state; each newly stored event updates the activity cache and the live `/stream`.

## Authorise readers and admins

Read access is NIP-98 gated and shaped by authority (design §7.3). Grant per pubkey:

```yaml
trace:
  security:
    redaction-master-key-hex: <32-byte-hex>   # encrypts registered redaction keys at rest
    authorities:
      - pubkey: <auditor-pubkey>
        grants: [ "trace:read:summary" ]      # never sees secrets
      - pubkey: <investigator-pubkey>
        grants: [ "trace:read:full" ]         # sees secret-bearing fields
      - pubkey: <operator-pubkey>
        grants: [ "trace:admin" ]             # admin endpoints
  issuer:
    pre-voucher-exposure: summary-withhold    # or `suppress`
```

## Watch health

| Surface | What it tells you |
|---------|-------------------|
| `GET /api/v1/trace/stats` | index availability, indexed event count, ingest counters |
| `GET /api/v1/trace/admin/index-status` | rebuild state and lag (admin) |
| `GET /api/v1/trace/admin/access-log` | who read what, and whether secrets were revealed |
| `GET /actuator/prometheus` | `cashu_trace_*` gauges and walk/visualisation timers |

Import [`grafana-trace-dashboard.json`](../reference/grafana-trace-dashboard.json) into Grafana
for index size, ingest rates, prune counts, and walk latency percentiles.

## Prune old events

Pruning removes a raw signed event's payload but keeps a tombstone and the proof join keys so
walks still traverse the hop (design §5.11). Drive `RetentionEngine.runOnce()` on a schedule:
it prunes terminal sub-DAGs ahead of the age threshold, then prunes by age, pausing sub-DAG
pruning when the activity cache is stale. Pruning is irreversible — archive raw events
off-system first if you need recoverability.

## Manage redaction keys (admin)

```bash
# register, list, verify (NIP-98 admin auth required)
POST /api/v1/trace/admin/redaction-keys        {"keyId":"prod-2026","label":"prod","keyHex":"<hex>"}
GET  /api/v1/trace/admin/redaction-keys
POST /api/v1/trace/admin/redaction-keys/prod-2026/verify   {"keyHex":"<hex>"}
```

Keys are stored only as AES-GCM ciphertext; listings show a fingerprint, never the key.

## Related

- [Trace API and CLI reference](../reference/trace-api.md)
- [Chain of custody](../explanation/chain-of-custody.md)
