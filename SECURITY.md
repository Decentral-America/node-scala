# Security Policy

## Supported versions

| Version | Supported |
|---------|-----------|
| 1.6.x (current) | ✅ Active |
| < 1.6.0 | ❌ No longer supported |

## Reporting a vulnerability

**Do not open a public GitHub issue for security vulnerabilities.**

Report vulnerabilities privately to the security team:

- **Email:** security@decentral.exchange  
- **PGP:** Available on request via the above address.

Please include:

1. A clear description of the vulnerability and its potential impact.
2. Steps to reproduce, including any proof-of-concept code.
3. The affected version(s) and component(s).
4. Any suggested mitigations you have identified.

We aim to acknowledge receipt within **48 hours** and provide a full response
within **7 days**. Critical vulnerabilities (CVSS ≥ 9.0) are prioritised for
same-day triage.

## Disclosure policy

We follow **coordinated disclosure**:

1. Vulnerability reported privately.
2. Engineering team confirms and develops a fix.
3. A patched release is prepared and tested.
4. The fix is released and a CVE advisory is published simultaneously.
5. You may publish your own write-up after the advisory is live.

We will credit reporters by name in the release notes unless anonymity is
requested.

## Scope

In scope:

- The DecentralChain node (this repository).
- The Docker image published at `ghcr.io/decentral-america/node-scala`.
- The REST API exposed by the node on port 6869.
- Consensus logic and cryptographic primitives.
- P2P networking protocol implementation.

Out of scope:

- Third-party infrastructure operated by node operators.
- Issues already publicly disclosed in upstream dependencies with no available
  patch (document via Dependabot or GitHub Advisories instead).
- Denial-of-service attacks requiring resources disproportionate to the network
  capacity.

## Security hardening

The node container runs with the following hardening applied:

- Non-root user (`waves`, UID 999) at runtime.
- Wallet seed injected via environment variable; written to a `chmod 600`
  temporary file at startup and removed on exit via `trap`.
- AmazonCorrettoCryptoProvider (ACCP) loaded for hardware-accelerated crypto.
- HTTPS enforced for all outbound API calls in production configurations.
- REST API should be placed behind a reverse proxy in production — never expose
  port 6869 directly to the internet without authentication.

## Dependency scanning

This repository uses:

- **GitHub Dependabot** for automated dependency updates.
- **GitHub Dependency Review** on every pull request.
- **Trivy** (container image scanning) on every Docker build.
- **SBOM** (SPDX format) generated and attached to every release via
  `anchore/sbom-action`.
