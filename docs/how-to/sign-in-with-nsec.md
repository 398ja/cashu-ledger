# Sign in with your nsec

The web Transaction Graph is access-controlled: every request is signed with your
Nostr private key (nsec). Instead of a browser extension, the application signs in
directly with your nsec and a password. This guide shows how to sign in, return
later, and sign out.

Your nsec is encrypted in the browser with your password and never leaves your
machine in plaintext. The public key derived from your nsec must be authorised for
the trace data — see [Operate the Trace Ledger](operate-trace-ledger.md) for
granting access.

## Sign in for the first time

1. Open the web application and go to the **Graph** section.
2. In the sign-in panel, paste your `nsec1…` private key.
3. Choose a **password**. It encrypts your nsec in this browser; you will use it to
   unlock on future visits.
4. Select **Sign in**.

The graph controls unlock and the status line shows your active public key. If the
nsec is malformed or the password is empty, the panel explains what to fix and
nothing is stored.

## Unlock on a later visit

Your encrypted nsec stays in the browser until you sign out, so a return visit asks
only for your password:

1. Open the application and go to the **Graph** section.
2. Enter your **password** and select **Unlock**.

An incorrect password is rejected and your stored nsec is left untouched, so you can
try again. To sign in with a different key, select **Use a different nsec**.

## Idle auto-lock

After 15 minutes of inactivity the session locks: the key is cleared from memory and
the next graph action asks for your password again. Your stored nsec is kept, so
unlocking needs only the password — not the nsec.

## Sign out

Select **Log out** to remove the encrypted nsec from the browser and clear the key
from memory. The graph locks until you sign in again. Logging out in one tab also
locks any other open tabs. Use this on shared or untrusted machines.

## If your stored credential is unreadable

If the browser reports that your stored credential cannot be read (for example after
local data corruption), select **Use a different nsec** to clear it and sign in fresh
with your nsec and password.

## Troubleshooting

| Symptom | Cause | Resolution |
|---------|-------|------------|
| "That is not a valid nsec" | The key is not a well-formed `nsec1…` value | Paste the full nsec private key |
| "Incorrect password" on unlock | Wrong password for the stored nsec | Re-enter the password, or **Use a different nsec** |
| "Authentication failed" after a request | The signed request was not accepted | Sign in again with your nsec |
| "Your key is not authorised to view this trace data" | Your public key lacks trace authority | Ask an operator to grant your public key access |
