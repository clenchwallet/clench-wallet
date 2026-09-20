#!/usr/bin/env python3
"""Hostile fixtures for the Android network-test acceptance contract."""
import importlib.util
from pathlib import Path
import tempfile
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('network_contract', Path(__file__).with_name('verify-network-results.py'))
contract = importlib.util.module_from_spec(spec)
spec.loader.exec_module(contract)

def suite():
    root = ET.Element('testsuite', tests='9', failures='0', errors='0', skipped='0')
    for name in sorted(contract.EXPECTED):
        ET.SubElement(root, 'testcase', classname=contract.CLASS, name=name)
    return root

def run(root, accepted):
    with tempfile.TemporaryDirectory() as directory:
        if root is not None:
            ET.ElementTree(root).write(Path(directory) / 'TEST-network.xml')
        try:
            contract.verify(directory)
        except ValueError:
            if accepted: raise
        else:
            if not accepted: raise AssertionError('Invalid evidence accepted')

run(suite(), True)
run(None, False)
r=suite(); r.remove(r[0]); run(r, False)
r=suite(); ET.SubElement(r, 'testcase', dict(r[0].attrib)); run(r, False)
for kind in ('failure', 'error', 'skipped'):
    r=suite(); ET.SubElement(r[0],kind); run(r,False)
for attr in ('failures', 'errors', 'skipped', 'disabled'):
    r=suite(); r.set(attr,'1'); run(r,False)
r=suite(); r[0].set('classname','unexpected.Class'); run(r,False)
r=suite(); r[0].set('name','unexpectedMethod'); run(r,False)
print('PASS: positive evidence and 12 hostile/missing-evidence cases')
