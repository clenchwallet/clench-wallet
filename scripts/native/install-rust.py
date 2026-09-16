#!/usr/bin/env python3
"""Install the checksum-pinned Rust components locally; do not modify shell/user state."""
import argparse
import concurrent.futures
import hashlib
import json
import pathlib
import shutil
import subprocess
import tarfile
import urllib.request


def sha256(path):
    with open(path, 'rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def installed_hashes(destination):
    return {str(path.relative_to(destination)): sha256(path)
            for directory in ['bin', 'lib']
            for path in sorted((destination / directory).rglob('*')) if path.is_file()}


def download(url, destination, expected, segments=1):
    if not destination.exists() or sha256(destination) != expected:
        partial = destination.with_suffix(destination.suffix + '.partial')
        command = ['curl', '--fail', '--location', '--retry', '3',
                   '--connect-timeout', '20', '--max-time', '900', '--silent', '--show-error']
        if segments == 1:
            subprocess.run(command + [url, '-o', str(partial)], check=True)
        else:
            with urllib.request.urlopen(urllib.request.Request(url, method='HEAD'), timeout=60) as response:
                size = int(response.headers['Content-Length'])
            chunk_size = (size + segments - 1) // segments
            def fetch(index):
                begin = index * chunk_size
                end = min(size, begin + chunk_size) - 1
                piece = partial.with_suffix('.part-' + str(index))
                subprocess.run(command + ['--range', str(begin) + '-' + str(end),
                               url, '-o', str(piece)], check=True)
                if piece.stat().st_size != end - begin + 1:
                    raise ValueError('Unexpected range response size: ' + url)
                return piece
            with concurrent.futures.ThreadPoolExecutor(max_workers=segments) as executor:
                pieces = list(executor.map(fetch, range(segments)))
            with partial.open('wb') as output:
                for piece in pieces:
                    with piece.open('rb') as stream:
                        shutil.copyfileobj(stream, output)
        if sha256(partial) != expected:
            raise ValueError('Download checksum mismatch: ' + url)
        partial.replace(destination)
    return destination


def install(base):
    config = json.loads(pathlib.Path(__file__).with_name('rust-toolchain.json').read_text())
    destination = base / 'rust'
    marker = destination / 'clench-toolchain.json'
    file_manifest = destination / 'clench-installed-files.json'
    if marker.exists() and json.loads(marker.read_text()) == config:
        if file_manifest.exists():
            if installed_hashes(destination) != json.loads(file_manifest.read_text()):
                raise ValueError('Installed Rust cache changed; rebuild in a fresh output directory.')
            return destination
    downloads = base / 'downloads'
    downloads.mkdir(parents=True, exist_ok=True)
    def fetch(component):
        return download(component['url'], downloads / component['url'].rsplit('/', 1)[1],
                        component['sha256'], segments=8)
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
        archives = list(executor.map(fetch, config['components']))
    unpack = base / 'rust-install'
    unpack.mkdir(exist_ok=True)
    for archive in archives:
        with tarfile.open(archive) as tar:
            tar.extractall(unpack, filter='data')
        source = unpack / archive.name.removesuffix('.tar.xz')
        subprocess.run(['sh', str(source / 'install.sh'), '--prefix=' + str(destination),
                        '--disable-ldconfig'], check=True)
    marker.write_text(json.dumps(config, indent=2) + '\n')
    file_manifest.write_text(json.dumps(installed_hashes(destination), indent=2) + '\n')
    return destination


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=pathlib.Path)
    args = parser.parse_args()
    print(install(args.directory.resolve()))
