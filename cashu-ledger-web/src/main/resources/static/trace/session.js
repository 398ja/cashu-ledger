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
    const IDLE_LOCK_MS = 15 * 60 * 1000;

    const core = window.cashuSessionCore;
    const el = (id) => document.getElementById(id);

    let secretKey = null;
    let pubkey = null;
    let state = SIGNED_OUT;
    let idleTimer = null;
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
        resetIdleTimer();
        renderPanel();
        emitStatus();
    }

    function clearKey() {
        if (idleTimer) {
            clearTimeout(idleTimer);
            idleTimer = null;
        }
        if (secretKey) {
            secretKey.fill(0);
        }
        secretKey = null;
        pubkey = null;
        state = SIGNED_OUT;
        renderPanel();
        emitStatus();
    }

    // Clears the in-memory key after a period of inactivity while leaving the
    // stored envelope intact, so the user can unlock again with their password.
    function autoLock() {
        if (state === UNLOCKED) {
            clearKey();
        }
    }

    function resetIdleTimer() {
        if (state !== UNLOCKED) {
            return;
        }
        if (idleTimer) {
            clearTimeout(idleTimer);
        }
        idleTimer = setTimeout(autoLock, IDLE_LOCK_MS);
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

    // Unlocks a returning session from the stored envelope using the password
    // only. A wrong password rejects (envelope kept); a corrupted envelope is
    // reported so the caller can offer to clear it and sign in fresh.
    async function unlock(password) {
        const envelope = readEnvelope();
        if (!envelope || envelope.corrupted || !core.isWellFormedEnvelope(envelope)) {
            const error = new Error('Your stored credential is unreadable. Clear it and sign in again.');
            error.corrupted = true;
            throw error;
        }
        if (!password) {
            throw new Error('Enter your password to unlock.');
        }
        let key;
        try {
            key = await core.decryptEnvelope(envelope, password);
        } catch (e) {
            throw new Error('Incorrect password.');
        }
        setUnlocked(key);
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
            line.style.background = 'rgba(15,138,90,0.12)';
            line.style.color = 'var(--green-deep)';
        } else if (hasStoredCredential()) {
            line.textContent = 'Enter your password to unlock the transaction graph.';
            line.style.background = 'rgba(154,106,6,0.12)';
            line.style.color = 'var(--amber)';
        } else {
            line.textContent = 'Sign in with your nsec to view the transaction graph.';
            line.style.background = 'rgba(154,106,6,0.12)';
            line.style.color = 'var(--amber)';
        }
    }

    // Selects the auth-panel view: unlocked → logout; locked with a stored
    // credential → password-only unlock; locked with none → first-time login.
    function renderPanel() {
        setStatusLine();
        const locked = state !== UNLOCKED;
        const stored = hasStoredCredential();
        show('trace-login-form', locked && !stored);
        show('trace-unlock-form', locked && stored);
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
        } catch (e) {
            setLoginError(e.message);
        } finally {
            // Cleared on every path, not just success (audit L-25). A failed login left the
            // nsec sitting in the input, so it stayed in the DOM for anyone who walked up to
            // the screen and survived into a browser crash dump. A wrong password is exactly
            // when the field is most likely to be left alone and forgotten.
            nsecField.value = '';
            passwordField.value = '';
            if (button) {
                button.disabled = false;
            }
        }
    }

    function setUnlockError(message) {
        const node = el('trace-unlock-error');
        if (node) {
            node.textContent = message || '';
            node.style.display = message ? '' : 'none';
        }
    }

    async function handleUnlock() {
        setUnlockError('');
        const passwordField = el('trace-unlock-password');
        const button = el('trace-unlock-btn');
        if (button) {
            button.disabled = true;
        }
        try {
            await unlock(passwordField.value);
        } catch (e) {
            setUnlockError(e.message);
        } finally {
            // See handleLogin: cleared on every path.
            passwordField.value = '';
            if (button) {
                button.disabled = false;
            }
        }
    }

    // Discards the stored credential and returns to the first-time login form,
    // e.g. to use a different nsec or after a corrupted credential.
    function resetCredential() {
        localStorage.removeItem(STORAGE_KEY);
        setUnlockError('');
        renderPanel();
        emitStatus();
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
        const unlockButton = el('trace-unlock-btn');
        if (unlockButton) {
            unlockButton.addEventListener('click', handleUnlock);
        }
        const unlockField = el('trace-unlock-password');
        if (unlockField) {
            unlockField.addEventListener('keydown', (e) => {
                if (e.key === 'Enter') {
                    handleUnlock();
                }
            });
        }
        const resetButton = el('trace-unlock-reset');
        if (resetButton) {
            resetButton.addEventListener('click', resetCredential);
        }
        const logoutButton = el('trace-logout-btn');
        if (logoutButton) {
            logoutButton.addEventListener('click', logout);
        }
    }

    function bindActivityTracking() {
        ['click', 'keydown'].forEach((eventName) => {
            document.addEventListener(eventName, resetIdleTimer, { passive: true });
        });
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
        bindActivityTracking();
        renderPanel();
        emitStatus();
    }

    window.cashuSession = {
        getState: getState,
        getPubkey: getPubkey,
        onStatusChange: onStatusChange,
        authHeader: authHeader,
        login: login,
        unlock: unlock,
        logout: logout
    };

    document.addEventListener('DOMContentLoaded', init);
})();
