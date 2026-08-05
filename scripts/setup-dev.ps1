# setup-dev.ps1 - development environment bootstrap (Windows PowerShell primary)
# Steps: repo root -> tool checks (git/node/npm/java/javac) -> hooks install -> hooks doctor
#        -> i18n tests -> full i18n check.
# Never touches global Git config; never runs automatically on git clone.
# Supports execution from any current directory:
#   & D:\path\PixivDownloader\scripts\setup-dev.ps1
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    function Test-Command([string]$name) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue
        if ($null -eq $cmd) {
            Write-Host "[setup-dev] ERROR: $name is not installed or not in PATH" -ForegroundColor Red
            return $false
        }
        return $true
    }

    $ok = $true

    Write-Host "[setup-dev] repo root: $repoRoot" -ForegroundColor Cyan
    Write-Host "[setup-dev] checking tool versions..." -ForegroundColor Cyan
    foreach ($tool in @('git', 'node', 'npm', 'java', 'javac')) {
        if (-not (Test-Command $tool)) { $ok = $false }
    }
    if ($ok) {
        $nodeMajor = [int](node --version | ForEach-Object { $_ -replace '^v', '' } | ForEach-Object { ($_ -split '\.')[0] })
        if ($nodeMajor -lt 18) {
            Write-Host "[setup-dev] ERROR: Node.js 18+ required (CI uses 24), found $(node --version)" -ForegroundColor Red
            $ok = $false
        } else {
            Write-Host "[setup-dev] git: $(git --version)" -ForegroundColor Green
            Write-Host "[setup-dev] node: $(node --version)" -ForegroundColor Green
            Write-Host "[setup-dev] npm: $(npm --version)" -ForegroundColor Green
            Write-Host "[setup-dev] java: $(java -version 2>&1 | Select-Object -First 1)" -ForegroundColor Green
            Write-Host "[setup-dev] javac: $(javac -version 2>&1 | Select-Object -First 1)" -ForegroundColor Green
        }
    }

    if ($ok) {
        Write-Host "[setup-dev] installing git hooks (local config only)..." -ForegroundColor Cyan
        node scripts/i18n/install-hooks.mjs
        if ($LASTEXITCODE -ne 0) { $ok = $false }
    }

    if ($ok) {
        Write-Host "[setup-dev] verifying hooks config..." -ForegroundColor Cyan
        node scripts/i18n/doctor-hooks.mjs
        if ($LASTEXITCODE -ne 0) { $ok = $false }
    }

    if ($ok) {
        Write-Host "[setup-dev] running i18n tests..." -ForegroundColor Cyan
        npm run test:i18n
        if ($LASTEXITCODE -ne 0) { $ok = $false }
    }

    if ($ok) {
        Write-Host "[setup-dev] running full i18n check..." -ForegroundColor Cyan
        npm run i18n:check
        if ($LASTEXITCODE -ne 0) { $ok = $false }
    }

    if ($ok) {
        Write-Host "[setup-dev] done: hooks installed and i18n gate passed." -ForegroundColor Green
        exit 0
    } else {
        Write-Host "[setup-dev] FAILED: fix the errors above and re-run this script." -ForegroundColor Red
        exit 1
    }
}
finally {
    Pop-Location
}
