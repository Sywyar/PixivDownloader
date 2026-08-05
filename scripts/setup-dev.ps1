# setup-dev.ps1 - development environment bootstrap (Windows PowerShell primary)
# Steps: tool version checks -> hooks install -> hooks doctor -> basic i18n check.
# Never touches global Git config; never runs automatically on git clone.
$ErrorActionPreference = 'Stop'

function Test-Command([string]$name) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($null -eq $cmd) {
        Write-Host "[setup-dev] ERROR: $name is not installed or not in PATH" -ForegroundColor Red
        return $false
    }
    return $true
}

$ok = $true

Write-Host "[setup-dev] checking tool versions..." -ForegroundColor Cyan
foreach ($tool in @('git', 'node', 'npm')) {
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
    Write-Host "[setup-dev] running basic i18n check..." -ForegroundColor Cyan
    npm run i18n:check
    if ($LASTEXITCODE -ne 0) { $ok = $false }
}

if ($ok) {
    Write-Host "[setup-dev] done: hooks installed and i18n check passed." -ForegroundColor Green
    exit 0
} else {
    Write-Host "[setup-dev] FAILED: fix the errors above and re-run this script." -ForegroundColor Red
    exit 1
}
