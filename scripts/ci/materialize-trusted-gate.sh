#!/usr/bin/env bash
# 从 trusted base 物化可信 gate bundle（CI 共享实现）。
#
# 用法：
#   bash scripts/ci/materialize-trusted-gate.sh \
#     --repo-root <path> --base <sha> \
#     --output <dir> --index <file> --paths-file <file> \
#     --paths 'scripts/i18n scripts/hooks ...'
#
# 语义（与 .github/workflows/quality-gate.yml 的 bootstrap shell 完全一致）：
# - git ls-tree -r --name-only <base> -- <paths> > paths-file
# - test -s paths-file（空 → 立即失败，不得让空目录一路执行到 node/bash 才报模糊错误）
# - 独立临时 index：GIT_INDEX_FILE=<index> git read-tree <base>
# - GIT_INDEX_FILE=<index> git -c core.autocrlf=false checkout-index --stdin --prefix=<output>/ < paths-file
# - 物化后立即验证 scripts/i18n/check.mjs 与 scripts/hooks/pre-push-guard.sh 存在
# - 当前执行中 verifier 的 gate-policy.minimumTrustedVerifier 是强制合同；trusted base 的
#   contract / schema / required files 必须满足它，缺任何一项 → FAIL CLOSED。
# 失败时以非零退出，绝不静默继续。
set -euo pipefail

repo_root=""
base=""
output=""
index_file=""
paths_file=""
paths=()

while [ $# -gt 0 ]; do
    case "$1" in
        --repo-root) repo_root="${2:-}"; shift 2 ;;
        --base) base="${2:-}"; shift 2 ;;
        --output) output="${2:-}"; shift 2 ;;
        --index) index_file="${2:-}"; shift 2 ;;
        --paths-file) paths_file="${2:-}"; shift 2 ;;
        --paths)
            paths=(${2:-}); shift 2 ;;
        *)
            echo "materialize-trusted-gate: unknown argument: $1" >&2
            exit 2
            ;;
    esac
done

if [ -z "$repo_root" ] || [ -z "$base" ] || [ -z "$output" ] \
    || [ -z "$index_file" ] || [ -z "$paths_file" ] || [ ${#paths[@]} -eq 0 ]; then
    echo "materialize-trusted-gate: --repo-root/--base/--output/--index/--paths-file/--paths are required" >&2
    exit 2
fi
if ! [[ "$base" =~ ^[0-9a-f]{40}$ ]]; then
    echo "materialize-trusted-gate: base $base is not a full 40-char commit sha" >&2
    exit 2
fi

mkdir -p "$output"
rm -f "$index_file"
rm -f "$paths_file"

git -C "$repo_root" ls-tree -r --name-only "$base" -- "${paths[@]}" > "$paths_file"
test -s "$paths_file" || {
    echo "materialize-trusted-gate: trusted base $base has no gate paths (${paths[*]}); fail closed" >&2
    exit 1
}

GIT_INDEX_FILE="$index_file" git -C "$repo_root" read-tree "$base"

GIT_INDEX_FILE="$index_file" git -C "$repo_root" -c core.autocrlf=false checkout-index \
    --stdin \
    --prefix="$output/" \
    < "$paths_file"

test -f "$output/scripts/i18n/check.mjs" || {
    echo "materialize-trusted-gate: output is missing scripts/i18n/check.mjs (empty materialization?); fail closed" >&2
    exit 1
}
test -f "$output/scripts/hooks/pre-push-guard.sh" || {
    echo "materialize-trusted-gate: output is missing scripts/hooks/pre-push-guard.sh; fail closed" >&2
    exit 1
}
# 核心 policy / contract / library 必须存在；额外 baseline 由执行中 policy 声明。
for rel in \
    scripts/i18n/gate-contract.mjs \
    scripts/i18n/gate-policy.json \
    scripts/i18n/lib/trusted-gate.mjs; do
    test -f "$output/$rel" || {
        echo "materialize-trusted-gate: trusted base $base does not satisfy the current Gate Epoch 2 verifier baseline (missing $rel); fail closed" >&2
        exit 1
    }
done
node -e 'const fs=require("fs"),path=require("path"),source=process.argv[1],base=process.argv[2],p=JSON.parse(fs.readFileSync(path.join(source,"scripts/i18n/gate-policy.json"),"utf8")),b=JSON.parse(fs.readFileSync(path.join(base,"scripts/i18n/gate-policy.json"),"utf8")),m=p.minimumTrustedVerifier;if(!m||!Number.isInteger(m.contractVersion)||!Number.isInteger(m.schemaVersion)||!Array.isArray(m.requiredFiles)||!m.requiredFiles.length)throw new Error("invalid minimumTrustedVerifier");if(!Number.isInteger(b.contractVersion)||b.contractVersion<m.contractVersion||!Number.isInteger(b.schemaVersion)||b.schemaVersion<m.schemaVersion)throw new Error("trusted verifier policy is below minimumTrustedVerifier");for(const rel of m.requiredFiles){if(typeof rel!=="string"||!fs.existsSync(path.join(base,...rel.split("/"))))throw new Error("missing "+rel)}' "$repo_root" "$output" || {
    echo "materialize-trusted-gate: trusted base $base does not satisfy the executing policy.minimumTrustedVerifier; fail closed" >&2
    exit 1
}
echo "materialize-trusted-gate: materialized $(wc -l < "$paths_file" | tr -d ' ') path(s) from $base into $output"
