# Fonebrew integration

Fylz is the constellation's file authority.

Fonebrew must not grow a parallel file manager, mount layer, archive stack or remote-storage stack. It receives a user-granted Storage Access Framework tree URI and treats the provider/document identity as opaque.

`integration/FylzWorkspaceContract.kt` defines the stable Android handoff:
- request a workspace with `ACTION_OPEN_DOCUMENT_TREE`;
- persist the returned URI grant;
- pass an opaque `content://` tree URI plus simple metadata;
- never turn a provider document ID into a guessed filesystem path.

This lets Fonebrew execute against local storage, removable media, USB and third-party providers while Fylz continues to own browsing, capability discovery, file operations, history, backups and remotes.

## Direct handoff

The current-folder overflow now exposes **Open workspace in Fonebrew**. Fylz grants the active SAF tree URI directly to the Fonebrew package with read/prefix/persistable flags and write access only when the existing grant is writable. Fonebrew receives the same opaque tree capability; no filesystem path is synthesized.

If the receiving app is absent or cannot accept the grant, Fylz leaves the workspace untouched and reports the handoff failure.
