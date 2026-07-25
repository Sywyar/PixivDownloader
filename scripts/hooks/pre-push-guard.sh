#!/usr/bin/env bash
# Guards this repository against reintroducing reverse-engineered
# Douyin anti-crawl signature implementations: the X-Bogus / a_bogus signers,
# the SM3 helper, the fabricated Chrome browser fingerprint, and the custom
# signing alphabets. This repository ships only the signing *seam*
# (DouyinSignedUriBuilder) with a non-functional stub signer; a real signer
# must be supplied out-of-tree (for example from a private plugin build) and
# never committed to this repository.
#
# Shared by the local pre-push hook (scripts/hooks/pre-push) and the CI
# quality-gate workflow (.github/workflows/quality-gate.yml). Activate the
# local hook with:  git config core.hooksPath scripts/hooks
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cd "$root"

# Markers unique to the removed crack code. They have no legitimate use
# elsewhere in the repository, so any match is a reintroduction.
marker_re='DouyinXBogusSigner|DouyinABogusSigner|DouyinSm3|generateChromeFingerprint|Dkdpgh4ZKs|Dkdpgh2Zms|ckdp1h4ZKs'

guard_rel="scripts/hooks/pre-push-guard.sh"

matches="$(git grep -nE "$marker_re" -- . ":(exclude)$guard_rel" 2>/dev/null || true)"

if [ -n "$matches" ]; then
  echo "ERROR: detected reverse-engineered Douyin signature code in this repository." >&2
  echo "       This repository must not ship X-Bogus / a_bogus / msToken implementations" >&2
  echo "       or fabricated browser fingerprints. Supply a real signer out-of-tree via" >&2
  echo "       DouyinSignedUriBuilder's signer seam instead." >&2
  echo "" >&2
  echo "$matches" >&2
  exit 1
fi

echo "signature-guard: no reverse-engineered Douyin signature code detected."
