#!/usr/bin/env python3
"""Focused release-mode startup reentry acceptance; separate from pinned #101."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'strict-16kb'))
from run import (Runner, require, digest, PACKAGE, COMPONENT, PROPS, HERE, REPO,
                 argparse, hashlib, json, os, platform, re, signal, socket,
                 subprocess, tempfile, time)

REQUIRED = ['artifact', 'static', 'environment', 'installed',
            'create_onboarding', 'created', 'created_launcher', 'created_explicit',
            'import_onboarding', 'imported', 'imported_explicit', 'imported_launcher',
            'imported_restart', 'final']

class ReentryRunner(Runner):
    def __init__(self, args):
        super().__init__(args)
        self.save('scenario-source.py', Path(__file__).read_bytes())
        self.report['scenario'] = 'fresh onboarding before cold restart; launcher and explicit intent'
        self.report['test_signed_release_candidate'] = True

    def setup(self):
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

    def enter(self, mode):
        self.environment()
        self.offline()
        if mode == 'launcher':
            # Launcher-equivalent MAIN/LAUNCHER plus NEW_TASK|RESET_TASK_IF_NEEDED.
            # Resolve the normal entry rather than assuming an internal Activity.
            resolved = self.shell('cmd', 'package', 'resolve-activity', '--brief',
                                  '-a', 'android.intent.action.MAIN',
                                  '-c', 'android.intent.category.LAUNCHER', PACKAGE)
            require(resolved.splitlines()[-1] == COMPONENT, 'Unexpected launcher entry')
            args = ['-a', 'android.intent.action.MAIN', '-c',
                    'android.intent.category.LAUNCHER', '-f', '0x10200000', '-n', COMPONENT]
        else:
            require(mode == 'explicit', 'Unknown launch mode')
            args = ['-n', COMPONENT]  # Exact original run03/run04 invocation.
        self.a('shell', 'am', 'start', '-W', *args,
               diagnostics='lifecycle/' + self.stage + '-launch')

    def activity(self, suffix, expected_state):
        data = self.a('shell', 'dumpsys', 'activity', 'activities')
        self.save('lifecycle/' + self.stage + '-' + suffix + '.txt', data)
        text = data.decode()
        match = re.search(r'\* Hist\s+#\d+: ActivityRecord\{([^}]+net\.clench\.wallet/\.ui\.MainActivity[^}]+)\}(.*?)(?=\n\s*\* Hist|\n\s*\* Task|\Z)', text, re.S)
        require(match, 'MainActivity record missing')
        require('state=' + expected_state in match.group(2), 'MainActivity is not ' + expected_state)
        pid = self.shell('pidof', PACKAGE)
        require(pid and ' ' not in pid, 'Expected one app process')
        proc = self.shell('cat', '/proc/' + pid + '/stat')
        return {'pid': pid, 'proc_stat': proc, 'activity_record': match.group(1),
                'task_id': re.search(r' t(\d+)', match.group(1)).group(1),
                'state': expected_state}

    def reopen(self, prefix, name, address, mode):
        self.stage = prefix + '_' + mode
        before = self.activity('before', 'RESUMED')
        self.shell('input', 'keyevent', 'KEYCODE_HOME')
        self.wait_ui(lambda t: any(n.get('package') == 'com.google.android.apps.nexuslauncher'
                                  for n in t.iter('node')) and
                     not any(n.get('package') == PACKAGE for n in t.iter('node')),
                     'actual launcher after HOME')
        background = self.activity('background', 'STOPPED')
        require(before['pid'] == background['pid'], 'Process replaced before reentry')
        self.enter(mode)
        # Stop at network-choice immediately; do not poll past the original failure.
        tree = self.wait_ui(lambda t: bool(self.nodes(t, 'Receive') or self.nodes(t, 'Testnet')),
                            'wallet route or network choice')
        require(not self.nodes(tree, 'Testnet'), 'Reentry routed to network choice')
        self.identity(name, address)
        after = self.activity('after', 'RESUMED')
        require(before['pid'] == after['pid'], 'Process replaced during foreground reentry')
        require(before['activity_record'] == background['activity_record'] == after['activity_record'],
                'Activity/task identity changed during reentry')
        self.step(self.stage, wallet_name=name, address=address, launch_mode=mode,
                  before=before, background=background, after=after,
                  force_stop_has_occurred=False)

    def onboard(self, prefix):
        self.stage = prefix + '_onboarding'
        self.enter('launcher')
        for label in ('Testnet', 'Offline mode', 'Continue in Offline Mode', 'Continue'):
            self.click(label)
        self.visible('Create New Wallet')
        self.step(self.stage, network='Testnet', app_mode='Offline', lock='default no lock')

    def run(self):
        self.setup()
        self.onboard('create')
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
        self.reopen('created', 'My Wallet', created, 'launcher')
        self.reopen('created', 'My Wallet', created, 'explicit')

        # A fresh import must evaluate pre-wallet startup state, not the answer
        # from the previously created wallet. Clear only this owned emulator app.
        self.stage = 'clear_for_fresh_import'
        cleared = self.shell('pm', 'clear', PACKAGE)
        require(cleared == 'Success', 'Disposable app clear failed')
        self.save('fresh-import-clear.txt', cleared.encode())
        self.onboard('import')
        self.stage = 'imported'
        self.click('Import Existing Wallet')
        self.edit('Reentry-Import')
        words = self.fixture['mnemonic'].split()
        require(words == ['abandon'] * 11 + ['about'], 'Unsupported public mnemonic')
        for word in words:
            self.click('A')
            self.click(word)
        self.click('Import Wallet')
        imported = self.identity('Reentry-Import', self.fixture['imported_address'])
        require(created != imported, 'Created/imported addresses unexpectedly equal')
        self.step('imported', wallet_name='Reentry-Import', address=imported)
        self.reopen('imported', 'Reentry-Import', imported, 'explicit')
        self.reopen('imported', 'Reentry-Import', imported, 'launcher')

        # The only am force-stop occurs after both immediate foreground scenarios.
        self.stage = 'imported_restart'
        before = self.activity('before', 'RESUMED')
        self.shell('am', 'force-stop', PACKAGE)
        require(not self.shell('pidof', PACKAGE, check=False), 'Process survived force-stop')
        self.enter('launcher')
        self.visible('Receive')
        self.identity('Reentry-Import', imported)
        after = self.activity('after', 'RESUMED')
        require(before['pid'] != after['pid'], 'Force-stop did not replace process')
        self.step(self.stage, wallet_name='Reentry-Import', address=imported, before=before, after=after)
        self.stage = 'final'
        self.environment()
        self.offline()
        crash = self.a('logcat', '-b', 'crash', '-d')
        self.save('crash.txt', crash)
        require(not crash.strip(), 'Non-empty crash buffer')
        self.save('logcat.txt', self.a('logcat', '-d', '-v', 'threadtime', '-t', '4000'))
        self.step('final', crash_buffer_empty=True)
        require([s['name'] for s in self.report['steps']] == REQUIRED and
                all(s['status'] == 'PASS' for s in self.report['steps']), 'Missing scenario steps')
        self.report['status'] = 'PASS'

    def finish(self, error=None):
        if error and self.proc and self.proc.poll() is None:
            self.deadline = time.monotonic() + 20
            try:
                self.save('first-failure-activities.txt', self.a('shell', 'dumpsys', 'activity', 'activities'))
                self.save('first-failure-screen.png', self.a('exec-out', 'screencap', '-p'))
            except Exception:
                pass
        super().finish(error)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--image', default='system-images;android-35;google_apis_ps16k;arm64-v8a')
    parser.add_argument('--build-tools', default='35.0.0')
    parser.add_argument('--port', type=int, default=5590)
    parser.add_argument('--timeout', type=int, default=900)
    args = parser.parse_args()
    parser.error('Use an even port in 5554..5682') if args.port % 2 or not 5554 <= args.port <= 5682 else None
    parser.error('Timeout must be 60..1800') if not 60 <= args.timeout <= 1800 else None
    runner = ReentryRunner(args)
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
