# Bug: `cashu-vault-jpa:latest` cannot store keyset keys on a fresh deployment — mint cannot sign

**Status:** Open — awaiting review
**Reported:** 2026-06-20
**Reporter:** Trace-traceability E2E work (T039)
**Severity:** High — a freshly provisioned mint stack cannot mint, swap, or melt any token.
**Components:** `cashu-vault-jpa` (primary), `cashu-mint-rest` `PreloadMintLoadService` (secondary), deployment seed `config/seed-vault.sql` (secondary).

> Filed for review per request: no `cashu-mint` / `cashu-vault` code was changed. This documents the
> blocker found while building the T039 real-mint trace-capture E2E.

## Summary

On a **fresh** database, `cashu-vault-jpa:latest` cannot persist keyset private keys. Every key
insert violates a `NOT NULL` constraint on the new `t_key.vault_path` column, because key material is
now expected to live in **HashiCorp Vault** (introduced by Flyway migration
`V3__add_vault_path_to_key.sql`) and no HashiCorp Vault backend is present or configured.

Consequences, in order:

1. The deployment seed `config/seed-vault.sql` fails (`column "private_key" ... does not exist`),
   leaving `t_key` empty.
2. The mint's `PreloadMintLoadService` then tries to seed keys over the vault REST API; the
   `POST /vault/key` call returns **HTTP 500** and the preload is abandoned with only a `WARN`, so the
   mint still reports healthy.
3. With no private keys available, `POST /v1/mint/bolt11` (and swap/melt) fail when resolving the
   signing key. The mint surfaces a generic `{"code":"internal_error","message":"Internal server error"}`.

The defect is masked on the existing **staging** host because its vault database predates migration V3:
under `spring.jpa.hibernate.ddl-auto=update` the legacy `private_key` column was never dropped, so the
inline-key seed continues to work there. Any clean environment (CI, a new laptop, a rebuilt staging
volume) is broken.

## Environment

| Component | Image |
|-----------|-------|
| Vault | `docker.398ja.xyz/cashu-vault-jpa:latest` |
| Mint | `docker.398ja.xyz/cashu-mint-rest:latest` |
| Payment adapter | `docker.398ja.xyz/payment-adapter-rest:latest` |
| Lightning mock | `docker.398ja.xyz/phoenixd-mock:latest` |
| Vault DB | `postgres:16` (fresh volume) |

Flyway migrations applied to the fresh vault DB (from the vault container at boot):

```
v1  - init schema
v2  - add proof unique constraint
v3  - add vault path to key        <-- introduces the regression
v4  - add proof melt saga id
v999- add nut13 derivation metadata
```

## Root cause

`V3__add_vault_path_to_key.sql` (extracted from the image at
`/app/resources/db/migration/V3__add_vault_path_to_key.sql`):

```sql
-- Replace private_key column with vault_path reference to HashiCorp Vault.
-- Private keys are now stored exclusively in HashiCorp Vault.

ALTER TABLE t_key ADD COLUMN vault_path VARCHAR(512) NOT NULL;
CREATE INDEX idx_key_vault_path ON t_key (vault_path);

DROP INDEX IF EXISTS idx_key_private_key_unq;
ALTER TABLE t_key DROP COLUMN private_key;

ALTER TABLE t_key_a ADD COLUMN vault_path VARCHAR(512);
ALTER TABLE t_key_a DROP COLUMN private_key;
```

Resulting schema on a fresh DB (no `private_key` column; `vault_path` is `NOT NULL`):

```
                           Table "public.t_key"
   Column   |   Type                 | Nullable
------------+------------------------+----------
 id         | uuid                   | not null
 amount     | numeric(38,0)          | not null
 key_set_id | uuid                   |
 vault_path | character varying(512) | not null   <-- requires HashiCorp Vault reference
```

So private keys are expected to be written to HashiCorp Vault, with only the *path* stored in
Postgres. Neither the minimal stack nor (apparently) the documented deployment provides or configures
that HashiCorp Vault backend, and the JPA persistence path inserts `vault_path = NULL`.

## Evidence

**1. Vault rejects the key insert (`vault-jpa` log):**

```
insert into t_key (amount,archived,created_at,key_set_id,updated_at,vault_path,version,id) values (?,?,?,?,?,?,?,?)
ERROR: null value in column "vault_path" of relation "t_key" violates not-null constraint
  Detail: Failing row contains (a0f1e55f-..., f, ..., a086d577-..., null).
org.springframework.dao.DataIntegrityViolationException ...
```

**2. The SQL seed fails and leaves `t_key` empty (`vault-seed` log):**

```
psql:/seed-vault.sql:67: ERROR:  column "private_key" of relation "t_key" does not exist
Vault seeded            <-- misleading: the INSERT block was skipped
```

```
cashu_vault=# SELECT count(*) FROM t_key;
 count
-------
     0
```

(`t_keyset` does contain the `sat` keyset `00e3372e61d05605`, so `GET /v1/keysets` and
`GET /v1/keys/{id}` still return public keys — making the mint look provisioned when it is not.)

**3. The mint's preload swallows the failure (`cashu-mint-rest` log):**

```
VaultClient - POST http://cashu-vault-jpa:3333/vault/keyset/      (200)
VaultClient - POST http://cashu-vault-jpa:3333/vault/key/         (500)
WARN  PreloadMintLoadService - PreloadMintLoadService: failed to seed vault from preload JSON
org.springframework.web.client.HttpServerErrorException$InternalServerError: 500
    on POST "http://cashu-vault-jpa:3333/vault/key": "An internal error occurred. Please contact support."
    at PreloadMintLoadService.seedVaultIfNeeded(PreloadMintLoadService.java:197)
```

The mint then reports healthy despite having no signing keys.

**4. Minting fails at signing time:**

```
$ curl -X POST .../v1/mint/quote/bolt11 -d '{"amount":16,"unit":"sat"}'   # ok, settles to paid:true
$ curl -X POST .../v1/mint/bolt11 -d '{"quote":"<id>","outputs":[{"amount":16,"id":"00e3372e61d05605","B_":"<valid point>"}]}'
{"code":"internal_error","message":"Internal server error"}
```

The mint log shows it gets as far as `SignBlindedMessageTask -> MintProtocolUtil.Resolve private key`
and then fails resolving the key from the vault. A valid curve point (the secp256k1 generator) was used
for `B_`, ruling out client-side blinding as the cause.

## Reproduction

1. Start a fresh stack from `cashu-vault-jpa:latest` + `cashu-mint-rest:latest` with a clean
   `cashu-vault-db` volume and the inline-key `seed-vault.sql` / `preload-test-data.json`.
2. `POST /v1/mint/quote/bolt11 {"amount":16,"unit":"sat"}`, wait for `paid:true`.
3. `POST /v1/mint/bolt11` with any valid output → `internal_error`.
4. Inspect: `SELECT count(*) FROM t_key;` → `0`; vault log shows the `vault_path` NOT NULL violation.

The minimal stack used to reproduce lives at
`cashu-ledger-e2e-tests/src/test/resources/trace-mint/` (distilled from the staging compose).

## Why staging masks this

Staging's `cashu_vault` volume was created before V3. With `ddl-auto=update`, Flyway added `vault_path`
but the pre-existing `private_key` column and data remained, so the inline-key seed/preload still
function. A clean staging redeploy (or CI) would hit the same failure.

## Affected assets that still assume the dropped `private_key` model

- `config/seed-vault.sql` (this repo's E2E copy, and the identical staging `config/seed-vault.sql`):
  `INSERT INTO t_key (..., private_key, ...)`.
- `config/preload-test-data.json`: carries `privateKeyHex` per amount.
- `cashu-mint-rest` `PreloadMintLoadService.seedVaultIfNeeded`: posts inline private keys to the vault.

## Suggested paths forward (for the team to decide — not yet applied)

1. **Provide a non-HashiCorp key-storage mode** in `cashu-vault-jpa` (e.g. a `local`/`dev` profile that
   stores key material in Postgres or a file backend and populates `vault_path` accordingly), so test
   and dev stacks need no external Vault. Preferred for testability.
2. **Document + ship a HashiCorp Vault service** as a required dependency, and update the seed/preload to
   write keys into Vault rather than inline `private_key`.
3. **Make `PreloadMintLoadService` fail fast** instead of `WARN`-and-continue, so a mint with no signing
   keys does not report healthy and silently 500 later.
4. **Run the post-V3 model in the E2E** (chosen — no product code change): keep `:latest` and supply a
   HashiCorp Vault, seeding key secrets into it plus matching `vault_path` DB rows. **Applied** in the
   E2E stack (`cashu-ledger-e2e-tests/src/test/resources/trace-mint/`) — see the "Resolution adopted"
   section below. (For reference, only `cashu-vault-jpa:0.6.0` predates V3; `:0.7.0`/`:latest` include
   `V3__add_vault_path_to_key`.)

## Impact on T039

The T039 real-mint trace-capture E2E is otherwise complete:

- `cashu-ledger-e2e-tests/.../trace/MintWallet.java` — real BDHKE mint+swap over the wallet client.
- `cashu-ledger-e2e-tests/.../trace/TraceCaptureE2ETest.java` — boots the stack, performs real ops,
  signs/publishes kind-9079 events, ingests via `TraceSyncEngine`, asserts capture within 30s.

### Resolution adopted for the E2E

The E2E now runs the **post-V3 model on current `:latest` images** — keys exclusively in HashiCorp
Vault — rather than pinning `0.6.0`. The trace-mint stack adds a `hashicorp-vault` + `vault-init`
that seed each key secret to `cashu/keys/{mintId}/{keySetId}/{amount}`, and `seed-vault.sql` inserts
`t_key` rows carrying the matching `vault_path`; the mint is configured for the HashiCorp backend and
reads secrets from Vault at signing time. The E2E **passes** (real mint+swap captured within ~1 s).

This validates that `cashu-vault-jpa:latest` is correct when a HashiCorp Vault is present. The bug
remains open as a **deployment/seeding gap**: the shipped seed (`seed-vault.sql` with `private_key`)
and the mint's `PreloadMintLoadService` (raw `keyClient().store()` posting a private key with a null
`vault_path`) still assume the dropped column, so a fresh deploy without the HashiCorp seeding above
cannot mint. Fixing that for production means routing the seed/preload through HashiCorp (e.g. the
mint preload using `keyVault().store()` so `HCKeyVault` writes the secret and sets `vault_path`).
