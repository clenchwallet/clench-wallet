# Wallet-scoped UTXO metadata — schema 14

UTXO labels and frozen state belong to `(walletId, outpoint)`. Overlapping
watch-only/hot wallet views can describe the same outpoint without replacing
one another's metadata. DAO reads, field updates and deletes require that wallet
identity. Label changes and freeze toggles use Room transactions so a concurrent
label edit cannot overwrite a newer freeze state.

## Upgrade and recovery

Room's `13 -> 14` upgrade transaction creates the composite-key table, copies
**every surviving row without altering its values**, replaces the old table and
commits only after schema validation. The same migration terminates the existing
supported `3 -> ... -> 13` routes. There is no destructive migration fallback,
and no new migration is invented for unsupported schema 1/2 installations.

A failed/interrupted migration rolls back the table changes and original rows;
opening with the corrected schema-14 app retries the upgrade. Keep the original
database and its encryption-key association intact. Do not clear application
data or install an older APK as a repair procedure. A successful schema-14
upgrade is **not** compatible with a schema-13 app: use a forward-fix release.
Restoring an older installation requires an independently verified, consistent
pre-upgrade application backup and an explicit recovery procedure, not ad hoc
replacement of a database file or a destructive downgrade.

Earlier versions might already have overwritten one wallet's metadata with
another wallet's row. Those lost rows cannot be inferred from survivors. Recovery
requires an independently available trusted backup; this migration does not
invent missing labels, ownership or frozen states.

## Backup ownership and offline restore

All metadata references must name a declared, nonempty source wallet ID. Invalid
or ambiguous mappings reject the import transaction; no partial wallet or
metadata writes are retained. Metadata outpoints are validated as 32-byte txids
and unsigned 32-bit indices and canonicalized, with conflicting duplicate
records rejected.

Restore matches network **and both receive/change descriptors**. An existing
exact ID/identity match preserves that view. A single unambiguous full-identity
match under another ID intentionally restores the same wallet. Multiple possible
local targets require original IDs; one target cannot silently consume two
source views. Separate views in a fresh backup retain distinct identities.
Repeated import for the same wallet deliberately updates that wallet's metadata.

No network sync is needed. Unknown, unsynced and historical/spent outpoint notes
remain scoped to their validated wallet. Their presence is **not evidence that a
coin belongs to that wallet or is spendable**. Transaction construction must
resolve actual native-wallet inputs and enforce current wallet-local frozen
policy, including imported metadata, before signing/export/broadcast.

## Verification scope

`WalletScopedMetadataTest` exercises the actual importer and Room DAO with
collisions, same-wallet updates, overlapping descriptors, offline unknown
outpoints, invalid owner preflight and transactional rollback.
`WalletScopedMetadataMigrationTest` exercises supported legacy routes, encrypted
SQLCipher migration/open/restart/import, injected interruption and retry, and
unsupported-version fail-closed behavior. Host SQL checks or compilation alone
are not Android runtime or physical-device acceptance.
