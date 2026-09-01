<#
.SYNOPSIS
    Stages official plugin inputs into a Windows app image.

.DESCRIPTION
    This file owns only package-local plugin input staging. Official plugin sets, artifact shapes,
    signatures and provenance remain defined by plugin-distribution-common.ps1.
    This file is ASCII-only for Windows PowerShell 5.1.
#>

if (-not (Get-Command Get-OfficialRequiredPlugins -ErrorAction SilentlyContinue)) {
    . (Join-Path $PSScriptRoot "plugin-distribution-common.ps1")
}

function Resolve-PrebuiltPluginsDir {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) {
        return ""
    }
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw "PrebuiltPluginsDir not found or not a directory: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function Stage-OfficialPlugins {
    param(
        [Parameter(Mandatory = $true)][string]$AppDir,
        [Parameter(Mandatory = $true)]$Plugins,
        [string]$PrebuiltPluginsDir,
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [string]$OfficialKeyId,
        [string]$PrivateKeyFile,
        [string]$SignatureToolJar,
        [switch]$AllowUnsignedLocalPlugins
    )
    $pluginsDir = Join-Path $AppDir "plugins"
    New-Item -ItemType Directory -Force -Path $pluginsDir | Out-Null
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    $manifest = @()
    $sumLines = @()
    $requiredPluginIds = @(Get-OfficialRequiredPlugins | ForEach-Object { $_.Id })
    foreach ($plugin in $Plugins) {
        $extension = Get-OfficialPluginArtifactExtension $plugin
        if ($PrebuiltPluginsDir) {
            $sourceArtifact = Find-PrebuiltOfficialPluginArtifact $plugin $PrebuiltPluginsDir
        } else {
            $sourceArtifact = Find-ModulePluginArtifact $plugin $ProjectRoot
        }
        $descriptor = Assert-OfficialPluginArtifact $sourceArtifact $plugin
        $stableName = "$($plugin.Module).$extension"
        $targetArtifact = Join-Path $pluginsDir $stableName
        Copy-Item $sourceArtifact $targetArtifact -Force
        $sha = Get-Sha256Hex $targetArtifact
        [System.IO.File]::WriteAllText("$targetArtifact.sha256", "$sha  $stableName`n", $utf8NoBom)
        $verifiedAt = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
        if ($AllowUnsignedLocalPlugins) {
            foreach ($signatureSidecar in @("$targetArtifact.sig", "$targetArtifact.sig.json")) {
                Remove-Item -LiteralPath $signatureSidecar -Force -ErrorAction SilentlyContinue
            }
            $signature = $null
            $provenanceSource = "LOCAL_UPLOAD"
            $verificationStatus = "UNSIGNED_ALLOWED"
            [void](Write-UnsignedLocalPluginProvenanceSidecar $targetArtifact $verifiedAt)
        } else {
            if ([string]::IsNullOrWhiteSpace($SignatureToolJar)) {
                throw "SignatureToolJar is required unless -AllowUnsignedLocalPlugins is used."
            }
            $sourceSignaturePath = Find-PluginArtifactSignatureSidecar $sourceArtifact
            $signature = Get-PluginArtifactSignatureForDistribution `
                -ToolJar $SignatureToolJar `
                -ArtifactPath $targetArtifact `
                -PluginId $plugin.Id `
                -Version $descriptor["plugin.version"] `
                -ExistingSignaturePath $sourceSignaturePath `
                -OfficialKeyId $OfficialKeyId `
                -PrivateKeyFile $PrivateKeyFile `
                -OutputPath "$targetArtifact.sig"
            $provenanceSource = "MARKET_CATALOG"
            $verificationStatus = "VERIFIED"
            [void](Write-PluginProvenanceSidecar $targetArtifact (Get-Item -LiteralPath $targetArtifact).Length `
                $sha $signature $verifiedAt)
        }
        $manifest += [ordered]@{
            id = $plugin.Id
            version = $descriptor["plugin.version"]
            requires = $descriptor["plugin.requires"]
            required = ($requiredPluginIds -contains $plugin.Id)
            file = $stableName
            sha256 = $sha
            source = $provenanceSource
            verification = $verificationStatus
            signature = $signature
        }
        $sumLines += "$sha  $stableName"
        Write-Host ("    OK: staged {0} (id {1}, sha256 {2}, verification {3})." -f `
            $stableName, $plugin.Id, $sha, $verificationStatus) -ForegroundColor Green
    }
    [System.IO.File]::WriteAllText((Join-Path $pluginsDir "SHA256SUMS"),
        (($sumLines -join "`n") + "`n"), $utf8NoBom)
    $manifestJson = ConvertTo-Json @($manifest) -Depth 5
    [System.IO.File]::WriteAllText((Join-Path $pluginsDir "plugins-manifest.json"),
        $manifestJson + "`n", $utf8NoBom)
    if ($AllowUnsignedLocalPlugins) {
        $warning = @(
            "LOCAL TEST BUILD ONLY",
            "Plugins in this directory are accepted as unsigned local uploads.",
            "Do not distribute this installer or app image."
        ) -join "`n"
        [System.IO.File]::WriteAllText((Join-Path $pluginsDir "LOCAL-UNSIGNED-BUILD.txt"),
            $warning + "`n", $utf8NoBom)
    }
    return $manifest.Count
}
