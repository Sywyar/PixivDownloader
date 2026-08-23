#!/usr/bin/env bash
# Guards this repository against reintroducing reverse-engineered
# Douyin anti-crawl signature implementations: the X-Bogus / a_bogus signers,
# the SM3 helper, the fabricated Chrome browser fingerprint, and the custom
# signing alphabets. This repository ships only the signing *seam*
# (DouyinSignedUriBuilder) with a non-functional stub signer; a real signer
# must be supplied out-of-tree (for example from a private plugin build) and
# never committed to this repository.
#
# Usage:
#   bash scripts/hooks/pre-push-guard.sh                 # scan the worktree
#   bash scripts/hooks/pre-push-guard.sh --ref <sha>     # scan the given commit tree
#   bash scripts/hooks/pre-push-guard.sh --repo-root <path> [--ref <sha>]
#                                                         # scan inside the given repository root
#
# Shared by the local pre-push hook (scripts/hooks/pre-push) and the CI
# quality-gate workflow (.github/workflows/quality-gate.yml). Activate the
# local hook with:  git config core.hooksPath scripts/hooks
set -euo pipefail

root=""
ref=""
while [ $# -gt 0 ]; do
    case "$1" in
        --repo-root)
            root="${2:-}"
            if [ -z "$root" ]; then
                echo "pre-push-guard: --repo-root requires a path" >&2
                exit 2
            fi
            shift 2
            ;;
        --ref)
            ref="${2:-}"
            if [ -z "$ref" ]; then
                echo "pre-push-guard: --ref requires a commit" >&2
                exit 2
            fi
            shift 2
            ;;
        *)
            echo "pre-push-guard: unknown argument: $1" >&2
            exit 2
            ;;
    esac
done

if [ -z "$root" ]; then
    root="$(git rev-parse --show-toplevel)"
fi
if [ ! -d "$root/.git" ] && ! git -C "$root" rev-parse --git-dir >/dev/null 2>&1; then
    echo "pre-push-guard: not inside a git repository: $root" >&2
    exit 1
fi
cd "$root"

# Markers unique to the removed crack code. They have no legitimate use
# elsewhere in the repository, so any match is a reintroduction.
marker_re='DouyinXBogusSigner|DouyinABogusSigner|DouyinSm3|generateChromeFingerprint|Dkdpgh4ZKs|Dkdpgh2Zms|ckdp1h4ZKs'

guard_rel="scripts/hooks/pre-push-guard.sh"
# 守卫回归测试在运行时拼接标记，仍保留精确路径豁免以避免夹具文本误报。
guard_test_rel="scripts/ci/test/hooks.test.mjs"

if [ -n "$ref" ]; then
    matches="$(git grep -nE "$marker_re" "$ref" -- . ":(exclude)$guard_rel" ":(exclude)$guard_test_rel" 2>/dev/null || true)"
else
    matches="$(git grep -nE "$marker_re" -- . ":(exclude)$guard_rel" ":(exclude)$guard_test_rel" 2>/dev/null || true)"
fi

if [ -n "$matches" ]; then
  echo "ERROR: detected reverse-engineered Douyin signature code in this repository." >&2
  echo "       This repository must not ship X-Bogus / a_bogus / msToken implementations" >&2
  echo "       or fabricated browser fingerprints. Supply a real signer out-of-tree via" >&2
  echo "       DouyinSignedUriBuilder's signer seam instead." >&2
  echo "" >&2
  echo "$matches" >&2
  exit 1
fi

if [ -n "$ref" ]; then
    echo "signature-guard: commit $ref has no reverse-engineered Douyin signature code."
else
    echo "signature-guard: no reverse-engineered Douyin signature code detected."
fi
