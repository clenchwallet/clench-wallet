#!/usr/bin/env python3
"""Bounded external regression of a signed release APK on an owned 16 KB AVD.

No app code, instrumentation, signing keys, physical devices or release gates.
Only a newly created AVD is mutated. All subprocesses have bounded timeouts.
"""
import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import signal
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
PACKAGE = 'net.clench.wallet'
COMPONENT = PACKAGE + '/.ui.MainActivity'
PROPS = {'bionic.linker.16kb.app_compat.enabled': 'false',
         'pm.16kb.app_compat.disabled': 'true'}
REQUIRED = ['artifact', 'static', 'environment', 'installed', 'onboarding',
            'created', 'created_restart', 'created_reopen', 'imported',
            'imported_restart', 'imported_reopen', 'both_wallets', 'database', 'final']


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(path):
    hasher = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            hasher.update(block)
    return hasher.hexdigest()


def validate_environment(env):
    require(env['page_size'] == 16384, 'PAGE_SIZE must be 16384')
    require(env['api'] >= 35, 'Android API must be >=35')
    require(env['qemu'] == '1', 'Target must be an emulator')
    require(env['abi'] in ('arm64-v8a', 'x86_64'), 'Unsupported runtime ABI')
    require(env['fallback'] == PROPS, 'Compatibility fallback is not disabled')


def validate_steps(steps):
    require([s['name'] for s in steps] == REQUIRED and
            all(s['status'] == 'PASS' for s in steps), 'Incomplete regression steps')


def address_from(tree):
    values = {n.get('text') for n in tree.iter('node')
              if re.fullmatch(r'tb1[a-z0-9]{20,90}', n.get('text', ''))}
    require(len(values) == 1, 'Expected exactly one Testnet receive address')
    return values.pop()


class Runner:
    def __init__(self, args):
        self.args = args
        self.out = args.evidence.resolve()
        self.out.mkdir(parents=True, exist_ok=False)  # never overwrite a prior failure
        (self.out / 'runner-source.py').write_bytes(Path(__file__).read_bytes())
        (self.out / 'static-source.py').write_bytes((HERE.parent / 'check-jna-16kb.py').read_bytes())
        self.start = time.monotonic()
        self.deadline = self.start + args.timeout
        self.serial = f'emulator-{args.port}'
        self.sdk = args.sdk.resolve()
        self.adb = self.sdk / 'platform-tools/adb'
        self.emulator = self.sdk / 'emulator/emulator'
        self.env = os.environ.copy()
        self.proc = None
        self.temp = None
        self.seq = 0
        self.stage = 'setup'
        self.report = {'schema_version': 1, 'status': 'FAIL', 'steps': [],
                       'started_utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                       'command': sys.argv, 'serial': self.serial,
                       'instrumented_build': False, 'physical_device_used': False}

    def save(self, name, value):
        path = self.out / name
        path.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(value, bytes):
            path.write_bytes(value)
        else:
            path.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')

    def command(self, *args, timeout=45, check=True, input=None):
        remaining = self.deadline - time.monotonic()
        require(remaining > 0, 'Overall deadline exceeded')
        cmd = list(map(str, args))
        p = subprocess.run(cmd, input=input, stdout=subprocess.PIPE,
                           stderr=subprocess.PIPE, timeout=min(timeout, remaining), env=self.env)
        with (self.out / 'commands.jsonl').open('a') as log:
            log.write(json.dumps({'command': cmd, 'returncode': p.returncode,
                                  'elapsed': round(time.monotonic() - self.start, 2),
                                  'stderr': p.stderr.decode(errors='replace')[-2000:]}) + '\n')
        if check and p.returncode:
            raise RuntimeError(f'Command failed ({p.returncode}): {cmd}: '
                               + (p.stdout + p.stderr).decode(errors='replace')[-4000:])
        return p.stdout

    def a(self, *args, **kwargs):
        require(self.proc is not None, 'No owned emulator process')
        require(self.proc.poll() is None, 'Owned emulator exited')
        return self.command(self.adb, '-s', self.serial, *args, **kwargs)

    def shell(self, *args, **kwargs):
        return self.a('shell', *args, **kwargs).decode().strip()

    def step(self, name, **observations):
        require(name not in [s['name'] for s in self.report['steps']], 'Duplicate step')
        self.report['steps'].append({'name': name, 'status': 'PASS',
                                     'elapsed_seconds': round(time.monotonic() - self.start, 2),
                                     **observations})
        self.save('result.json', self.report)
        print(name + ': PASS', flush=True)

    def snap(self):
        self.a('shell', 'uiautomator', 'dump', '/sdcard/strict16-ui.xml', timeout=20)
        data = self.a('exec-out', 'cat', '/sdcard/strict16-ui.xml')
        self.seq += 1
        self.save(f'ui/{self.seq:04}-{self.stage}.xml', data)
        return ET.fromstring(data)

    def wait_ui(self, predicate, description, timeout=35):
        end = min(self.deadline, time.monotonic() + timeout)
        while time.monotonic() < end:
            tree = self.snap()
            if predicate(tree):
                return tree
            # Never relaunch or repeat a wallet action after a crash.
            require(self.shell('pidof', PACKAGE, check=False), f'App died waiting for {description}')
            time.sleep(0.4)
        raise RuntimeError('UI timeout: ' + description)

    @staticmethod
    def nodes(tree, label):
        return [n for n in tree.iter('node') if label in (n.get('text'), n.get('content-desc'))]

    def visible(self, label):
        return self.wait_ui(lambda t: bool(self.nodes(t, label)), label)

    def tap(self, node):
        require(node.get('enabled') == 'true', 'Disabled UI control')
        nums = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
        require(len(nums) == 4 and nums[2] > nums[0] and nums[3] > nums[1], 'Invalid UI bounds')
        self.shell('input', 'tap', str((nums[0] + nums[2]) // 2), str((nums[1] + nums[3]) // 2))

    @staticmethod
    def actions(tree, label):
        parents = {child: parent for parent in tree.iter() for child in parent}
        actions = []
        for node in Runner.nodes(tree, label):
            while node is not None and node.get('clickable') != 'true':
                node = parents.get(node)
            if node is not None and node not in actions:
                actions.append(node)
        return actions

    def click(self, label):
        tree = self.wait_ui(lambda t: bool(self.actions(t, label)), 'clickable ' + label)
        nodes = self.actions(tree, label)
        require(len(nodes) == 1, 'Ambiguous clickable UI control: ' + label)
        self.tap(nodes[0])

    def edit(self, value):
        tree = self.wait_ui(lambda t: any(n.get('class') == 'android.widget.EditText'
                                         for n in t.iter('node')), 'text field')
        nodes = [n for n in tree.iter('node') if n.get('class') == 'android.widget.EditText']
        require(len(nodes) == 1, 'Ambiguous text field')
        self.tap(nodes[0])
        self.shell('input', 'keyevent', 'KEYCODE_MOVE_END')
        # Fresh import form has an empty name; fail instead of silently appending.
        require(not nodes[0].get('text'), 'Expected empty import name')
        self.shell('input', 'text', value)
        self.shell('input', 'keyevent', 'KEYCODE_BACK')

    def environment(self):
        prop = lambda name: self.shell('getprop', name)
        env = {'page_size': int(self.shell('getconf', 'PAGE_SIZE')),
               'api': int(prop('ro.build.version.sdk')), 'abi': prop('ro.product.cpu.abi'),
               'fingerprint': prop('ro.build.fingerprint'), 'qemu': prop('ro.kernel.qemu'),
               'fallback': {p: prop(p) for p in PROPS}}
        validate_environment(env)
        require(env['abi'] == self.image_abi, 'Guest ABI differs from image')
        self.save('environment-' + self.stage + '.json', env)
        return env

    def offline(self):
        for family in ('-4', '-6'):
            routes = self.shell('ip', family, 'route', 'show', 'table', 'main')
            self.save('routes' + family + '-' + self.stage + '.txt', routes.encode())
            require(not routes, 'Guest must have no external routes')

    def launch(self):
        self.environment()
        self.offline()
        self.a('shell', 'am', 'start', '-W', '-n', COMPONENT)
        self.visible('Receive')

    def identity(self, name, expected=None):
        self.visible(name)
        self.click('Receive')
        tree = self.wait_ui(lambda t: any(re.fullmatch(r'tb1[a-z0-9]{20,90}', n.get('text', ''))
                                         for n in t.iter('node')), 'derived Testnet address')
        address = address_from(tree)
        if expected:
            require(address == expected, f'Persisted address mismatch for {name}')
        pid = self.shell('pidof', PACKAGE)
        maps = self.a('shell', 'cat', f'/proc/{pid}/maps')
        self.save('maps-' + self.stage + '.txt', maps)
        # extractNativeLibs=false maps uncompressed libraries directly from base.apk;
        # /proc/maps has APK offsets, not necessarily .so filenames.
        static = json.loads((self.out / 'static.json').read_text())
        offsets = {x['path']: x['offset'] for x in static['apk_native_alignment']}
        mapping_evidence = {}
        with zipfile.ZipFile(self.args.apk) as archive:
            for lib in ('libjnidispatch.so', 'libbdkffi.so', 'libsqlcipher.so'):
                member = f'lib/{self.image_abi}/{lib}'
                begin = offsets[member]
                end = begin + archive.getinfo(member).file_size
                matches = []
                for line in maps.decode().splitlines():
                    fields = line.split()
                    if len(fields) >= 6 and 'x' in fields[1] and fields[-1] == self.installed_path:
                        if begin <= int(fields[2], 16) < end:
                            matches.append(line)
                require(matches, 'Missing executable APK mapping: ' + member)
                mapping_evidence[member] = {'zip_offset': begin, 'zip_end': end, 'executable_mappings': matches}
        self.save('native-mappings-' + self.stage + '.json', mapping_evidence)
        self.click('Back')
        return address

    def lifecycle(self, prefix, name, address):
        # First verify cold persistence, then background/reopen of that persisted
        # identity. Fresh-onboarding foreground routing is a separate limitation.
        self.stage = prefix + '_restart'
        before = self.shell('pidof', PACKAGE)
        self.shell('am', 'force-stop', PACKAGE)
        require(not self.shell('pidof', PACKAGE, check=False), 'Process survived force-stop')
        self.launch()
        new = self.shell('pidof', PACKAGE)
        require(new and new != before, 'Restart must create a fresh process')
        self.identity(name, address)
        self.step(self.stage, wallet_name=name, address=address, pid_before=before, pid_after=new)
        self.stage = prefix + '_reopen'
        self.shell('input', 'keyevent', 'KEYCODE_HOME')
        self.wait_ui(lambda t: not any(n.get('package') == PACKAGE for n in t.iter('node')),
                     'launcher visible after HOME')
        self.save('background-activities-' + prefix + '.txt',
                  self.a('shell', 'dumpsys', 'activity', 'activities'))
        self.launch()
        after = self.shell('pidof', PACKAGE)
        require(new == after, 'Background/reopen unexpectedly replaced process')
        self.identity(name, address)
        self.step(self.stage, wallet_name=name, address=address, pid_before=new, pid_after=after)

    def run(self):
        fixture = json.loads(self.args.fixture.read_text())
        self.fixture = fixture
        self.save('fixture.json', fixture)
        apk = self.args.apk.resolve()
        require(digest(apk) == fixture['apk_sha256'], 'APK SHA-256 differs from pinned fixture')
        tools = self.sdk / 'build-tools' / self.args.build_tools
        signature = self.command(tools / 'apksigner', 'verify', '--verbose', '--print-certs', apk)
        self.save('apk-signature.txt', signature)
        require(('Signer #1 certificate SHA-256 digest: ' + fixture['signer_sha256']).encode() in signature,
                'Wrong APK signer')
        require(b'Number of signers: 1' in signature, 'Expected one signer')
        badging = self.command(tools / 'aapt', 'dump', 'badging', apk)
        self.save('apk-badging.txt', badging)
        require(b"package: name='net.clench.wallet'" in badging and
                f"versionName='{fixture['version']}'".encode() in badging and
                f"versionCode='{fixture['version_code']}'".encode() in badging,
                'Wrong package/version')
        require(b'application-debuggable' not in badging, 'Debug APK prohibited')
        commit = self.command('git', '-C', REPO, 'rev-parse', fixture['source_commit'] + '^{commit}').decode().strip()
        require(commit == fixture['source_commit'], 'Source commit unavailable')
        self.report['identifiers'] = {'apk_sha256': digest(apk), 'source_commit': commit,
            'fixture_sha256': digest(self.args.fixture), 'runner_sha256': digest(Path(__file__)),
            'static_sha256': digest(HERE.parent / 'check-jna-16kb.py'),
            'runner_git_commit': self.command('git', '-C', REPO, 'rev-parse', 'HEAD').decode().strip(),
            'runner_git_status': self.command('git', '-C', REPO, 'status', '--porcelain').decode(),
            'host': platform.platform(), 'python': sys.version,
            'java_home': self.env.get('JAVA_HOME'),
            'tools': {str(p.relative_to(self.sdk)): digest(p) for p in
                      (self.adb, self.emulator, tools / 'aapt', tools / 'apksigner', tools / 'lib/apksigner.jar',
                       self.sdk / 'cmdline-tools/latest/bin/avdmanager')}}
        self.step('artifact', version=fixture['version'], signer_sha256=fixture['signer_sha256'])
        self.stage = 'static'
        self.command(sys.executable, '-B', HERE.parent / 'check-jna-16kb.py',
                     '--apk', apk, '--output', self.out / 'static.json')
        self.step('static')
        self.stage = 'environment'
        image_parts = self.args.image.split(';')
        require(len(image_parts) == 4 and image_parts[0] == 'system-images' and
                re.fullmatch(r'android-\d+', image_parts[1]) and
                int(image_parts[1][8:]) >= 35 and image_parts[2] == 'google_apis_ps16k' and
                image_parts[3] in ('arm64-v8a', 'x86_64'), 'Require official Google APIs ps16k image')
        self.image_abi = image_parts[3]
        require(platform.machine() in ('arm64', 'aarch64', 'x86_64', 'AMD64'), 'Unsupported host ISA')
        require((platform.machine() in ('arm64', 'aarch64')) == (self.image_abi == 'arm64-v8a'),
                'Guest and host ISA must match for accelerated execution')
        image = self.sdk.joinpath(*image_parts)
        image_props = (image / 'source.properties').read_bytes()
        require(b'Addon.VendorId=google' in image_props and b'page_size_16kb' in image_props,
                'Unrecognized image metadata')
        self.save('image-source.properties', image_props)
        self.save('sdk-command-tools-source.properties',
                  (self.sdk / 'cmdline-tools/latest/source.properties').read_bytes())
        self.save('image-package.xml', (image / 'package.xml').read_bytes())
        for tool, args in ((self.adb, ['version']), (self.emulator, ['-version']),
                           (self.emulator, ['-accel-check'])):
            self.save(tool.name + '-' + args[0].strip('-') + '.txt', self.command(tool, *args))
        # Require acceleration at launch as well as checking host capability.
        for port in (self.args.port, self.args.port + 1):
            with socket.socket() as sock:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                sock.bind(('127.0.0.1', port))
        devices = self.command(self.adb, 'devices').decode()
        require(self.serial not in devices, 'Chosen emulator serial is already in use')
        self.temp = tempfile.TemporaryDirectory(prefix='clench-strict16-')
        self.env['ANDROID_AVD_HOME'] = self.temp.name
        self.env['ANDROID_HOME'] = str(self.sdk)
        self.env['PATH'] = str(self.sdk / 'platform-tools') + os.pathsep + self.env.get('PATH', '')
        avd = Path(self.temp.name) / 'device.avd'
        name = 'clench_strict16_' + Path(self.temp.name).name.replace('-', '_')
        self.command(self.sdk / 'cmdline-tools/latest/bin/avdmanager', 'create', 'avd',
                     '--name', name, '--package', self.args.image, '--device', 'pixel_7',
                     '--path', avd, input=b'no\n', timeout=90)
        self.save('avd-config.ini', (avd / 'config.ini').read_bytes())
        cmd = [str(self.emulator), '-avd', name, '-port', str(self.args.port),
               '-accel', 'on', '-no-window', '-no-audio', '-no-snapshot',
               '-no-boot-anim', '-gpu', 'swiftshader', '-memory', '2048', '-cores', '4']
        self.report['emulator_command'] = cmd
        self.report['image_package'] = self.args.image
        with (self.out / 'emulator.log').open('wb') as log:
            self.proc = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT, env=self.env)
        self.a('wait-for-device', timeout=150)
        end = time.monotonic() + 180
        while self.shell('getprop', 'sys.boot_completed') != '1':
            require(time.monotonic() < end, 'Emulator boot timeout')
            time.sleep(1)
        require(self.a('emu', 'avd', 'name').decode().splitlines()[0] == name,
                'Connected emulator is not the AVD owned by this run')
        self.a('root')
        self.a('wait-for-device')
        require(self.shell('id', '-u') == '0', 'Rootable official image required for DB/maps evidence')
        for prop, value in PROPS.items():
            self.shell('setprop', prop, value)
        self.shell('svc', 'wifi', 'disable')
        self.shell('svc', 'data', 'disable')
        self.shell('cmd', 'connectivity', 'airplane-mode', 'enable')
        for interface in ('eth0', 'wlan0'):
            self.shell('ip', 'link', 'set', interface, 'down', check=False)
        self.shell('settings', 'put', 'system', 'system_locales', 'en-US')
        for setting in ('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale'):
            self.shell('settings', 'put', 'global', setting, '0')
        self.shell('input', 'keyevent', 'KEYCODE_WAKEUP')
        self.shell('wm', 'density', '240')  # expose the full English mnemonic picker
        self.save('display-density.txt', self.a('shell', 'wm', 'density'))
        self.shell('wm', 'dismiss-keyguard')
        self.offline()
        env = self.environment()
        self.save('getprop.txt', self.a('shell', 'getprop'))
        self.step('environment', **env)
        self.stage = 'installed'
        self.a('install', '--no-streaming', apk, timeout=90)
        package = self.a('shell', 'dumpsys', 'package', PACKAGE)
        self.save('installed-package.txt', package)
        require(b'DEBUGGABLE' not in package, 'Installed app must not be debuggable')
        installed = self.shell('pm', 'path', PACKAGE)
        require(installed.startswith('package:') and '\n' not in installed, 'Expected single installed APK')
        self.installed_path = installed.removeprefix('package:')
        actual = self.a('exec-out', 'cat', self.installed_path)
        require(hashlib.sha256(actual).hexdigest() == fixture['apk_sha256'], 'Installed APK bytes changed')
        self.a('logcat', '-b', 'all', '-c')
        self.step('installed', installed_apk_sha256=hashlib.sha256(actual).hexdigest())
        self.stage = 'onboarding'
        self.a('shell', 'am', 'start', '-W', '-n', COMPONENT)
        for label in ('Testnet', 'Offline mode', 'Continue in Offline Mode', 'Continue'):
            self.click(label)
        self.visible('Create New Wallet')
        self.step('onboarding', network='Testnet', app_mode='Offline', lock='default no lock')
        self.stage = 'created'
        for label in ('Create New Wallet', '12 words', 'Generate Seed Phrase'):
            self.click(label)
        tree = self.visible("I've Written It Down — Verify")
        texts = [n.get('text') for n in tree.iter('node') if n.get('text')]
        words = {int(t[:-1]): texts[i + 1] for i, t in enumerate(texts) if re.fullmatch(r'\d+\.', t)}
        require(set(words) == set(range(1, 13)), 'Expected twelve generated words')
        self.click("I've Written It Down — Verify")
        tree = self.wait_ui(lambda t: sum(bool(re.fullmatch(r'Word #\d+', n.get('text', '')))
                                         for n in t.iter('node')) == 4, 'four seed verification questions')
        nodes = list(tree.iter('node'))
        questions = [(i, n) for i, n in enumerate(nodes) if re.fullmatch(r'Word #\d+', n.get('text', ''))]
        for k, (index, node) in enumerate(questions):
            word = words[int(node.get('text').split('#')[1])]
            end = questions[k + 1][0] if k + 1 < len(questions) else len(nodes)
            choices = [n for n in nodes[index + 1:end] if n.get('text') == word]
            require(len(choices) == 1, 'Ambiguous verification answer')
            self.tap(choices[0])
        self.click('Continue')
        created = self.identity('My Wallet')
        self.step('created', wallet_name='My Wallet', address=created, verified_seed_questions=4)
        self.lifecycle('created', 'My Wallet', created)
        self.stage = 'imported'
        for label in ('Wallets', 'Add Wallet', 'Import Existing Wallet'):
            self.click(label)
        self.edit('Strict16-Import')
        words = fixture['mnemonic'].split()
        require(words == ['abandon'] * 11 + ['about'], 'Unsupported public mnemonic fixture')
        for word in words:
            self.click('A')
            self.click(word)
        self.click('Import Wallet')
        imported = self.identity('Strict16-Import', fixture['imported_address'])
        require(created != imported, 'Created and imported identities must differ')
        self.step('imported', wallet_name='Strict16-Import', address=imported,
                  expected_public_fixture_address=fixture['imported_address'])
        self.lifecycle('imported', 'Strict16-Import', imported)
        self.stage = 'both_wallets'
        self.click('Wallets')
        self.visible('Strict16-Import')
        self.click('My Wallet')
        self.identity('My Wallet', created)
        self.step('both_wallets', created_address=created, imported_address=imported)
        self.stage = 'database'
        self.shell('am', 'force-stop', PACKAGE)
        data = self.a('exec-out', 'cat', '/data/user/0/' + PACKAGE + '/databases/clench.db')
        self.save('database/clench.db', data)
        require(len(data) >= 4096 and not data.startswith(b'SQLite format 3'), 'Missing/encrypted DB assertion failed')
        with contextlib.closing(sqlite3.connect('file:' + str(self.out / 'database/clench.db') + '?mode=ro', uri=True)) as conn:
            try:
                conn.execute('select name from sqlite_master').fetchall()
            except sqlite3.DatabaseError as exc:
                rejection = str(exc)
            else:
                raise RuntimeError('Plain SQLite unexpectedly read encrypted DB')
        self.step('database', bytes=len(data), sha256=hashlib.sha256(data).hexdigest(),
                  header_hex=data[:16].hex(), plain_sqlite_rejection=rejection,
                  inference='SQLCipher mapping + opaque DB + successful wallet reload support encrypted persistence; no independent decryption or schema proof')
        self.stage = 'final'
        self.environment()
        self.offline()
        crash = self.a('logcat', '-b', 'crash', '-d')
        self.save('crash.txt', crash)
        require(not crash.strip(), 'Non-empty crash buffer')
        self.save('logcat.txt', self.a('logcat', '-d', '-v', 'threadtime', '-t', '4000'))
        self.step('final', crash_buffer_empty=True)
        validate_steps(self.report['steps'])
        self.report['status'] = 'PASS'

    def finish(self, error=None):
        # A separate 60-second budget preserves the first failure even after the main timeout.
        self.deadline = time.monotonic() + 60
        if error:
            self.report['failure'] = {'stage': self.stage, 'error': str(error)}
            self.save('first-failure.json', self.report['failure'])
            if self.proc and self.proc.poll() is None:
                for name, args in [('first-failure-crash.txt', ['logcat', '-b', 'crash', '-d']),
                                   ('first-failure-logcat.txt', ['logcat', '-d', '-v', 'threadtime', '-t', '4000']),
                                   ('first-failure-tombstones.txt', ['shell', 'ls', '-lt', '/data/tombstones'])]:
                    with contextlib.suppress(Exception):
                        self.save(name, self.a(*args, timeout=10))
                with contextlib.suppress(Exception):
                    self.a('pull', '/data/tombstones', self.out / 'tombstones', timeout=20)
        if self.proc:
            if self.proc.poll() is None:
                with contextlib.suppress(Exception):
                    self.a('emu', 'kill', timeout=5)
                try:
                    self.proc.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    self.proc.kill()
                    self.proc.wait(timeout=5)
        if self.temp:
            self.temp.cleanup()
        self.report['elapsed_seconds'] = round(time.monotonic() - self.start, 2)
        self.report['owned_emulator_stopped'] = self.proc is None or self.proc.poll() is not None
        self.save('result.json', self.report)
        files = sorted(p for p in self.out.rglob('*') if p.is_file() and p.name != 'SHA256SUMS')
        (self.out / 'SHA256SUMS').write_text(''.join(digest(p) + '  ' + str(p.relative_to(self.out)) + '\n' for p in files))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True, help='New directory; never overwritten')
    parser.add_argument('--fixture', type=Path, default=HERE / 'v0.3.33.json')
    parser.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', '')))
    parser.add_argument('--image', default='system-images;android-35;google_apis_ps16k;' +
                        ('arm64-v8a' if platform.machine() in ('arm64', 'aarch64') else 'x86_64'))
    parser.add_argument('--build-tools', default='35.0.0')
    parser.add_argument('--port', type=int, default=5584)
    parser.add_argument('--timeout', type=int, default=1800)
    args = parser.parse_args()
    parser.error('Use an even emulator port in 5554..5682') if args.port % 2 or not 5554 <= args.port <= 5682 else None
    parser.error('Timeout must be 60..1800 seconds') if not 60 <= args.timeout <= 1800 else None
    runner = Runner(args)
    error = None
    def interrupted(signum, frame):
        raise RuntimeError(f'Interrupted by signal {signum}')
    signal.signal(signal.SIGTERM, interrupted)
    try:
        runner.run()
    except (Exception, KeyboardInterrupt) as exc:
        error = exc
        print(f'FAIL at {runner.stage}: {exc}', file=sys.stderr)
    finally:
        runner.finish(error)
    return 1 if error else 0


if __name__ == '__main__':
    sys.exit(main())
