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
- **Secondary contact:** josue.rojas@sdbullion.com
- **PGP:** Available on request via the above addresses.

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

- Non-root user (`dcc`, UID 999) at runtime.
- `no-new-privileges: true` — privilege escalation is blocked at the kernel level.
- All Linux capabilities dropped (`cap_drop: ALL`); none re-added.
- Wallet seed injected via environment variable; written to a `chmod 600`
  temporary file at startup and removed on exit via `trap`.
- AmazonCorrettoCryptoProvider (ACCP) loaded for hardware-accelerated crypto.
- HTTPS enforced for all outbound API calls in production configurations.
- REST API (port 6869) is localhost-only in the Docker Compose configuration —
  never expose it to the internet without authentication.

### Kubernetes (LKE) hardening (gen/val nodes)

Gen and validator nodes run on LKE (not public-facing) with additional pod-level
security context:

- `fsGroup` set to restrict filesystem ownership.
- `allowPrivilegeEscalation: false`.
- All capabilities dropped at pod level.

## API key hashing algorithm

The node's REST API uses a **double hash**: `secureHash = Keccak256(Blake2b256(key))`,
then base58-encoded. This is **not SHA-256**. Always use the node's own utility to
generate the hash for a new API key:

```bash
curl -s -X POST http://localhost:6869/utils/hash/secure \
  -H "Content-Type: application/json" \
  -d '{"message":"YOUR_NEW_KEY"}' \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['hash'])"
```

## Known patched vulnerabilities

| CVE | Component | Status |
|-----|-----------|--------|
| CVE-2026-44249 | node-scala Docker image | ✅ Patched in current image |

## Dependency scanning

This repository uses:

- **GitHub Dependabot** for automated dependency updates.
- **GitHub Dependency Review** on every pull request.
- **Trivy** (container image scanning) on every Docker build.
- **SBOM** (SPDX format) generated and attached to every release via
  `anchore/sbom-action`.

---

_Last reviewed: 2026-06-30_
