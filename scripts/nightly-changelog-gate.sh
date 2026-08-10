#!/usr/bin/env bash
# Nightly changelog gate: decides whether the Nightly build must run.
#
# Usage:
#   nightly-changelog-gate.sh <changelog-path> [nightly-ref]
#
#   changelog-path  Path to CHANGELOG.md relative to the repository root.
#   nightly-ref     Ref of the last successful Nightly baseline (e.g.
#                   "nightly"). Omit it (or when the ref does not exist) to
#                   fall back to first-nightly semantics.
#
# Semantics:
#   - With an existing nightly-ref: has_changes = true when the real Git diff
#     of CHANGELOG.md against that ref is non-empty. Additions, modifications
#     and pure deletions all count - the gate keys on `git diff --quiet`, not
#     on extracted added lines.
#   - Without a nightly-ref (first Nightly): has_changes = true when the
#     [Unreleased] section has non-blank content. An empty section must not
#     trigger the first build.
#   - A genuine Git failure (exit code > 1 from `git diff`) is never reported
#     as "has changes" or "no changes": the script prints the failure to
#     stderr and exits non-zero so the workflow step fails.
#
# Output: exactly "true" or "false" on stdout. Exit code 0 on success,
# non-zero on Git failure.
#
# No external dependencies (git + awk only), no repository writes.

set -euo pipefail

if [ "$#" -lt 1 ]; then
    echo "usage: nightly-changelog-gate.sh <changelog-path> [nightly-ref]" >&2
    exit 2
fi
CHANGELOG_PATH="$1"
NIGHTLY_REF="${2:-}"

if [ -n "$NIGHTLY_REF" ]; then
    if ! git rev-parse --verify "${NIGHTLY_REF}^{commit}" >/dev/null 2>&1; then
        # Baseline ref vanished (or this is the first Nightly): fall back to
        # the first-nightly [Unreleased] semantics below.
        NIGHTLY_REF=""
    fi
fi

if [ -n "$NIGHTLY_REF" ]; then
    diff_status=0
    git diff --quiet "$NIGHTLY_REF" -- "$CHANGELOG_PATH" || diff_status=$?
    case "$diff_status" in
        0)
            echo "false"
            ;;
        1)
            echo "true"
            ;;
        *)
            echo "git diff failed with exit code $diff_status" >&2
            exit "$diff_status"
            ;;
    esac
    exit 0
fi

# First Nightly: [Unreleased] section with non-blank content triggers the
# build. Pure blank lines inside the section never count as a change.
if awk '
    /^## \[Unreleased\]/ { found=1; next }
    /^## \[/ { if (found) exit }
    found && NF { print }
' "$CHANGELOG_PATH" | grep -q .; then
    echo "true"
else
    echo "false"
fi
