#!/usr/bin/env python3
"""Source-build the three BDK Android libraries with a pinned Rust/NDK toolchain."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[2]
COMMIT = 'cfb3418524d451ba8d1758f0ec27f8443740b422'
ARCHIVE_SHA256 = '629e3dc3f17050d500cafb71c17977f35bfaab5a947a3d6d082a7992bf784ec6'
UPSTREAM_LOCK_SHA256 = '8e86d388a119564809fafa5fed1b851357f08d8a5cb03634ec6092aec074476a'
VENDOR_AAR_SHA256 = 'e11f099ab3f7acce9770825d9f431ce0970a30356c46ebed6aef66594491bb1e'
NDK_VERSION = '28.2.13676358'
API = 24
TARGETS = [
    ('arm64-v8a', 'aarch64-linux-android', 'aarch64-linux-android'),
    ('armeabi-v7a', 'armv7-linux-androideabi', 'armv7a-linux-androideabi'),
    ('x86_64', 'x86_64-linux-android', 'x86_64-linux-android'),
]
LOCK = ROOT / 'docs/security/upstream/bdk-ffi-3.0.0-clench-Cargo.lock'

spec = importlib.util.spec_from_file_location('installer', Path(__file__).with_name('install-rust.py'))
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)
sha256 = installer.sha256


def run(command, env=None, cwd=None):
    return subprocess.check_output(command, env=env, cwd=cwd, text=True).strip()


def ndk_directory():
    candidates = [os.environ.get('ANDROID_NDK_HOME'), os.environ.get('ANDROID_NDK_ROOT')]
    for key in ['ANDROID_HOME', 'ANDROID_SDK_ROOT']:
        if os.environ.get(key):
            candidates.append(str(Path(os.environ[key]) / 'ndk' / NDK_VERSION))
    candidates += [str(Path.home() / 'Android/Sdk/ndk' / NDK_VERSION),
                   '/opt/android-sdk/ndk/' + NDK_VERSION]
    for candidate in candidates:
        if candidate:
            directory = Path(candidate).resolve()
            properties = directory / 'source.properties'
            if properties.exists() and 'Pkg.Revision = ' + NDK_VERSION in properties.read_text():
                return directory
    raise SystemExit('Install Android NDK ' + NDK_VERSION + ' with sdkmanager and set ANDROID_NDK_HOME.')


def symbols(nm, path):
    output = run([str(nm), '--dynamic', '--defined-only', '--format=posix', str(path)])
    return sorted(line.split()[0] for line in output.splitlines() if line.strip())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/native-bdk')
    parser.add_argument('--jobs', type=int, default=4)
    parser.add_argument('--offline', action='store_true', help='Use already verified local download/Cargo caches.')
    args = parser.parse_args()
    if (platform.system(), platform.machine()) != ('Linux', 'x86_64'):
        raise SystemExit('Pinned build currently supports Linux x86_64 only.')
    output = args.output.resolve()
    downloads = output / 'downloads'
    downloads.mkdir(parents=True, exist_ok=True)
    toolchain = installer.install(output)
    ndk = ndk_directory()
    llvm = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    upstream_url = 'https://codeload.github.com/bitcoindevkit/bdk-ffi/tar.gz/' + COMMIT
    archive = installer.download(upstream_url, downloads / 'upstream.tar.gz', ARCHIVE_SHA256)
    # Always re-extract pristine source; build outputs/cache live outside the tree.
    source = output / ('bdk-ffi-' + COMMIT)
    if source.exists():
        shutil.rmtree(source)
    with tarfile.open(archive) as tar:
        tar.extractall(output, filter='data')
        source_date_epoch = str(tar.getmembers()[0].mtime)
    crate = source / 'bdk-ffi'
    if sha256(crate / 'Cargo.lock') != UPSTREAM_LOCK_SHA256:
        raise SystemExit('Unexpected upstream Cargo.lock digest')
    shutil.copyfile(LOCK, crate / 'Cargo.lock')
    env = os.environ.copy()
    # Inherited build overrides must not silently change the pinned compiler,
    # feature/flag selection or linker. Network/proxy settings remain untouched.
    override_prefixes = ('CARGO_', 'RUST', 'CC', 'CXX', 'AR_', 'CFLAGS', 'CXXFLAGS',
                         'CPPFLAGS', 'LDFLAGS', 'LD_', 'BINDGEN_EXTRA_CLANG_ARGS',
                         'CMAKE_', 'PKG_CONFIG_', 'HOST_', 'TARGET_')
    for key in list(env):
        if key.startswith(override_prefixes) or key in {'AR', 'AS', 'LD', 'NM', 'RANLIB', 'STRIP'}:
            del env[key]
    env['PATH'] = str(toolchain / 'bin') + os.pathsep + str(llvm) + os.pathsep + env['PATH']
    env['RUSTC'] = str(toolchain / 'bin/rustc')
    env['RUSTDOC'] = str(toolchain / 'bin/rustdoc')
    env['CARGO_HOME'] = str(output / 'cargo-home')
    env['CARGO_TARGET_DIR'] = str(output / 'target')
    env['SOURCE_DATE_EPOCH'] = source_date_epoch
    env['CARGO_INCREMENTAL'] = '0'
    env['LC_ALL'] = 'C'
    env['TZ'] = 'UTC'
    # Use the same virtual paths regardless of checkout/CI user/home location.
    remaps = [(source, '/bdk-source'), (Path(env['CARGO_HOME']), '/cargo'),
              (toolchain, '/rust'), (ndk, '/ndk'), (output, '/bdk-build')]
    env['RUSTFLAGS'] = ' '.join('--remap-path-prefix=' + str(old) + '=' + new for old, new in remaps)
    env['RUSTFLAGS'] += ' -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384'
    env['CFLAGS'] = ' '.join('-ffile-prefix-map=' + str(old) + '=' + new for old, new in remaps)
    env['CXXFLAGS'] = env['CFLAGS']
    env['AR'] = str(llvm / 'llvm-ar')
    for _, target, compiler in TARGETS:
        key = target.replace('-', '_')
        env['CC_' + key] = str(llvm / (compiler + str(API) + '-clang'))
        env['CXX_' + key] = str(llvm / (compiler + str(API) + '-clang++'))
        env['AR_' + key] = str(llvm / 'llvm-ar')
        env['CARGO_TARGET_' + key.upper() + '_LINKER'] = env['CC_' + key]
    fetch_command = ['cargo', 'fetch', '--locked']
    if args.offline:
        fetch_command += ['--offline']
    for _, target, _ in TARGETS:
        fetch_command += ['--target', target]
    subprocess.run(fetch_command, cwd=crate, env=env, check=True)
    for abi, target, _ in TARGETS:
        print('Building ' + abi + ' (' + target + ')', flush=True)
        subprocess.run(['cargo', 'build', '--lib', '--locked', '--offline', '--profile',
                        'release-smaller', '--target', target, '--jobs', str(args.jobs)],
                       cwd=crate, env=env, check=True)
        destination = output / 'jni' / abi / 'libbdkffi.so'
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(output / 'target' / target / 'release-smaller/libbdkffi.so', destination)
    vendor = installer.download(
        'https://repo.maven.apache.org/maven2/org/bitcoindevkit/bdk-android/3.0.0/bdk-android-3.0.0.aar',
        downloads / 'bdk-android-3.0.0.aar', VENDOR_AAR_SHA256)
    abi_report = {}
    payloads = []
    for abi, target, _ in TARGETS:
        name = 'jni/' + abi + '/libbdkffi.so'
        library = output / name
        vendor_library = output / 'vendor' / name
        vendor_library.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(vendor) as aar:
            vendor_library.write_bytes(aar.read(name))
        old = symbols(llvm / 'llvm-nm', vendor_library)
        new = symbols(llvm / 'llvm-nm', library)
        missing = sorted(set(old) - set(new))
        added = sorted(set(new) - set(old))
        abi_report[abi] = {'vendor_exports': len(old), 'rebuilt_exports': len(new),
                           'removed': missing, 'added': added,
                           'vendor_export_names_sha256': hashlib.sha256(('\n'.join(old)+'\n').encode()).hexdigest(),
                           'rebuilt_export_names_sha256': hashlib.sha256(('\n'.join(new)+'\n').encode()).hexdigest()}
        payloads.append({'path': name, 'bytes': library.stat().st_size, 'sha256': sha256(library)})
        if missing or added:
            raise SystemExit('Rebuilt library changes vendor ABI exports: ' + abi +
                             '; removed=' + ', '.join(missing) + '; added=' + ', '.join(added))
        readelf = run([str(llvm / 'llvm-readelf'), '--wide', '--program-headers', '--dynamic', str(library)])
        (output / (abi + '-readelf.txt')).write_text(readelf + '\n')
        loads = [line for line in readelf.splitlines() if line.strip().startswith('LOAD ')]
        if not loads or any(int(line.split()[-1], 16) < 16384 for line in loads):
            raise SystemExit('ELF LOAD alignment below 16 KiB: ' + abi)
        if 'GNU_RELRO' not in readelf or 'BIND_NOW' not in readelf:
            raise SystemExit('Missing full RELRO: ' + abi)
        if 'TEXTREL' in readelf:
            raise SystemExit('Unexpected text relocations: ' + abi)
        stacks = [line for line in readelf.splitlines() if 'GNU_STACK' in line]
        if len(stacks) != 1 or ' E ' in stacks[0] or 'RWE' in stacks[0]:
            raise SystemExit('Missing or executable GNU_STACK: ' + abi)
    (output / 'abi-comparison.json').write_text(json.dumps(abi_report, indent=2) + '\n')
    provenance = {'schema_version': 1, 'source_url': upstream_url, 'source_commit': COMMIT,
                  'source_archive_sha256': ARCHIVE_SHA256, 'original_lock_sha256': UPSTREAM_LOCK_SHA256,
                  'patched_lock_sha256': sha256(LOCK), 'rust_toolchain': json.loads((toolchain / 'clench-toolchain.json').read_text()),
                  'rustc': run(['rustc', '--version', '--verbose'], env=env),
                  'cargo': run(['cargo', '--version'], env=env), 'ndk_version': NDK_VERSION,
                  'clang': run([str(llvm / 'clang'), '--version']).replace(str(ndk), '/ndk'),
                  'clang_sha256': sha256(llvm / 'clang'), 'android_api': API,
                  'profile': 'release-smaller', 'source_date_epoch': source_date_epoch,
                  'native_payloads': payloads, 'abi_comparison': 'abi-comparison.json',
                  'scope': 'Source-built replacement BDK JNI only; unchanged vendor Kotlin wrapper, not a whole-product audit.'}
    (output / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    print('Native build complete: ' + str(output / 'provenance.json'))


if __name__ == '__main__':
    main()
