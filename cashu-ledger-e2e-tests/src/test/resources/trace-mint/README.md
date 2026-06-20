# Trace capture E2E mint stack (T039)

A minimal, real cashu-mint stack used by `TraceCaptureE2ETest`, distilled from the live staging
`docker-compose.staging.yml` and the mint's own `docker-compose.dev.yml`. It lets the E2E perform
**real** mint / swap operations against a real mint (real keysets, real BDHKE blind signatures) and
feed the resulting trace events through the ledger's ingest pipeline.

## Services

| Service | Image | Role |
|---------|-------|------|
| `cashu-vault-db` | `postgres:16` | keyset metadata store |
| `hashicorp-vault` | `hashicorp/vault:1.18` | secrets backend holding keyset private keys (dev mode) |
| `vault-init` | `hashicorp/vault:1.18` | enables the kv-v2 `cashu` engine and seeds key secrets (`config/seed-hashicorp.sh`) |
| `cashu-vault-jpa` | `cashu-vault-jpa:latest` | keyset metadata service (port 3333) |
| `vault-seed` | `postgres:16` | inserts mint/keyset/key **metadata** rows (`config/seed-vault.sql`) |
| `payment-adapter-db` | `postgres:16` | gateway/quote store (`payment_gateway`) |
| `payment-adapter-rest` | `payment-adapter-rest:latest` | gateway: persists mint/melt quotes (port 8080) |
| `phoenixd-mock` | `phoenixd-mock:latest` | fake Lightning, **auto-settles** invoices (~2 s) |
| `cashu-mint-rest` | `cashu-mint-rest:latest` | the mint (port 7777), keyset `00e3372e61d05605` |
| `nostr-relay` | `dockurr/strfry:latest` | relay the producer publishes to and the ledger consumes |

Images come from `docker.398ja.xyz` (the internal registry). `MINT_WEBHOOK_SECRET` is set because
recent mint builds require it outside the `local` profile.

## Key storage: HashiCorp Vault (post-V3)

Since vault migration `V3__add_vault_path_to_key`, keyset private keys live **exclusively** in
HashiCorp Vault; the vault DB keeps only a `vault_path` reference. This stack runs that model on the
current `:latest` images (no version pin):

- `vault-init` writes each key secret to `cashu/keys/{mintId}/{keySetId}/{amount}` — the exact path
  the mint's `HCKeyVault` derives.
- `vault-seed` inserts the matching `t_key` rows whose `vault_path` points at those secrets.
- The mint is configured for the HashiCorp backend (`VAULT_HASHI_ENABLED=true`, token auth against
  the dev root token, engine mount `cashu`), so at signing time it reads metadata from the vault
  service and the secret from Vault. Because every key amount is pre-seeded, the mint's preload finds
  them present and skips the raw-key POST (which has no secret backend to write to).

The unfixed `:latest` regression — a fresh deploy whose seed/preload still assume the dropped
`private_key` column — is documented in
[../../../../../docs/bugs/2026-06-20-cashu-vault-jpa-vault-path-blocks-minting.md](../../../../../docs/bugs/2026-06-20-cashu-vault-jpa-vault-path-blocks-minting.md);
this stack works around it at the deployment layer by seeding HashiCorp + the matching `vault_path`
rows itself.

The `nostr-relay` command rewrites the strfry default config to disable the write-policy whitelist so
the test producer key may publish.

## Verified

`docker compose up -d --wait` brings the stack healthy; a mint quote auto-settles to `paid:true`
and `POST /v1/mint/bolt11` returns real signatures (keys read from HashiCorp). The E2E starts this
compose via Testcontainers and skips cleanly if Docker or the images are unavailable.
