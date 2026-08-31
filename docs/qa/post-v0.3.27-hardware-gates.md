# Post-v0.3.27 hardware development gates

This records unreleased source behavior after v0.3.27. It does not amend the
v0.3.27 release, security-review, or physical-evidence records. Automated
results are not physical-device passes.

This development snapshot is promoted into the authoritative v0.3.28 record
at [`physical-hardware-gates-v0.3.28.md`](physical-hardware-gates-v0.3.28.md).

| Surface | Source status | Remaining physical gate |
| --- | --- | --- |
| OneKey Pro | BC-UR v2 `crypto-psbt` QR implemented with automated routing/encoding coverage | Complete unsigned/signed PSBT QR round trips and on-device output review on representative firmware; **NOT RUN** |
| Krux | BC-UR v2 `crypto-psbt` QR plus user-selected microSD/file transfer implemented with automated routing/encoding coverage | Complete both QR and microSD round trips and on-device output review; **NOT RUN** |
| Specter DIY | BC-UR v2 `crypto-psbt` QR plus user-selected microSD/file transfer implemented with automated routing/encoding coverage | Complete both QR and microSD round trips and on-device output review; **NOT RUN** |
| Inbound QR compatibility | Legacy UR v1, `ur:psbt`, binary/text `ur:bytes`, and bounded static Base43 PSBT/transaction normalization have automated positive and hostile coverage | Complete representative Sparrow/device/camera round trips, including interruption and conflicting streams; **NOT RUN** |
| TAPSIGNER multisig | PSBT-v0 BIP-48 account-zero native P2WSH standard `CHECKMULTISIG` with `SIGHASH_ALL` is implemented; automated coverage includes witness-policy binding, mixed cosigner origins, verified existing partials, hostile substitutions, and atomic multi-input failure | Complete a real-card 2-of-N round trip plus mixed-origin/existing-partial, multi-input, interruption, wrong-card, exact-transaction, and explicit-broadcast checks; **NOT RUN** |
| SATSCARD | Unchanged: fund, verify, CVC-authenticated unseal, and sweep only | Arbitrary PSBT and multisig signing remain intentionally unavailable |
| Transport policy | No hardware-wallet flow opens USB or Bluetooth data; supported transports are QR, an intentional NFC tap, and user-selected file/removable media | Recheck manifest/UI claims and every physical flow before release; **NOT RUN** |

Physical results must identify the exact source commit, APK digest, Android
device/API, hardware model/firmware, network, redacted confirmation reference
where applicable, and whether broadcast remained an explicit separate action.
Retain only a redacted confirmation reference; never record a seed, private
key, PIN/CVC, sensitive PSBT, reusable address, xpub, full transaction ID, or
unredacted card identity.
