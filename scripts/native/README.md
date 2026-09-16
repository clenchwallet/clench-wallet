# Rebuilding BDK's Android native libraries

Clench retains the published BDK 3.0.0 Kotlin wrapper and source-builds its JNI
libraries with a narrowly updated Cargo lock. No untraceable prebuilt JNI files
are checked into the repository.

## Pinned inputs

- BDK FFI source commit: `cfb3418524d451ba8d1758f0ec27f8443740b422`.
- Source archive: `https://codeload.github.com/bitcoindevkit/bdk-ffi/tar.gz/cfb3418524d451ba8d1758f0ec27f8443740b422`.
- Archive SHA-256: `629e3dc3f17050d500cafb71c17977f35bfaab5a947a3d6d082a7992bf784ec6`.
- Original lock: `docs/security/upstream/bdk-ffi-3.0.0-Cargo.lock`, retained unchanged.
- Build lock: `docs/security/upstream/bdk-ffi-3.0.0-clench-Cargo.lock`.
- Rust: **1.94.0**, official release component URLs and SHA-256 digests in
  `rust-toolchain.json`.
- Android NDK: **28.2.13676358 (r28c)**; Android API **24**, matching the vendor
  BDK release script (Clench itself may require a higher API level).
- Upstream `release-smaller` profile; upstream source, `Cargo.toml`, enabled
  dependency features and UniFFI version are unchanged.

The lock updates Rustls to **0.23.45** for RUSTSEC-2026-0285 and anyhow to
**1.0.103** for RUSTSEC-2026-0190. Rustls's mandatory compatible modern webpki and
AWS-LC dependency updates are included: webpki **0.103.14**, aws-lc-rs **1.18.0**,
and aws-lc-sys **0.44.0**. These are the minimum versions required by the patched
Rustls manifest, and Cargo's offline resolver validated the retained graph. The
patched lock SHA-256 is
`f75230c54970038d85db0dbb03529582fefdd8e6cf208fc65872d3344b36c4e2`.
The legacy Rustls 0.21/webpki 0.101
dependency is not silently migrated to a different major/minor API; its existing
advisory dispositions remain separate from this maintenance change.

## Build

On Linux x86-64, install Python 3.12+, curl, make, GCC, CMake and Android NDK r28c.
Point `ANDROID_NDK_HOME` at that NDK, or set `ANDROID_HOME` to its SDK root.

```sh
bash scripts/native/build-bdk.sh
```

The script installs checksum-pinned Rust components under `build/native-bdk/rust`
without changing the user's Rust installation, shell startup files or default
toolchain. Cargo validates downloaded crate checksums from the retained lock.
Builds use `--locked --offline` after fetching. The NDK must already be present;
the script does not install or accept a different NDK version.

Inherited Rust/Cargo compiler, wrapper, target/profile and C/C++ flag overrides
are removed before invoking the pinned toolchain. Reuse of the locally installed
Rust cache rechecks a bin/lib file-hash manifest. The local cache and its manifest
are still trusted local state, not an authenticated remote build cache; use a
fresh output directory if that state may have been modified by an untrusted job.
CI should not restore this directory from untrusted pull-request caches.

Outputs:

- `build/native-bdk/jni/{arm64-v8a,armeabi-v7a,x86_64}/libbdkffi.so`
- `build/native-bdk/provenance.json`: exact toolchains, input and output digests.
- `build/native-bdk/abi-comparison.json`: dynamic exports compared with the
  checksum-pinned vendor AAR.
- `build/native-bdk/*-readelf.txt`: ELF program headers and dynamic metadata.

Packaging/Gradle integration is handled by `prepare-bdk.py` in the same directory.
The build recipe fails if any vendor export is added or removed, if LOAD alignment is below
16 KiB, or if full RELRO/nonexecutable stack/no-text-relocations checks fail.
Exact export-set equality and clean-build reproduction are recorded in the
maintenance evidence, not inferred solely from a successful compilation.

## Reproducibility

The source archive and lock are immutable inputs, compiler components are
checksum-pinned, and the NDK version is fixed. Rust/C/C++ embedded source paths
are remapped to stable virtual paths. Cargo incremental compilation is disabled;
the archive's commit time provides `SOURCE_DATE_EPOCH`. Do not inject different
toolchains or undocumented compiler options to make a checksum mismatch pass.

For an independent build directory:

```sh
bash scripts/native/build-bdk.sh --output build/native-bdk-second
```

Compare all three library SHA-256 digests before using rebuilt bytes in an app.
The recipe proves how these replacement BDK libraries are built. It does not
establish reproduction of the original vendor binaries, fix SQLCipher provenance,
complete hardware/device acceptance, or sign off the broader security audit.
