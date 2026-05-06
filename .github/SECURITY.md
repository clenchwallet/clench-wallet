# Security Policy

## Reporting a Vulnerability

**Please do NOT open a public GitHub issue for security vulnerabilities.**

If you discover a security vulnerability in Clench Wallet, please report it responsibly by emailing:

📧 **security@clench.net**

### What to include

- Description of the vulnerability
- Steps to reproduce
- Potential impact
- Suggested fix (if you have one)

### What to expect

- **Acknowledgment** within 48 hours
- **Assessment** within 1 week
- **Fix or mitigation plan** communicated to you before public disclosure
- **Credit** in the release notes (unless you prefer to remain anonymous)

## Scope

The following are in scope:
- Key management and storage vulnerabilities
- Transaction building / signing flaws
- PSBT validation bypasses
- Address substitution attacks
- Network privacy leaks (unintended data exposure)
- Authentication bypasses (biometric, PIN)
- Database encryption weaknesses
- Release signing, release-artifact verification, and dependency-verification weaknesses

Security review docs:
- `docs/security/threat-model.md`
- `docs/security/audit-path.md`
- `docs/release/signed-release-verification.md`

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.3.x   | ✅ Current |
| < 0.3   | ❌ Not supported |

## Disclosure Policy

We follow coordinated disclosure. We ask that you:

1. Give us reasonable time to fix the issue before public disclosure
2. Do not exploit the vulnerability beyond what is necessary to demonstrate it
3. Do not access, modify, or delete other users' data

We commit to not pursuing legal action against researchers who follow this policy.
