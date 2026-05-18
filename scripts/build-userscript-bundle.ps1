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

# 先同步 scripts/shared 下的共享片段到所有标准 .user.js，保证 bundle 内联的版本是最新的。
& (Join-Path $PSScriptRoot "sync-shared-snippets.ps1")

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
    },
    @{
        Key = "experience"
        FunctionName = "pixivBundleExperienceToolbox"
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
        # "体验增强" — \u escapes keep the match independent of this script's source encoding.
        if ($file.Name -match "\u4f53\u9a8c\u589e\u5f3a") {
            $resolved["experience"] = $file
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

# Bundle pins run-at to document-end: page/user/bulk import single works modules touch
# document.body synchronously in their top-level init, so the DOM must exist.
# The Java single-artwork module uses its own waitForPageLoad() and tolerates
# document-end just fine.
$runAt = "document-end"
$metadataLines = [System.Collections.Generic.List[string]]::new()
$bundleUpdateUrl = "// @updateURL    https://github.com/Sywyar/PixivDownloader/releases/latest/download/Pixiv%20All-in-One.user.js"
$bundleDownloadUrl = "// @downloadURL  https://github.com/Sywyar/PixivDownloader/releases/latest/download/Pixiv%20All-in-One.user.js"
$bundleDescriptionZh = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String("Ly8gQGRlc2NyaXB0aW9uICBQaXhpdiDlpJrlkIjkuIDohJrmnKzvvIzmlbTlkIjpobXpnaLmibnph4/kuIvovb3jgIFVc2VyIOaJuemHj+S4i+i9veOAgeaJuemHj+WvvOWFpeWNleS9nOWTgeOAgeWNleS9nOWTgeS4i+i9ve+8iEphdmHlkI7nq6/niYjvvInkuI7kvZPpqozlop7lvLrlt6XlhbfnrrHjgILlpoLlpJrlkIjkuIDohJrmnKzlvILluLjvvIzor7flhYjlsJ3or5Xlr7nlupTni6znq4vohJrmnKzvvIzlho3lsIbkuKTnp43ohJrmnKznmoTooajnjrDkuIDlubbpmYTkuIrlkI7mj5DkuqQgaXNzdWXjgII="))
$bundleDescriptionEn = "// @description:en  Pixiv all-in-one toolkit bundling page batch, user batch, bulk import single works, single-artwork download (Java backend), and the experience-enhancement toolbox. If the bundle misbehaves, try the matching standalone script first; if only the bundle fails, open an issue with reproduction details."

$null = $metadataLines.Add("// ==UserScript==")
$null = $metadataLines.Add("// @name         Pixiv All-in-One Downloader")
$null = $metadataLines.Add("// @namespace    http://tampermonkey.net/")
$null = $metadataLines.Add("// @version      $Version")
$null = $metadataLines.Add($bundleUpdateUrl)
$null = $metadataLines.Add($bundleDownloadUrl)
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
