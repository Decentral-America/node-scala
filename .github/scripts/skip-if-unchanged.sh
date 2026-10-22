#!/usr/bin/env bash
# Decide whether a SCHEDULED workflow needs to run, or can be skipped because the code it exercises is
# byte-identical to what the last successful run already verified.
#
#   skip-if-unchanged.sh <workflow-file> [max-age-days]
#
# Env: GITHUB_REPOSITORY, GITHUB_SHA, GITHUB_EVENT_NAME, GITHUB_OUTPUT (all provided by Actions), GH_TOKEN.
# Writes `run=true|false` and `reason=...` to $GITHUB_OUTPUT (stdout when unset).
#
# Rules (first match wins):
#   - anything other than a `schedule` event (workflow_dispatch, ...) always runs: a human asked for it.
#   - no previous successful scheduled run, or the API lookup fails -> run (fail open: never lose a signal
#     because of a lookup problem).
#   - last successful scheduled run tested a different commit than HEAD -> run.
#   - same commit, but it was first verified more than max-age-days ago (when given) -> run (periodic
#     refresh for checks that also depend on something outside the repo, e.g. a live network). Age is
#     measured from the EARLIEST successful scheduled run of that commit, because a skipped run is itself
#     reported as a success and would otherwise keep resetting the clock.
#   - otherwise skip.
# Set GH_BIN to override the gh executable (used by the unit test).
set -euo pipefail

WORKFLOW="${1:?usage: skip-if-unchanged.sh <workflow-file> [max-age-days]}"
MAX_AGE_DAYS="${2:-0}"
GH="${GH_BIN:-gh}"
OUT="${GITHUB_OUTPUT:-/dev/stdout}"

emit() {
  { echo "run=$1"; echo "reason=$2"; } >> "$OUT"
  echo "skip-if-unchanged: run=$1 ($2)"
}

if [ "${GITHUB_EVENT_NAME:-}" != "schedule" ]; then
  emit true "event ${GITHUB_EVENT_NAME:-unknown} is not a scheduled run"
  exit 0
fi

# shellcheck disable=SC2016  # the $s below is a jq variable, not a shell expansion
if ! last=$("$GH" api --method GET "repos/${GITHUB_REPOSITORY}/actions/workflows/${WORKFLOW}/runs" \
      -f event=schedule -f status=success -f per_page=30 \
      --jq '.workflow_runs | select(length > 0) | .[0].head_sha as $s | "\($s) \([.[] | select(.head_sha == $s)] | last | .created_at)"' 2>/dev/null); then
  emit true "could not query previous runs"
  exit 0
fi

if [ -z "$last" ]; then
  emit true "no previous successful scheduled run"
  exit 0
fi

last_sha="${last%% *}"
last_at="${last#* }"

if [ "$last_sha" != "$GITHUB_SHA" ]; then
  emit true "commit changed since last success (${last_sha:0:10} -> ${GITHUB_SHA:0:10})"
  exit 0
fi

if [ "$MAX_AGE_DAYS" -gt 0 ]; then
  # GNU date on the runner, BSD date locally (the unit test passes NOW_EPOCH to stay portable).
  last_epoch=$(date -u -d "$last_at" +%s 2>/dev/null || date -u -j -f '%Y-%m-%dT%H:%M:%SZ' "$last_at" +%s)
  now_epoch="${NOW_EPOCH:-$(date -u +%s)}"
  age_days=$(( (now_epoch - last_epoch) / 86400 ))
  if [ "$age_days" -ge "$MAX_AGE_DAYS" ]; then
    emit true "same commit but last success is ${age_days}d old (refresh every ${MAX_AGE_DAYS}d)"
    exit 0
  fi
fi

emit false "commit ${GITHUB_SHA:0:10} already verified by the last successful scheduled run"
