#!/usr/bin/env bash
# Read-only verification of anonymously downloaded production assets on Linux.
set -euo pipefail
: "${RELEASE_TAG:?}" "${SOURCE_COMMIT:?}" "${VERSION:?}" "${VERSION_CODE:?}"
: "${PUBLIC_EVIDENCE_DIR:?}" "${INDEPENDENT_APK:?}"
test "$(git rev-parse HEAD)" = "$SOURCE_COMMIT"
test "$RELEASE_TAG" = "v$VERSION"
mkdir -p "$PUBLIC_EVIDENCE_DIR"
BUNDLE="$PUBLIC_EVIDENCE_DIR/bundle"
mkdir "$BUNDLE"
export BUNDLE
python3 - <<'PY'
import json, os, pathlib, re, subprocess, urllib.request
version = os.environ['VERSION']
tag = os.environ['RELEASE_TAG']
if not re.fullmatch(r'v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', tag):
    raise SystemExit('Invalid release tag')
repo = 'clenchwallet/clench-wallet'
url = f'https://api.github.com/repos/{repo}/releases/tags/{tag}'
# Intentionally anonymous: a private or draft release must not satisfy this gate.
with urllib.request.urlopen(url, timeout=60) as response:
    release = json.load(response)
if release['draft'] or release['prerelease'] or release['tag_name'] != tag:
    raise SystemExit('Expected a public production release')
expected = {
    f'clench-{version}-release.apk', f'clench-{version}-unsigned.apk',
    f'clench-{version}-sbom.cdx.json', 'PROVENANCE.intoto.jsonl',
    'INDEPENDENT-APK-VERIFICATION.json', 'UNSIGNED-APPROVAL.txt',
    'ORIGINAL-UNSIGNED-BUILD-SHA256SUMS', 'VERIFIED-UNSIGNED-SHA256SUMS',
    'POST-SIGN-UNSIGNED-BUILD-SHA256SUMS', 'SHA256SUMS', 'SHA256SUMS.txt',
    'RELEASE-MANIFEST.txt', 'RELEASE-NOTES.md',
}
assets = release['assets']
if len(assets) != 13 or {a['name'] for a in assets} != expected:
    raise SystemExit('Public release does not satisfy the exact 13-asset contract')
pathlib.Path(os.environ['PUBLIC_EVIDENCE_DIR'], 'release.json').write_text(json.dumps(release, indent=2)+'\n')
for asset in assets:
    url = f"https://github.com/{repo}/releases/download/{tag}/{asset['name']}"
    if asset['browser_download_url'] != url:
        raise SystemExit('Unexpected public asset URL')
    subprocess.run(['curl', '--proto', '=https', '--tlsv1.2', '--fail', '--location',
                    '--retry', '5', '--retry-all-errors', '--silent', '--show-error',
                    '--output', str(pathlib.Path(os.environ['BUNDLE'], asset['name'])), url], check=True)
PY
scripts/release/verify-release-bundle.sh "$BUNDLE"
for evidence in "$BUNDLE"/*; do
  gh attestation verify "$evidence" \
    --repo clenchwallet/clench-wallet \
    --signer-workflow clenchwallet/clench-wallet/.github/workflows/release.yml \
    --source-digest "$SOURCE_COMMIT" --source-ref refs/heads/master \
    --deny-self-hosted-runners --format json \
    > "$PUBLIC_EVIDENCE_DIR/$(basename "$evidence").attestation.json"
done
gh attestation verify "$BUNDLE/clench-$VERSION-release.apk" \
  --repo clenchwallet/clench-wallet --predicate-type https://cyclonedx.org/bom \
  --signer-workflow clenchwallet/clench-wallet/.github/workflows/release.yml \
  --source-digest "$SOURCE_COMMIT" --source-ref refs/heads/master \
  --deny-self-hosted-runners --format json > "$PUBLIC_EVIDENCE_DIR/sbom-attestation.json"
scripts/release/verify-sbom-attestation.py "$PUBLIC_EVIDENCE_DIR/sbom-attestation.json" \
  "$BUNDLE/clench-$VERSION-sbom.cdx.json"
scripts/release/verify-independent-apk.sh "$BUNDLE" "$INDEPENDENT_APK" \
  "$PUBLIC_EVIDENCE_DIR/fresh-independent-verification.json" \
  "$BUNDLE/clench-$VERSION-unsigned.apk"
python3 - <<'PY'
import hashlib,json,os,pathlib,tomllib,zipfile
version = os.environ['VERSION']
with zipfile.ZipFile(pathlib.Path(os.environ['BUNDLE'], f'clench-{version}-release.apk')) as apk:
    paths = sorted(p for p in apk.namelist() if p.endswith('/libbdkffi.so'))
    expected = sorted(f'lib/{abi}/libbdkffi.so' for abi in ('arm64-v8a','armeabi-v7a','x86_64'))
    if paths != expected:
        raise SystemExit('Unexpected packaged BDK ABI set')
    packaged = {p:hashlib.sha256(apk.read(p)).hexdigest() for p in paths}
lock = tomllib.loads(pathlib.Path('docs/security/upstream/bdk-ffi-3.0.0-clench-Cargo.lock').read_text())
pairs = {(p['name'],p['version']) for p in lock['package']}
for pair in [('rustls','0.23.45'),('anyhow','1.0.103'),('rustls-webpki','0.103.14'),('aws-lc-rs','1.18.0'),('aws-lc-sys','0.44.0')]:
    if pair not in pairs:
        raise SystemExit('Required patched dependency absent from fresh rebuild inputs')
if ('rustls','0.23.40') in pairs or ('anyhow','1.0.102') in pairs:
    raise SystemExit('Superseded dependency present')
report = {'source_commit':os.environ['SOURCE_COMMIT'], 'packaged_bdk_sha256':packaged,
          'proof':'Fresh independent source/native/strict-Gradle rebuild matched raw unsigned APK and every signed APK ZIP entry.'}
pathlib.Path(os.environ['PUBLIC_EVIDENCE_DIR'],'packaged-native.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
PY
printf 'Public release independently verified: %s at %s\n' "$RELEASE_TAG" "$SOURCE_COMMIT"
