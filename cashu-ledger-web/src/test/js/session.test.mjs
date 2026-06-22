/*
 * Unit tests for the nsec-login crypto/signing core (session-core.js). Exercises
 * the security-critical, DOM-free primitives under Node's built-in test runner:
 * password-based encryption round-trips and nsec validation/decoding. The
 * vendored nostr-tools bundle (a classic script defining `var NostrTools`) is
 * loaded into a function scope; WebCrypto is Node 20's global `crypto.subtle`.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { createRequire } from 'node:module';

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const traceDir = join(here, '..', '..', 'main', 'resources', 'static', 'trace');

const bundle = readFileSync(join(traceDir, 'vendor', 'nostr-tools.min.js'), 'utf8');
const NostrTools = new Function(bundle + '; return NostrTools;')();
const { createCore } = require(join(traceDir, 'session-core.js'));
const core = createCore(NostrTools, globalThis.crypto.subtle);

const randomSecretKey = () => NostrTools.generateSecretKey();

/** Encrypting a secret key and decrypting with the same password returns the original 32 bytes. */
test('encrypt then decrypt round-trips the secret key', async () => {
    // Arrange
    const secretKey = randomSecretKey();

    // Act
    const envelope = await core.encryptSecretKey(secretKey, 'correct horse battery');
    const recovered = await core.decryptEnvelope(envelope, 'correct horse battery');

    // Then
    assert.deepEqual(Array.from(recovered), Array.from(secretKey));
});

/** The stored envelope carries no plaintext key or password, only ciphertext + non-secret params. */
test('the envelope contains no plaintext secret material', async () => {
    // Arrange
    const secretKey = randomSecretKey();

    // Act
    const envelope = await core.encryptSecretKey(secretKey, 'pw');
    const serialized = JSON.stringify(envelope);

    // Then
    assert.equal(core.isWellFormedEnvelope(envelope), true);
    assert.ok(!serialized.includes('pw'));
    assert.ok(!serialized.includes(NostrTools.nip19.nsecEncode(secretKey)));
});

/** A valid nsec validates and decodes to a 32-byte secret key. */
test('a valid nsec decodes to a 32-byte key', () => {
    // Arrange
    const nsec = NostrTools.nip19.nsecEncode(randomSecretKey());

    // Act / Then
    assert.equal(core.isValidNsec(nsec), true);
    assert.equal(core.decodeNsec(nsec).length, 32);
});

/** A malformed nsec is rejected: isValidNsec is false and decodeNsec throws. */
test('a malformed nsec is rejected', () => {
    // Act / Then
    assert.equal(core.isValidNsec('not-an-nsec'), false);
    assert.equal(core.isValidNsec(''), false);
    assert.throws(() => core.decodeNsec('not-an-nsec'));
});
