#!/usr/bin/env python3
"""Generate PUBLIC, disposable TLS test fixtures; never production identities.

Optional regeneration tool (cryptography 46.0.3); not used during app builds.
All issuers are synthetic. No actual compromised private key is used or needed.
"""
import datetime as dt
from pathlib import Path
from ipaddress import ip_address
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

DEST = Path(__file__).resolve().parents[2] / 'app/src/androidTest/assets/network-trust'

def key():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)

def issue(name, public_key, issuer_name, issuer_key, *, ca, serial):
    builder = (x509.CertificateBuilder()
               .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, name)]))
               .issuer_name(issuer_name).public_key(public_key).serial_number(serial)
               .not_valid_before(dt.datetime(2020, 1, 1, tzinfo=dt.timezone.utc))
               .not_valid_after(dt.datetime(2040, 1, 1, tzinfo=dt.timezone.utc))
               .add_extension(x509.BasicConstraints(ca=ca, path_length=None), critical=True)
               .add_extension(x509.KeyUsage(digital_signature=True, content_commitment=False,
                   key_encipherment=not ca, data_encipherment=False, key_agreement=False,
                   key_cert_sign=ca, crl_sign=ca, encipher_only=None, decipher_only=None), critical=True)
               .add_extension(x509.SubjectKeyIdentifier.from_public_key(public_key), critical=False)
               .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(issuer_key.public_key()), critical=False))
    if not ca:
        builder = builder.add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
        builder = builder.add_extension(x509.SubjectAlternativeName([
            x509.DNSName('fixture.invalid'), x509.IPAddress(ip_address('127.0.0.1'))]), critical=False)
    return builder.sign(issuer_key, hashes.SHA256())

def main():
    first_key, other_key, intermediate_key, leaf_key = key(), key(), key(), key()
    first_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'PUBLIC TEST Root A')])
    other_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'PUBLIC TEST Root B')])
    root = issue('PUBLIC TEST Root A', first_key.public_key(), first_name, first_key, ca=True, serial=8001)
    other = issue('PUBLIC TEST Root B', other_key.public_key(), other_name, other_key, ca=True, serial=8002)
    intermediate = issue('PUBLIC TEST Intermediate', intermediate_key.public_key(), root.subject, first_key, ca=True, serial=8003)
    reissued = issue('PUBLIC TEST Intermediate', intermediate_key.public_key(), root.subject, first_key, ca=True, serial=8004)
    cross = issue('PUBLIC TEST Intermediate', intermediate_key.public_key(), other.subject, other_key, ca=True, serial=8005)
    leaf = issue('fixture.invalid', leaf_key.public_key(), intermediate.subject, intermediate_key, ca=False, serial=8006)
    for name, cert in [('path-root', root), ('path-other-root', other), ('path-intermediate', intermediate),
                       ('path-intermediate-reissued', reissued), ('path-intermediate-cross', cross), ('path-leaf', leaf)]:
        (DEST / f'{name}.der').write_bytes(cert.public_bytes(serialization.Encoding.DER))
    # Only this synthetic leaf key is needed by loopback TLS endpoints. Issuer private keys are discarded.
    (DEST / 'path-leaf.key').write_bytes(leaf_key.private_bytes(
        serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    print('Generated six public synthetic certificates and one disposable loopback leaf key.')

if __name__ == '__main__':
    main()
