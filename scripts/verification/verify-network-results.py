#!/usr/bin/env python3
"""Fail closed unless all nine Android network regressions passed exactly once."""
import argparse
from collections import Counter
from pathlib import Path
import re
import xml.etree.ElementTree as ET

CLASS = 'net.clench.wallet.data.network.NetworkTrustAuditTest'
EXPECTED = {
    'pinnedExpectedLeafWorksDirectAndThroughSocks',
    'pinnedWrongLeafIsRejectedDirectAndThroughSocks',
    'pinnedWrongHostnameIsRejectedDirectAndThroughSocks',
    'expiredPinnedLeafIsRejectedIncludingSelfSignedAnchor',
    'platformTrustRejectsUnknownCaDirectAndThroughSocks',
    'pinIsExactCertificateNotPermissionForAnotherLeaf',
    'httpPlatformChainExpiryHostnameAndProxyDns',
    'unavailableSocksCannotFallBackToDirectElectrum',
    'offlineInterruptsTlsHandshakeAndFreshConnectionRecovers',
}


def verify(directory):
    source = Path(__file__).resolve().parents[2] / 'app/src/androidTest/java/net/clench/wallet/data/network/NetworkTrustAuditTest.kt'
    declared = re.findall(r'@Test\s+fun\s+(\w+)\s*\(', source.read_text())
    if Counter(declared) != Counter(EXPECTED):
        raise ValueError('Network test source and expected contract differ')
    seen = Counter()
    for file in Path(directory).rglob('*.xml'):
        root = ET.parse(file).getroot()
        for suite in root.iter('testsuite'):
            for field in ('failures', 'errors', 'skipped', 'disabled'):
                if int(suite.get(field, '0')) != 0:
                    raise ValueError(f'{file}: nonzero {field}')
        for case in root.iter('testcase'):
            if case.find('failure') is not None or case.find('error') is not None or case.find('skipped') is not None:
                raise ValueError(f'{file}: unsuccessful testcase')
            if case.get('classname') != CLASS:
                raise ValueError(f'{file}: unexpected testcase class')
            seen[case.get('name')] += 1
    if seen != Counter(EXPECTED):
        raise ValueError(f'Expected each of nine network tests once; got {dict(seen)}')
    print('PASS: exact nine Android network tests, no failures/errors/skips')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('results')
    verify(parser.parse_args().results)
