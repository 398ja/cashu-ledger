/*
 * nsec login session manager (feature 049-nsec-login). Owns the browser-side
 * authentication that replaces the NIP-07 extension: the user signs in with an
 * nsec + password, the encrypted nsec is persisted in localStorage, and while
 * unlocked the in-memory secret key signs the NIP-98 headers the Graph section's
 * requests require. Pure crypto/signing lives in cashuSessionCore; this module
 * owns state, the auth panel UI, and persistence.
 *
 * Exposes window.cashuSession: getState(), getPubkey(), onStatusChange(cb),
 * authHeader(method, url), login(nsec, password). (logout/unlock/auto-lock are
 * layered on by later slices of the feature.)
 */
(function () {
    'use strict';

    const STORAGE_KEY = 'cashu-ledger-nsec';
    const SIGNED_OUT = 'SIGNED_OUT';
    const UNLOCKED = 'UNLOCKED';

    const core = window.cashuSessionCore;
    const el = (id) => document.getElementById(id);

    let secretKey = null;
    let pubkey = null;
    let state = SIGNED_OUT;
    const statusListeners = [];

    function onStatusChange(callback) {
        statusListeners.push(callback);
    }

    function emitStatus() {
        const snapshot = { state: state, pubkey: pubkey, hasStoredCredential: hasStoredCredential() };
        statusListeners.forEach((cb) => cb(snapshot));
    }

    function getState() {
        return state;
    }

    function getPubkey() {
        return pubkey;
    }

    function hasStoredCredential() {
        return localStorage.getItem(STORAGE_KEY) !== null;
    }

    function readEnvelope() {
        const raw = localStorage.getItem(STORAGE_KEY);
        if (raw === null) {
            return null;
        }
        try {
            return JSON.parse(raw);
        } catch (e) {
            return { corrupted: true };
        }
    }

    function setUnlocked(key) {
        secretKey = key;
        pubkey = core.derivePublicKey(key);
        state = UNLOCKED;
        renderPanel();
        emitStatus();
    }

    function clearKey() {
        if (secretKey) {
            secretKey.fill(0);
        }
        secretKey = null;
        pubkey = null;
        state = SIGNED_OUT;
        renderPanel();
        emitStatus();
    }

    // Produces the NIP-98 Authorization header for a request, or null when the
    // session is locked so the caller blocks the request and prompts to sign in.
    function authHeader(method, url) {
        if (state !== UNLOCKED || !secretKey) {
            return null;
        }
        return core.buildAuthorizationHeader(method, url, secretKey);
    }

    // Signs out: removes the stored envelope and clears the in-memory key, so no
    // recoverable key material remains and the Graph is locked again.
    function logout() {
        localStorage.removeItem(STORAGE_KEY);
        clearKey();
    }

    // Signs in with a fresh nsec + password: validates input, encrypts the nsec
    // under the password, persists the envelope, and unlocks the session.
    async function login(nsec, password) {
        if (!core.isValidNsec(nsec)) {
            throw new Error('That is not a valid nsec. Paste your private key as nsec1…');
        }
        if (!password) {
            throw new Error('Enter a password to encrypt your nsec.');
        }
        const key = core.decodeNsec(nsec);
        const envelope = await core.encryptSecretKey(key, password);
        localStorage.setItem(STORAGE_KEY, JSON.stringify(envelope));
        setUnlocked(key);
    }

    function show(id, visible) {
        const node = el(id);
        if (node) {
            node.style.display = visible ? '' : 'none';
        }
    }

    function setStatusLine() {
        const line = el('trace-auth-status');
        if (!line) {
            return;
        }
        if (state === UNLOCKED) {
            line.textContent = 'Signed in as ' + pubkey.slice(0, 12) + '… '
                + 'Fields shown depend on your operator access level.';
            line.style.background = 'rgba(34,197,94,0.14)';
        } else {
            line.textContent = 'Sign in with your nsec to view the transaction graph.';
            line.style.background = 'rgba(234,179,8,0.12)';
        }
    }

    // Toggles the auth panel between the signed-out (login form) and signed-in
    // (logout) views and refreshes the status line.
    function renderPanel() {
        setStatusLine();
        show('trace-login-form', state !== UNLOCKED);
        show('trace-session-active', state === UNLOCKED);
    }

    function setLoginError(message) {
        const node = el('trace-login-error');
        if (node) {
            node.textContent = message || '';
            node.style.display = message ? '' : 'none';
        }
    }

    async function handleLogin() {
        setLoginError('');
        const nsecField = el('trace-login-nsec');
        const passwordField = el('trace-login-password');
        const button = el('trace-login-btn');
        if (button) {
            button.disabled = true;
        }
        try {
            await login(nsecField.value.trim(), passwordField.value);
            nsecField.value = '';
            passwordField.value = '';
        } catch (e) {
            setLoginError(e.message);
        } finally {
            if (button) {
                button.disabled = false;
            }
        }
    }

    function bindControls() {
        const button = el('trace-login-btn');
        if (button) {
            button.addEventListener('click', handleLogin);
        }
        const passwordField = el('trace-login-password');
        if (passwordField) {
            passwordField.addEventListener('keydown', (e) => {
                if (e.key === 'Enter') {
                    handleLogin();
                }
            });
        }
        const logoutButton = el('trace-logout-btn');
        if (logoutButton) {
            logoutButton.addEventListener('click', logout);
        }
    }

    // Keeps tabs consistent: when this credential is removed in another tab
    // (logout elsewhere), lock this tab too so it stops using the key.
    function bindCrossTabSync() {
        window.addEventListener('storage', (e) => {
            if (e.key === STORAGE_KEY && e.newValue === null && state === UNLOCKED) {
                clearKey();
            }
        });
    }

    function init() {
        if (!el('trace-auth-status')) {
            return;
        }
        bindControls();
        bindCrossTabSync();
        renderPanel();
        emitStatus();
    }

    window.cashuSession = {
        getState: getState,
        getPubkey: getPubkey,
        onStatusChange: onStatusChange,
        authHeader: authHeader,
        login: login,
        logout: logout
    };

    document.addEventListener('DOMContentLoaded', init);
})();
