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
        if ($file.Name -match "Local download") {
            continue
        }
        if ($file.Name -match "Java") {
            $resolved["single-java"] = $file
            continue
        }
        if ($file.Name -match "^Pixiv User ") {
            $resolved["user-batch"] = $file
            continue
        }
        if ($file.Name -match "^Pixiv URL ") {
            $resolved["import-batch"] = $file
            continue
        }
    }

    $assignedPaths = @($resolved.Values | ForEach-Object { $_.FullName })
    $pageCandidates = @(
        $allFiles | Where-Object {
            $_.Name -notmatch "Local download" -and
            $assignedPaths -notcontains $_.FullName
        }
    )

    if ($pageCandidates.Count -ne 1) {
        throw "Could not uniquely identify the page batch userscript source."
    }

    $resolved["page-batch"] = $pageCandidates[0]

    foreach ($definition in $SourceDefinitions) {
        if (-not $resolved.ContainsKey($definition.Key)) {
            throw "Missing userscript source for key: $($definition.Key)"
        }
    }

    return $resolved
}

function Get-UserscriptHeaderLines {
    param([string]$Content)

    $headerPattern = "(?s)// ==UserScript==\s*(.*?)\s*// ==/UserScript=="
    $match = [regex]::Match($Content, $headerPattern)
    if (-not $match.Success) {
        throw "Userscript header not found."
    }

    return ($match.Groups[1].Value -split "`r?`n")
}

function Remove-UserscriptHeader {
    param([string]$Content)

    $regex = [regex]"(?s)^.*?// ==/UserScript==\s*"
    return $regex.Replace($Content, "", 1).Trim()
}

function Convert-ToBundleFunction {
    param(
        [string]$Content,
        [string]$FunctionName
    )

    $body = Remove-UserscriptHeader -Content $Content
    $startRegex = [regex]"^\(function\s*\(\s*\)\s*\{"
    $endRegex = [regex]"\}\)\(\);\s*$"
    $body = $startRegex.Replace($body, "function $FunctionName() {", 1)
    $body = $endRegex.Replace($body, "}", 1)

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
$moduleDefinitions = [System.Collections.Generic.List[string]]::new()
$moduleCalls = [System.Collections.Generic.List[string]]::new()
$resolvedSources = Resolve-UserscriptSources -RootDir $ProjectRoot

foreach ($definition in $SourceDefinitions) {
    $source = $resolvedSources[$definition.Key]
    $content = Get-Content -Raw -Encoding UTF8 $source.FullName
    $headerLines = Get-UserscriptHeaderLines -Content $content

    foreach ($line in $headerLines) {
        if ($line -match "^\s*//\s*@grant\s+(.+?)\s*$") {
            Add-UniqueValues -Target $grantValues -Values @($matches[1].Trim())
        } elseif ($line -match "^\s*//\s*@match\s+(.+?)\s*$") {
            Add-UniqueValues -Target $matchValues -Values @($matches[1].Trim())
        } elseif ($line -match "^\s*//\s*@connect\s+(.+?)\s*$") {
            Add-UniqueValues -Target $connectValues -Values @($matches[1].Trim())
        }
    }

    $null = $moduleDefinitions.Add((Convert-ToBundleFunction -Content $content -FunctionName $definition.FunctionName))
    $null = $moduleCalls.Add("    $($definition.FunctionName)();")
}

if (-not $grantValues.Contains("GM_registerMenuCommand")) {
    $null = $grantValues.Add("GM_registerMenuCommand")
}

# Bundle pins run-at to document-end: page/user/URL import modules touch
# document.body synchronously in their top-level init, so the DOM must exist.
# The Java single-artwork module uses its own waitForPageLoad() and tolerates
# document-end just fine.
$runAt = "document-end"
$metadataLines = [System.Collections.Generic.List[string]]::new()
$bundleDescriptionZh = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String("Ly8gQGRlc2NyaXB0aW9uICBQaXhpdiDlpJrlkIjkuIDkuIvovb3ohJrmnKzvvIzmlbTlkIjpobXpnaLmibnph4/kuIvovb3jgIFVc2VyIOaJuemHj+S4i+i9veOAgVVSTCDmibnph4/lr7zlhaXlkozljZXkvZzlk4HkuIvovb3vvIhKYXZh5ZCO56uv54mI77yJ44CC5aaC5aSa5ZCI5LiA6ISa5pys5byC5bi477yM6K+35YWI5bCd6K+V5a+55bqU54us56uL6ISa5pys77yb6Iul5LuF5aSa5ZCI5LiA5byC5bi477yM6K+36ZmE5aSN546w5q2l6aqk5ZCO5o+Q5LqkIGlzc3Vl44CC"))
$bundleDescriptionEn = "// @description:en  Pixiv all-in-one downloader for page batch, user batch, URL import, and single-artwork download (Java backend). If the bundle misbehaves, try the matching standalone script first; if only the bundle fails, open an issue with reproduction details."

$null = $metadataLines.Add("// ==UserScript==")
$null = $metadataLines.Add("// @name         Pixiv All-in-One Downloader")
$null = $metadataLines.Add("// @namespace    http://tampermonkey.net/")
$null = $metadataLines.Add("// @version      $Version")
$null = $metadataLines.Add($bundleDescriptionZh)
$null = $metadataLines.Add($bundleDescriptionEn)
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
$bundlePath = Join-Path $OutputDir $BundleFileName
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($bundlePath, $bundleContent, $utf8NoBom)
Write-Host "Generated bundled userscript: $(Join-Path $OutputDir $BundleFileName)"
