# Vendored frontend libraries

Third-party browser bundles served as static assets. Kept pristine (no local
edits) so they can be audited against upstream.

## nostr-tools.min.js

- **Package**: [`nostr-tools`](https://github.com/nbd-wtf/nostr-tools)
- **Version**: 2.17.2 (pinned)
- **Source file**: `nostr-tools/lib/nostr.bundle.js` (esbuild IIFE browser bundle)
- **Global exposed**: `window.NostrTools`
- **Used for**: NIP-19 `nsec` decoding (`nip19.decode`), public-key derivation
  (`getPublicKey`), and NIP-01/BIP-340 event finalization (`finalizeEvent`) — the
  client-side NIP-98 signing that replaces the NIP-07 browser extension.
- **Crypto provenance**: built on the audited `@noble/secp256k1`,
  `@noble/hashes`, and `@scure/base` libraries (no hand-rolled cryptography).

### Refreshing

Replace the file with the `lib/nostr.bundle.js` from the desired pinned
`nostr-tools` release and update the version above. Do not edit the bundle in
place.
