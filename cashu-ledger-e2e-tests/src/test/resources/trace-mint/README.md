# Trace capture E2E mint stack (T039)

A minimal, real cashu-mint stack used by `TraceCaptureE2ETest`, distilled from the live staging
`docker-compose.staging.yml`. It lets the E2E perform **real** mint / swap / melt operations
against a real mint (real keysets, real BDHKE blind signatures) and feed the resulting trace
events through the ledger's ingest pipeline.

## Services

| Service | Image | Role |
|---------|-------|------|
| `cashu-vault-db` | `postgres:16` | keyset/vault store |
| `cashu-vault-jpa` | `cashu-vault-jpa:0.6.0` | keyset service (port 3333), seeded by `vault-seed` (pinned pre-V3, see below) |
| `vault-seed` | `postgres:16` | applies `config/seed-vault.sql` |
| `payment-adapter-db` | `postgres:16` | gateway/quote store (`payment_gateway`) |
| `payment-adapter-rest` | `payment-adapter-rest:latest` | gateway: persists mint/melt quotes (port 8080) |
| `phoenixd-mock` | `phoenixd-mock:latest` | fake Lightning, **auto-settles** invoices (~2 s) |
| `cashu-mint-rest` | `cashu-mint-rest:latest` | the mint (port 7777), preloaded keyset `00e3372e61d05605` |
| `nostr-relay` | `dockurr/strfry:latest` | relay the producer publishes to and the ledger consumes |

Images come from `docker.398ja.xyz` (the internal registry). `MINT_WEBHOOK_SECRET` is set because
recent mint builds require it outside the `local` profile.

`cashu-vault-jpa` is pinned to `0.6.0` — the last tag before Flyway migration
`V3__add_vault_path_to_key` moved private keys to HashiCorp Vault. On `:0.7.0`/`:latest` a fresh DB
cannot store the inline keys the seed/preload provide, so the mint cannot sign. See
[../../../../../docs/bugs/2026-06-20-cashu-vault-jpa-vault-path-blocks-minting.md](../../../../../docs/bugs/2026-06-20-cashu-vault-jpa-vault-path-blocks-minting.md).
The `nostr-relay` command rewrites the strfry default config to disable the write-policy whitelist so
the test producer key may publish.

## Verified

`docker compose up -d --wait` brings the stack healthy; a mint quote auto-settles to `paid:true`
within a few seconds, so real proofs can be minted. The E2E starts this compose via Testcontainers
and skips cleanly if Docker or the images are unavailable.
