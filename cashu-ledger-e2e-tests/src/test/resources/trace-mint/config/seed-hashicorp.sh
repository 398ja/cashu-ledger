#!/bin/sh
# Seeds the test keyset's private keys into HashiCorp Vault for the trace-capture E2E.
#
# Paths and payload match what the mint's HCKeyVault expects:
#   - relative path: keys/{mintId}/{keySetId}/{amount}  (under the kv-v2 mount "cashu")
#   - payload field: private_key
# The vault DB rows seeded by seed-vault.sql carry the matching vault_path
# "cashu/keys/{mintId}/{keySetId}/{amount}", so the mint reads metadata from the vault service
# and the secret from here. Values mirror config/preload-test-data.json.
set -e

MINT_ID="1f240ace-0e4e-42dd-bdcb-9ad4ce8eaeae"
KEYSET_ID="00e3372e61d05605"
MOUNT="cashu"

# Enable the kv-v2 secrets engine (idempotent across reruns of a cached volume).
vault secrets enable -path="${MOUNT}" kv-v2 || true

put() {
  amount="$1"
  private_key="$2"
  vault kv put "${MOUNT}/keys/${MINT_ID}/${KEYSET_ID}/${amount}" private_key="${private_key}"
}

put 1    4fbf609f4d521cf57b93bba3c530d7be1a1e5da7186c2d33990f0a4ead82ccf4
put 2    34087457fc5668d84c4adb282b6d6504aee920444a76ad802a496f0470b63bd4
put 4    96764f964f8e8f606a104fb107f4bb1566004babb1b1902379a97de8d5f726cc
put 8    0810f116d6b98fac26fd4529464d234457804e110a398dd69845ca6b68898142
put 16   1c5b94ffb5f3f434ef4f7820348b8b2e9f21db8c362daa5b094c8975f9a611b2
put 32   c7ed093f604d897091b3b6e34f3005e27e8e8b7e984757153426c1c244e74324
put 64   7f92c62917f281775e2b27d7121cbf135c0583e33e6594d8798325919909bca6
put 128  f242e9ec2a6854a626cdfbeefe3ceddaf31f498376f90284188645b8c44a8dd6
put 256  111122223333444455556666777788889999aaaabbbbccccddddeeeeffff0000
put 512  0000fffefdfcfbfaf9f8f7f6f5f4f3f2f1f0efeeedecebeae9e8e7e6e5e4e3e2
put 1024 abcdef0123456789fedcba9876543210abcdef0123456789fedcba9876543210

echo "HashiCorp Vault seeded with keyset ${KEYSET_ID} (11 keys)"
