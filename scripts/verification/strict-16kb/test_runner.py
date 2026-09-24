"""Harness contract tests; these never stand in for Android runtime acceptance."""
import importlib.util
from pathlib import Path
import tempfile
import subprocess
import sys
from types import SimpleNamespace
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('strict16', Path(__file__).with_name('run.py'))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class Contracts(unittest.TestCase):
    def test_strict_environment_rejects_each_wrong_condition(self):
        good = dict(page_size=16384, api=35, abi='arm64-v8a', qemu='1', fallback=runner.PROPS.copy())
        runner.validate_environment(good)
        for key, value in [('page_size', 4096), ('api', 34), ('qemu', '0'), ('abi', 'armeabi-v7a'),
                           ('fallback', {}), ('fallback', {**runner.PROPS, 'bionic.linker.16kb.app_compat.enabled': 'true'}),
                           ('fallback', {**runner.PROPS, 'pm.16kb.app_compat.disabled': 'false'})]:
            with self.subTest(key=key, value=value), self.assertRaises(RuntimeError):
                runner.validate_environment({**good, key: value})

    def test_observed_address_is_required_unique_and_testnet(self):
        address = 'tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl'
        tree = ET.fromstring(f'<hierarchy><node text="{address}"/></hierarchy>')
        self.assertEqual(runner.address_from(tree), address)
        for body in ['', '<node text="bc1qexample"/>',
                     f'<node text="{address}"/><node text="tb1qaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"/>']:
            with self.subTest(body=body), self.assertRaises(RuntimeError):
                runner.address_from(ET.fromstring('<hierarchy>' + body + '</hierarchy>'))

    def test_click_resolves_action_not_duplicate_heading(self):
        tree = ET.fromstring('<hierarchy><node clickable="false" text="Import Wallet"/>'
                             '<node clickable="true"><node text="Import Wallet"/></node></hierarchy>')
        actions = runner.Runner.actions(tree, 'Import Wallet')
        self.assertEqual(len(actions), 1)
        self.assertEqual(actions[0].get('clickable'), 'true')
        self.assertEqual(runner.Runner.actions(tree, 'Missing'), [])

    def test_completion_requires_all_steps_in_order(self):
        steps = [dict(name=name, status='PASS') for name in runner.REQUIRED]
        runner.validate_steps(steps)
        for bad in [steps[:-1], steps[::-1], steps + steps[:1],
                    [dict(name=s['name'], status='FAIL') for s in steps]]:
            with self.assertRaises(RuntimeError):
                runner.validate_steps(bad)

    def test_command_timeout_and_evidence_collision_fail_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = SimpleNamespace(evidence=Path(tmp) / 'run', timeout=60, port=5584, sdk=Path(tmp))
            instance = runner.Runner(args)
            with self.assertRaises(FileExistsError):
                runner.Runner(args)
            with self.assertRaises(subprocess.TimeoutExpired):
                instance.command(sys.executable, '-c', 'import time; time.sleep(3)', timeout=0.05)
            instance.deadline = 0
            with self.assertRaisesRegex(RuntimeError, 'deadline'):
                instance.command(sys.executable, '-c', 'pass')

    def test_hashes_bytes_without_python311_dependency(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / 'data'
            p.write_bytes(b'abc')
            self.assertEqual(runner.digest(p), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')


if __name__ == '__main__':
    unittest.main()
