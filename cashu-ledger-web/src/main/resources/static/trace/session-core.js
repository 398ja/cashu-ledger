/*
 * Pure, DOM-free primitives for nsec login (feature 049-nsec-login):
 * password-based encryption of the nsec (WebCrypto PBKDF2 + AES-GCM), NIP-19
 * nsec decoding, public-key derivation, and NIP-98 (kind 27235) header signing.
 *
 * Kept free of `document`/`window`/`localStorage` so it can be unit-tested under
 * Node's `node --test` runner (WebCrypto is `globalThis.crypto` in Node 20). The
 * signing/decoding primitives come from the vetted `nostr-tools` bundle, injected
 * as `nostrTools` — no hand-rolled cryptography.
 *
 * Exposed via UMD: `window.cashuSessionCore` in the browser (built from
 * `window.NostrTools` + `window.crypto.subtle`), or `{ createCore }` under
 * CommonJS so tests can inject their own NostrTools + SubtleCrypto.
 */
(function (root) {
    'use strict';

    const ENVELOPE_VERSION = 1;
    const KDF = 'PBKDF2-SHA256';
    const PBKDF2_ITERATIONS = 600000;
    const SALT_BYTES = 16;
    const IV_BYTES = 12;
    const AES_KEY_BITS = 256;
    const SECRET_KEY_BYTES = 32;

    function createCore(nostrTools, subtle) {
        if (!nostrTools || !nostrTools.nip19) {
            throw new Error('nsec login requires the nostr-tools bundle');
        }
        if (!subtle) {
            throw new Error('nsec login requires WebCrypto (crypto.subtle)');
        }

        const encoder = new TextEncoder();

        function bytesToBase64Url(bytes) {
            let binary = '';
            for (let i = 0; i < bytes.length; i++) {
                binary += String.fromCharCode(bytes[i]);
            }
            return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
        }

        function base64UrlToBytes(text) {
            const base64 = text.replace(/-/g, '+').replace(/_/g, '/');
            const binary = atob(base64);
            const bytes = new Uint8Array(binary.length);
            for (let i = 0; i < binary.length; i++) {
                bytes[i] = binary.charCodeAt(i);
            }
            return bytes;
        }

        // Validates an nsec without throwing; true only for a well-formed 32-byte key.
        function isValidNsec(nsec) {
            try {
                const decoded = nostrTools.nip19.decode(nsec);
                return decoded.type === 'nsec'
                    && decoded.data instanceof Uint8Array
                    && decoded.data.length === SECRET_KEY_BYTES;
            } catch (e) {
                return false;
            }
        }

        // Decodes an nsec to its 32-byte secret key; throws when the input is not a
        // well-formed nsec so callers reject malformed input at the boundary.
        function decodeNsec(nsec) {
            const decoded = nostrTools.nip19.decode(nsec);
            if (decoded.type !== 'nsec' || decoded.data.length !== SECRET_KEY_BYTES) {
                throw new Error('value is not a valid nsec');
            }
            return decoded.data;
        }

        function derivePublicKey(secretKey) {
            return nostrTools.getPublicKey(secretKey);
        }

        async function deriveAesKey(password, salt, iterations) {
            const baseKey = await subtle.importKey(
                'raw', encoder.encode(password), 'PBKDF2', false, ['deriveKey']);
            return subtle.deriveKey(
                { name: 'PBKDF2', salt: salt, iterations: iterations, hash: 'SHA-256' },
                baseKey,
                { name: 'AES-GCM', length: AES_KEY_BITS },
                false,
                ['encrypt', 'decrypt']);
        }

        // Encrypts the 32-byte secret key under the password, returning the storage
        // envelope (see contracts/credential-envelope.md). Salt/iv are random and
        // non-secret; no plaintext key or password is retained.
        async function encryptSecretKey(secretKey, password) {
            const salt = crypto.getRandomValues(new Uint8Array(SALT_BYTES));
            const iv = crypto.getRandomValues(new Uint8Array(IV_BYTES));
            const aesKey = await deriveAesKey(password, salt, PBKDF2_ITERATIONS);
            const ciphertext = new Uint8Array(
                await subtle.encrypt({ name: 'AES-GCM', iv: iv }, aesKey, secretKey));
            return {
                v: ENVELOPE_VERSION,
                kdf: KDF,
                iterations: PBKDF2_ITERATIONS,
                salt: bytesToBase64Url(salt),
                iv: bytesToBase64Url(iv),
                ciphertext: bytesToBase64Url(ciphertext)
            };
        }

        function isWellFormedEnvelope(envelope) {
            return !!envelope
                && envelope.v === ENVELOPE_VERSION
                && envelope.kdf === KDF
                && typeof envelope.iterations === 'number'
                && typeof envelope.salt === 'string'
                && typeof envelope.iv === 'string'
                && typeof envelope.ciphertext === 'string';
        }

        // Decrypts the envelope to the 32-byte secret key. AES-GCM authentication
        // makes a wrong password (or tampered ciphertext) reject with an exception.
        async function decryptEnvelope(envelope, password) {
            if (!isWellFormedEnvelope(envelope)) {
                throw new Error('stored credential is corrupted');
            }
            const salt = base64UrlToBytes(envelope.salt);
            const iv = base64UrlToBytes(envelope.iv);
            const ciphertext = base64UrlToBytes(envelope.ciphertext);
            const aesKey = await deriveAesKey(password, salt, envelope.iterations);
            const plaintext = await subtle.decrypt(
                { name: 'AES-GCM', iv: iv }, aesKey, ciphertext);
            return new Uint8Array(plaintext);
        }

        // Builds the NIP-98 Authorization header value for a request, signing the
        // kind-27235 event with the in-memory secret key (see
        // contracts/nip98-auth-header.md). nowSeconds is injectable for tests.
        function buildAuthorizationHeader(method, url, secretKey, nowSeconds) {
            const createdAt = typeof nowSeconds === 'number'
                ? nowSeconds
                : Math.floor(Date.now() / 1000);
            const template = {
                kind: 27235,
                created_at: createdAt,
                tags: [['u', url], ['method', method]],
                content: ''
            };
            const signed = nostrTools.finalizeEvent(template, secretKey);
            return 'Nostr ' + bytesToBase64Url(encoder.encode(JSON.stringify(signed)));
        }

        return {
            isValidNsec,
            decodeNsec,
            derivePublicKey,
            encryptSecretKey,
            decryptEnvelope,
            isWellFormedEnvelope,
            buildAuthorizationHeader,
            bytesToBase64Url,
            base64UrlToBytes
        };
    }

    if (typeof module === 'object' && module.exports) {
        module.exports = { createCore };
    } else {
        root.cashuSessionCore = createCore(root.NostrTools, root.crypto && root.crypto.subtle);
    }
})(typeof self !== 'undefined' ? self : globalThis);
