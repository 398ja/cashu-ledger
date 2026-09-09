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

/**
 * The stored envelope carries no plaintext key or password, only ciphertext + non-secret params.
 *
 * The password here is long and distinctive on purpose. This test used to encrypt under 'pw' and
 * assert the serialized envelope did not contain that string, which fails about 2.4% of the time:
 * "pw" is two base64url characters, the envelope carries roughly a hundred positions of random
 * base64, and 1-(1-1/4096)^100 is not small. A test that reddens CI one run in forty, on a random
 * repository, teaches people to re-run rather than to read.
 *
 * A password long enough not to occur by chance tests the same property without the coin flip.
 */
test('the envelope contains no plaintext secret material', async () => {
    // Arrange
    const secretKey = randomSecretKey();
    const password = 'correct-horse-battery-staple-9f3a2b7c';

    // Act
    const envelope = await core.encryptSecretKey(secretKey, password);
    const serialized = JSON.stringify(envelope);

    // Then
    assert.equal(core.isWellFormedEnvelope(envelope), true);
    assert.ok(!serialized.includes(password), 'the password must not be recoverable');
    assert.ok(!serialized.includes(NostrTools.nip19.nsecEncode(secretKey)),
        'the nsec must not be recoverable');
    // The raw key bytes must not appear in any of the encodings the envelope uses either.
    const hex = Array.from(secretKey, b => b.toString(16).padStart(2, '0')).join('');
    assert.ok(!serialized.includes(hex), 'the key must not appear as hex');
    assert.ok(!serialized.includes(Buffer.from(secretKey).toString('base64')),
        'the key must not appear as base64');
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

/** Decrypting with the wrong password fails (AES-GCM authentication), never yielding a key. */
test('decryption with the wrong password is rejected', async () => {
    // Arrange
    const envelope = await core.encryptSecretKey(randomSecretKey(), 'right-password');

    // Act / Then
    await assert.rejects(core.decryptEnvelope(envelope, 'wrong-password'));
});

/** A tampered ciphertext fails decryption even with the correct password. */
test('a tampered envelope is rejected', async () => {
    // Arrange: flip the last two ciphertext chars, keeping a valid-looking envelope
    const envelope = await core.encryptSecretKey(randomSecretKey(), 'pw');
    const tampered = Object.assign({}, envelope, {
        ciphertext: envelope.ciphertext.slice(0, -2) + (envelope.ciphertext.endsWith('AA') ? 'BB' : 'AA')
    });

    // Act / Then
    await assert.rejects(core.decryptEnvelope(tampered, 'pw'));
});
