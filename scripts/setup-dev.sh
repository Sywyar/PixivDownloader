#!/usr/bin/env bash
# setup-dev.sh — 开发环境初始化（POSIX shell 版本；Windows 用 setup-dev.ps1）。
# 执行：工具版本检查 → hooks 安装 → hooks doctor → i18n 测试 → i18n 全量检查。
# 不修改 global Git 配置，也不在 git clone 时自动执行。
# 通过脚本自身位置定位仓库根，任意当前目录执行均可。
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

ok=true

echo "[setup-dev] repo root: $root"
echo "[setup-dev] checking tool versions..."
for tool in git node npm java javac; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "[setup-dev] ERROR: $tool not found in PATH" >&2
        ok=false
    fi
done
if [ "$ok" = true ]; then
    node_major="$(node --version | sed 's/^v//' | cut -d. -f1)"
    if [ "$node_major" -lt 18 ]; then
        echo "[setup-dev] ERROR: Node.js 18+ required (CI uses 24), found $(node --version)" >&2
        ok=false
    else
        echo "[setup-dev] git: $(git --version)"
        echo "[setup-dev] node: $(node --version)"
        echo "[setup-dev] npm: $(npm --version)"
        echo "[setup-dev] java: $(java -version 2>&1 | head -n1)"
        echo "[setup-dev] javac: $(javac -version 2>&1 | head -n1)"
    fi
fi

if [ "$ok" = true ]; then
    echo "[setup-dev] installing git hooks (local config)..."
    node scripts/i18n/install-hooks.mjs
fi
if [ "$ok" = true ]; then
    echo "[setup-dev] verifying hooks config..."
    node scripts/i18n/doctor-hooks.mjs
fi
if [ "$ok" = true ]; then
    echo "[setup-dev] running i18n tests..."
    npm run test:i18n
fi
if [ "$ok" = true ]; then
    echo "[setup-dev] running full i18n check..."
    npm run i18n:check
fi

if [ "$ok" = true ]; then
    echo "[setup-dev] done: hooks installed and i18n gate passed."
    exit 0
fi
echo "[setup-dev] FAILED: fix the errors above and re-run this script." >&2
exit 1
