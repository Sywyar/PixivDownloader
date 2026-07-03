[CmdletBinding()]
param(
    [switch]$Check
)

# Sync shared snippets from scripts/shared/*.js into every .user.js file.
# Each target script declares a shared block with these markers:
#   // >>> SHARED:sse-manager.js
#   ... generated content ...
#   // <<< SHARED:sse-manager.js
# Shared files contain raw content only, without indent or markers. This script
# prefixes shared content with the indentation from the target marker line.
#
# Usage:
#   ./scripts/sync-shared-snippets.ps1           # write shared content back
#   ./scripts/sync-shared-snippets.ps1 -Check    # fail if generated blocks drift

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$SharedDir = Join-Path $ProjectRoot "scripts\shared"

function Read-FileText {
    param([string]$Path)
    return [System.IO.File]::ReadAllText($Path, [System.Text.UTF8Encoding]::new($false))
}

function Write-FileText {
    param([string]$Path, [string]$Content)
    [System.IO.File]::WriteAllText($Path, $Content, [System.Text.UTF8Encoding]::new($false))
}

function Get-LineEnding {
    param([string]$Content)
    if ($Content -match "`r`n") { return "`r`n" }
    return "`n"
}

function Sync-Target {
    param([string]$TargetPath, [bool]$CheckOnly)

    $content = Read-FileText -Path $TargetPath
    $eol = Get-LineEnding -Content $content
    $original = $content

    $pattern = '(?ms)^(?<indent>[ \t]*)//\s*>>>\s*SHARED:(?<name>[^\r\n]+?)\s*$.*?^[ \t]*//\s*<<<\s*SHARED:\k<name>\s*$'
    $regex = [regex]$pattern

    $content = $regex.Replace($content, {
        param($match)
        $indent = $match.Groups["indent"].Value
        $name = $match.Groups["name"].Value.Trim()
        $sharedPath = Join-Path $SharedDir $name
        if (-not (Test-Path -LiteralPath $sharedPath)) {
            throw "Shared snippet not found: $sharedPath (referenced from $TargetPath)"
        }
        $shared = Read-FileText -Path $sharedPath
        $sharedLines = $shared -split "`r?`n"
        # trim trailing blank lines from the shared content
        while ($sharedLines.Count -gt 0 -and [string]::IsNullOrWhiteSpace($sharedLines[$sharedLines.Count - 1])) {
            $sharedLines = $sharedLines[0..($sharedLines.Count - 2)]
        }
        $rebuilt = [System.Collections.Generic.List[string]]::new()
        $null = $rebuilt.Add("$indent// >>> SHARED:$name")
        foreach ($line in $sharedLines) {
            if ([string]::IsNullOrEmpty($line)) {
                $null = $rebuilt.Add("")
            } else {
                $null = $rebuilt.Add("$indent$line")
            }
        }
        $null = $rebuilt.Add("$indent// <<< SHARED:$name")
        return ($rebuilt -join $eol)
    })

    if ($content -eq $original) {
        return @{ Changed = $false; Drift = $false }
    }

    if ($CheckOnly) {
        return @{ Changed = $false; Drift = $true }
    }

    Write-FileText -Path $TargetPath -Content $content
    return @{ Changed = $true; Drift = $false }
}

$targets = Get-ChildItem -LiteralPath $ProjectRoot -Filter "*.user.js" -File |
    Where-Object { $_.Name -ne "Pixiv All-in-One.user.js" }

$driftedFiles = [System.Collections.Generic.List[string]]::new()
foreach ($file in $targets) {
    $result = Sync-Target -TargetPath $file.FullName -CheckOnly:$Check.IsPresent
    if ($Check.IsPresent -and $result.Drift) {
        $null = $driftedFiles.Add($file.Name)
    }
    if ($result.Changed) {
        Write-Host "Synced shared snippets: $($file.Name)"
    }
}

if ($Check.IsPresent -and $driftedFiles.Count -gt 0) {
    Write-Error "Drift detected in: $($driftedFiles -join ', '). Run scripts/sync-shared-snippets.ps1 to fix."
    exit 1
}
