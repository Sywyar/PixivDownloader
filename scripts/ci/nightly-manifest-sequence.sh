#!/usr/bin/env bash
# Derive a monotonic Nightly manifest sequence from a workflow run and attempt.

set -euo pipefail

if [ "$#" -ne 2 ]; then
    echo "usage: nightly-manifest-sequence.sh <run-id> <run-attempt>" >&2
    exit 2
fi

RUN_ID="$1"
RUN_ATTEMPT="$2"
if ! [[ "$RUN_ID" =~ ^[1-9][0-9]*$ ]] || ! [[ "$RUN_ATTEMPT" =~ ^[1-9][0-9]*$ ]]; then
    echo "run id and attempt must be positive decimal integers" >&2
    exit 2
fi
if [ "${#RUN_ID}" -gt 13 ] || [ "${#RUN_ATTEMPT}" -gt 3 ]; then
    echo "run id or attempt is outside the supported range" >&2
    exit 2
fi

run_id=$((10#$RUN_ID))
run_attempt=$((10#$RUN_ATTEMPT))
if [ "$run_id" -gt 9007199254739 ] || [ "$run_attempt" -gt 999 ]; then
    echo "run id or attempt is outside the supported range" >&2
    exit 2
fi

printf '%d\n' "$((run_id * 1000 + run_attempt))"
