[CmdletBinding()]
param(
    [string]$Version = "0.0.0-local",
    [string]$OutputDir = ""
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path $ProjectRoot "build\generated-userscripts"
}

$BundleFileName = "Pixiv All-in-One.user.js"
$SourceDefinitions = @(
    @{
        Key = "page-batch"
        FunctionName = "pixivBundlePageBatch"
    },
    @{
        Key = "user-batch"
        FunctionName = "pixivBundleUserBatch"
    },
    @{
        Key = "import-batch"
        FunctionName = "pixivBundleImportBatch"
    },
    @{
        Key = "single-java"
        FunctionName = "pixivBundleArtworkJava"
    }
)

function Resolve-UserscriptSources {
    param([string]$RootDir)

    $allFiles = Get-ChildItem -LiteralPath $RootDir -Filter "*.user.js" -File |
        Where-Object { $_.Name -ne $BundleFileName }

    $resolved = @{}
    foreach ($file in $allFiles) {
        if ($file.Name -match 'Local download') {
            continue
        } elseif ($file.Name -match 'Java') {
            $resolved["single-java"] = $file
        } elseif ($file.Name -match '^Pixiv User ') {
            $resolved["user-batch"] = $file
        } elseif ($file.Name -match '^Pixiv URL ') {
            $resolved["import-batch"] = $file
        }
    }

    $assignedPaths = @($resolved.Values | ForEach-Object { $_.FullName })
    $pageCandidate = @(
        $allFiles | Where-Object {
            $_.Name -notmatch 'Local download' -and
            $assignedPaths -notcontains $_.FullName
        }
    )
    if ($pageCandidate.Count -ne 1) {
        throw "Could not uniquely identify the page batch userscript source."
    }
    $resolved["page-batch"] = $pageCandidate[0]

    foreach ($definition in $SourceDefinitions) {
        if (-not $resolved.ContainsKey($definition.Key)) {
            throw "Missing userscript source for key: $($definition.Key)"
        }
    }

    return $resolved
}

function Get-UserscriptHeaderLines {
    param([string]$Content)

    $headerPattern = '(?s)// ==UserScript==\s*(.*?)\s*// ==/UserScript=='
    $match = [regex]::Match($Content, $headerPattern)
    if (-not $match.Success) {
        throw "Userscript header not found."
    }

    return ($match.Groups[1].Value -split "`r?`n")
}

function Remove-UserscriptHeader {
    param([string]$Content)

    $regex = [regex]'(?s)^.*?// ==/UserScript==\s*'
    return $regex.Replace($Content, '', 1).Trim()
}

function Convert-ToBundleFunction {
    param(
        [string]$Content,
        [string]$FunctionName
    )

    $body = Remove-UserscriptHeader -Content $Content
    $startRegex = [regex]'^\(function\s*\(\s*\)\s*\{'
    $endRegex = [regex]'\}\)\(\);\s*$'
    $body = $startRegex.Replace($body, "function $FunctionName() {", 1)
    $body = $endRegex.Replace($body, '}', 1)

    if (-not $body.StartsWith("function $FunctionName() {")) {
        throw "Failed to convert outer IIFE for function $FunctionName."
    }

    return $body
}

function Add-UniqueValues {
    param(
        [System.Collections.Generic.List[string]]$Target,
        [string[]]$Values
    )

    foreach ($value in $Values) {
        if ([string]::IsNullOrWhiteSpace($value)) {
            continue
        }
        if (-not $Target.Contains($value)) {
            $null = $Target.Add($value)
        }
    }
}

$grantValues = [System.Collections.Generic.List[string]]::new()
$matchValues = [System.Collections.Generic.List[string]]::new()
$connectValues = [System.Collections.Generic.List[string]]::new()
$hasDocumentStart = $false
$moduleDefinitions = [System.Collections.Generic.List[string]]::new()
$moduleCalls = [System.Collections.Generic.List[string]]::new()
$resolvedSources = Resolve-UserscriptSources -RootDir $ProjectRoot

foreach ($definition in $SourceDefinitions) {
    $source = $resolvedSources[$definition.Key]
    $content = Get-Content -Raw -Encoding UTF8 $source.FullName
    $headerLines = Get-UserscriptHeaderLines -Content $content

    foreach ($line in $headerLines) {
        if ($line -match '^\s*//\s*@grant\s+(.+?)\s*$') {
            Add-UniqueValues -Target $grantValues -Values @($matches[1].Trim())
        } elseif ($line -match '^\s*//\s*@match\s+(.+?)\s*$') {
            Add-UniqueValues -Target $matchValues -Values @($matches[1].Trim())
        } elseif ($line -match '^\s*//\s*@connect\s+(.+?)\s*$') {
            Add-UniqueValues -Target $connectValues -Values @($matches[1].Trim())
        } elseif ($line -match '^\s*//\s*@run-at\s+document-start\s*$') {
            $hasDocumentStart = $true
        }
    }

    $null = $moduleDefinitions.Add((Convert-ToBundleFunction -Content $content -FunctionName $definition.FunctionName))
    $null = $moduleCalls.Add("    $($definition.FunctionName)();")
}

if (-not $grantValues.Contains("GM_registerMenuCommand")) {
    $null = $grantValues.Add("GM_registerMenuCommand")
}

$runAt = if ($hasDocumentStart) { "document-start" } else { "document-end" }
$metadataLines = [System.Collections.Generic.List[string]]::new()
$null = $metadataLines.Add("// ==UserScript==")
$null = $metadataLines.Add("// @name         Pixiv All-in-One Downloader")
$null = $metadataLines.Add("// @namespace    http://tampermonkey.net/")
$null = $metadataLines.Add("// @version      $Version")
$null = $metadataLines.Add("// @description  Bundled Pixiv downloader that combines page batch, user batch, URL import, and Java-backend single-artwork downloading.")
$null = $metadataLines.Add("// @author       Sywyar")
foreach ($value in $matchValues) {
    $null = $metadataLines.Add("// @match        $value")
}
foreach ($value in $grantValues) {
    $null = $metadataLines.Add("// @grant        $value")
}
foreach ($value in $connectValues) {
    $null = $metadataLines.Add("// @connect      $value")
}
$null = $metadataLines.Add("// @run-at       $runAt")
$null = $metadataLines.Add("// ==/UserScript==")

$bootstrap = @"
(function () {
    'use strict';

@MODULE_DEFINITIONS@

@MODULE_CALLS@
})();
"@

$bundleContent = @(
    $metadataLines -join "`r`n"
    ""
    ($bootstrap.Replace("@MODULE_DEFINITIONS@", ($moduleDefinitions -join "`r`n`r`n")).Replace("@MODULE_CALLS@", ($moduleCalls -join "`r`n")))
) -join "`r`n"

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
Set-Content -Path (Join-Path $OutputDir $BundleFileName) -Value $bundleContent -Encoding UTF8
Write-Host "Generated bundled userscript: $(Join-Path $OutputDir $BundleFileName)"
