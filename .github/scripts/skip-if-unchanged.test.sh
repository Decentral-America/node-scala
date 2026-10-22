#!/usr/bin/env bash
# Unit test for skip-if-unchanged.sh using a stub `gh`. Run: .github/scripts/skip-if-unchanged.test.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

# stub gh: prints $STUB_RESPONSE, or fails when STUB_FAIL=1
cat > "$tmp/gh" <<'STUB'
#!/usr/bin/env bash
[ "${STUB_FAIL:-0}" = 1 ] && exit 1
printf '%s' "${STUB_RESPONSE:-}"
STUB
chmod +x "$tmp/gh"

fail=0
check() { # name expected-run event response [fail] [maxage]
  local name=$1 want=$2 event=$3 resp=$4 sf=${5:-0} maxage=${6:-0}
  : > "$tmp/out"
  GH_BIN="$tmp/gh" GITHUB_OUTPUT="$tmp/out" GITHUB_REPOSITORY=o/r GITHUB_SHA=aaaaaaaaaaaaaaaa \
    GITHUB_EVENT_NAME="$event" STUB_RESPONSE="$resp" STUB_FAIL="$sf" NOW_EPOCH=1760000000 \
    "$here/skip-if-unchanged.sh" wf.yml "$maxage" > /dev/null
  got=$(sed -n 's/^run=//p' "$tmp/out")
  if [ "$got" = "$want" ]; then echo "ok   - $name"; else echo "FAIL - $name (want run=$want, got run=$got)"; fail=1; fi
}

# 1760000000 = 2025-10-09T08:53:20Z
check "same sha on schedule is skipped"          false schedule "aaaaaaaaaaaaaaaa 2025-10-08T03:00:00Z"
check "new commit runs"                           true  schedule "bbbbbbbbbbbbbbbb 2025-10-08T03:00:00Z"
check "no previous success runs"                  true  schedule ""
check "api failure fails open"                    true  schedule "" 1
check "workflow_dispatch always runs"             true  workflow_dispatch "aaaaaaaaaaaaaaaa 2025-10-08T03:00:00Z"
check "same sha but stale beyond max-age runs"    true  schedule "aaaaaaaaaaaaaaaa 2025-09-01T03:00:00Z" 0 28
check "same sha within max-age is skipped"        false schedule "aaaaaaaaaaaaaaaa 2025-10-01T03:00:00Z" 0 28
exit $fail
