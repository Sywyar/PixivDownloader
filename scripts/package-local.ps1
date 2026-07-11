[CmdletBinding()]
param(
    [string]$Version = "0.0.1-local",
    [string]$PrebuiltJar,
    [string]$PrebuiltPluginsDir,
    [switch]$SkipPlugins,
    [switch]$RunTests,
    [switch]$SkipPortable,
    [switch]$SkipOfflinePortable,
    [switch]$RedownloadFfmpeg,
    [string[]]$MsiCultures,
    [string[]]$MsiVariants,
    [Alias("SkipMsi")]
    [switch]$SkipInstaller,
    [string]$OfficialKeyId,
    [string]$PrivateKeyFile,
    [string]$SignatureToolJar
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null

# Shared official-plugin list + plugin-jar / checksum primitives (one source of distribution truth,
# also used by scripts/assemble-plugin-distribution.ps1).
. (Join-Path $PSScriptRoot "plugin-distribution-common.ps1")

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BuildRoot = Join-Path $ProjectRoot "build"
$InputDir = Join-Path $BuildRoot "input"
$RuntimeDir = Join-Path $BuildRoot "runtime"
$OnlineAppImageRoot = Join-Path $BuildRoot "app-image-online"
$OnlineAppDir = Join-Path $OnlineAppImageRoot "PixivDownload"
$OfflineAppImageRoot = Join-Path $BuildRoot "app-image-full"
$OfflineAppDir = Join-Path $OfflineAppImageRoot "PixivDownload"
$InnoToolchainDir = Join-Path $BuildRoot "inno-admin-loader"
$OutDir = Join-Path $BuildRoot "out"
$WixDir = Join-Path $BuildRoot "wix"
$FfmpegDir = Join-Path $BuildRoot "ffmpeg"
$FfmpegUnpackDir = Join-Path $FfmpegDir "unpack"
$AppName = "PixivDownload"
$AppVendor = "sywyar"
$MainClass = "org.springframework.boot.loader.launch.JarLauncher"
$JreModules = "java.base,java.compiler,java.datatransfer,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.xml,jdk.charsets,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.httpserver,jdk.localedata,jdk.management,jdk.unsupported,jdk.zipfs"
$FfmpegZipUrl = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-lgpl.zip"
$OfficialPluginCatalogUrl = "https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/master/manifest.json"
$InstallerPluginApiVersion = "1.1.0"
$InstallerCatalogDirName = "installer-catalog"
$InstallerCatalogIncludePath = Join-Path $BuildRoot "installer-plugin-catalog-items.iss.inc"
$FfmpegExe = Join-Path $FfmpegDir "ffmpeg.exe"
$FfprobeExe = Join-Path $FfmpegDir "ffprobe.exe"
$FfmpegLicense = Join-Path $FfmpegDir "ffmpeg-LGPL.txt"
$OnlineZipPath = Join-Path $OutDir "$AppName-$Version-win-x64-online-portable.zip"
$OfflineZipPath = Join-Path $OutDir "$AppName-$Version-win-x64-portable.zip"
$SetupPath = Join-Path $OutDir "$AppName-$Version-win-x64-setup.exe"
$InnoScript = Join-Path $ProjectRoot "packaging/windows/inno/PixivDownload.iss"
$SetExeExecutionLevelScript = Join-Path $PSScriptRoot "set-windows-exe-requested-execution-level.ps1"
$PrepareInnoAdminLoaderScript = Join-Path $PSScriptRoot "prepare-inno-admin-loader.ps1"
$InstallerVersion = $null

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Assert-Command {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Missing required command: $Name"
    }
}

function Get-MavenCommand {
    $wrapper = Join-Path $ProjectRoot "mvnw.cmd"
    if (Test-Path $wrapper) {
        return $wrapper
    }

    $maven = Get-Command "mvn.cmd" -ErrorAction SilentlyContinue
    if ($maven) {
        return $maven.Source
    }

    $maven = Get-Command "mvn" -ErrorAction SilentlyContinue
    if ($maven) {
        return $maven.Source
    }

    throw "Missing Maven command. Install Maven or use the Maven wrapper."
}

function Get-InnoSetupCompiler {
    $command = Get-Command "iscc.exe" -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $candidates = @()
    $programFilesX86 = ${env:ProgramFiles(x86)}
    if (-not [string]::IsNullOrWhiteSpace($programFilesX86)) {
        $candidates += Join-Path $programFilesX86 "Inno Setup 6\ISCC.exe"
    }
    if (-not [string]::IsNullOrWhiteSpace($env:ProgramFiles)) {
        $candidates += Join-Path $env:ProgramFiles "Inno Setup 6\ISCC.exe"
    }

    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    throw "Missing required command: iscc.exe. Install Inno Setup 6 or add ISCC.exe to PATH."
}

function Remove-PathIfExists {
    param([string]$Path)
    if (Test-Path $Path) {
        Remove-Item -Recurse -Force $Path
    }
}

function Ensure-Directory {
    param([string]$Path)
    New-Item -ItemType Directory -Force -Path $Path | Out-Null
}

function Invoke-External {
    param(
        [string]$FilePath,
        [string[]]$ArgumentList
    )

    & $FilePath @ArgumentList
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed: $FilePath $($ArgumentList -join ' ')"
    }
}

function Get-BuiltJar {
    $bootCandidate = Get-ChildItem (Join-Path $ProjectRoot "pixivdownload-app\target\PixivDownload-*-boot.jar") -File
    if ($bootCandidate) {
        return ($bootCandidate | Sort-Object LastWriteTime -Descending | Select-Object -First 1)
    }

    $jar = Get-ChildItem (Join-Path $ProjectRoot "pixivdownload-app\target\PixivDownload-*.jar") -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if (-not $jar) {
        throw "Could not find built JAR under pixivdownload-app/target/."
    }

    return $jar
}

function Resolve-PrebuiltJar {
    param([string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path)) {
        return $null
    }

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Write-Warning "Prebuilt JAR not found: $Path"
        return $null
    }

    $item = Get-Item -LiteralPath $Path
    if ($item.Extension -ne ".jar") {
        Write-Warning "Prebuilt JAR is not a .jar file: $Path"
        return $null
    }
    if ($item.Length -le 0) {
        Write-Warning "Prebuilt JAR is empty: $Path"
        return $null
    }

    return $item.FullName
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
    # Stage the requested official external plugin artifacts into <AppDir>\plugins.
    # Each artifact is shape-checked and copied under a STABLE, version-less name (<module>.<ext>): an in-place
    # installer upgrade then overwrites only that exact path (the existing [Files] ignoreversion flag)
    # and never leaves a stale duplicate, while third-party plugin artifacts under plugins/ - any other name -
    # are not in the installer file list and are therefore preserved across upgrade. The plugin's own
    # plugin.version (read from plugin.properties) is recorded in plugins-manifest.json.
    param(
        [Parameter(Mandatory = $true)][string]$AppDir,
        [Parameter(Mandatory = $true)]$Plugins,
        [string]$PrebuiltPluginsDir,
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [string]$OfficialKeyId,
        [string]$PrivateKeyFile,
        [Parameter(Mandatory = $true)][string]$SignatureToolJar
    )
    $pluginsDir = Join-Path $AppDir "plugins"
    Ensure-Directory $pluginsDir
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    $manifest = @()
    $sumLines = @()
    $requiredPluginIds = @(Get-OfficialRequiredPlugins | ForEach-Object { $_.Id })
    foreach ($plugin in $Plugins) {
        $extension = Get-OfficialPluginArtifactExtension $plugin
        if ($PrebuiltPluginsDir) {
            $candidate = Get-ChildItem (Join-Path $PrebuiltPluginsDir "$($plugin.Module)-*.$extension") -File -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -notlike "*-sources.jar" -and $_.Name -notlike "*-javadoc.jar" } |
                Sort-Object LastWriteTime -Descending |
                Select-Object -First 1
            if (-not $candidate) {
                throw "Prebuilt plugin artifact for module $($plugin.Module) not found under $PrebuiltPluginsDir."
            }
            $sourceArtifact = $candidate.FullName
        } else {
            $sourceArtifact = Find-ModulePluginArtifact $plugin $ProjectRoot
        }
        $sourceSignaturePath = Find-PluginArtifactSignatureSidecar $sourceArtifact
        $descriptor = Assert-OfficialPluginArtifact $sourceArtifact $plugin
        $stableName = "$($plugin.Module).$extension"
        $targetArtifact = Join-Path $pluginsDir $stableName
        Copy-Item $sourceArtifact $targetArtifact -Force
        $sha = Get-Sha256Hex $targetArtifact
        [System.IO.File]::WriteAllText("$targetArtifact.sha256", "$sha  $stableName`n", $utf8NoBom)
        $signature = Get-PluginArtifactSignatureForDistribution `
            -ToolJar $SignatureToolJar `
            -ArtifactPath $targetArtifact `
            -PluginId $plugin.Id `
            -Version $descriptor["plugin.version"] `
            -ExistingSignaturePath $sourceSignaturePath `
            -OfficialKeyId $OfficialKeyId `
            -PrivateKeyFile $PrivateKeyFile `
            -OutputPath "$targetArtifact.sig"
        $verifiedAt = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
        [void](Write-PluginProvenanceSidecar $targetArtifact (Get-Item -LiteralPath $targetArtifact).Length `
            $sha $signature $verifiedAt)
        $manifest += [ordered]@{
            id       = $plugin.Id
            version  = $descriptor["plugin.version"]
            requires = $descriptor["plugin.requires"]
            required = ($requiredPluginIds -contains $plugin.Id)
            file     = $stableName
            sha256   = $sha
            signature = $signature
        }
        $sumLines += "$sha  $stableName"
        Write-Host ("    OK: staged {0} (id {1}, sha256 {2})." -f $stableName, $plugin.Id, $sha) -ForegroundColor Green
    }
    # Checksums + manifest live alongside the jars inside plugins/ (paths relative to plugins/).
    [System.IO.File]::WriteAllText((Join-Path $pluginsDir "SHA256SUMS"), (($sumLines -join "`n") + "`n"), $utf8NoBom)
    $manifestJson = ConvertTo-Json @($manifest) -Depth 5
    [System.IO.File]::WriteAllText((Join-Path $pluginsDir "plugins-manifest.json"), $manifestJson + "`n", $utf8NoBom)
    return $manifest.Count
}

function ConvertTo-RawPluginCatalogUrl {
    param([Parameter(Mandatory = $true)][string]$Url)
    $trimmed = $Url.Trim()
    if ($trimmed -match "^https://github\.com/([^/?#]+)/([^/?#]+)/blob/([^/?#]+)/(.+\.json)(?:[?#].*)?$") {
        return "https://raw.githubusercontent.com/$($Matches[1])/$($Matches[2])/$($Matches[3])/$($Matches[4])"
    }
    return $trimmed
}

function Get-PluginCatalogSignatureUrl {
    param([Parameter(Mandatory = $true)][string]$Url)
    $raw = ConvertTo-RawPluginCatalogUrl $Url
    $queryIndex = $raw.IndexOf("?")
    if ($queryIndex -ge 0) {
        return $raw.Substring(0, $queryIndex) + ".sig" + $raw.Substring($queryIndex)
    }
    return "$raw.sig"
}

function Assert-InstallerPluginCatalogSignature {
    param(
        [Parameter(Mandatory = $true)][string]$SignatureToolJar,
        [Parameter(Mandatory = $true)][string]$ManifestPath,
        [Parameter(Mandatory = $true)][string]$SignaturePath
    )
    Invoke-PluginSignatureTool $SignatureToolJar @(
        "verify-manifest",
        "--manifest", $ManifestPath,
        "--signature", $SignaturePath,
        "--repository-id", "official",
        "--policy", "official"
    )
}

function Get-InstallerCatalogProp {
    param(
        $Object,
        [Parameter(Mandatory = $true)][string]$Name
    )
    if ($null -eq $Object) { return $null }
    $prop = $Object.PSObject.Properties[$Name]
    if ($null -eq $prop) { return $null }
    return $prop.Value
}

function Get-InstallerCatalogTextValue {
    param(
        $Map,
        [AllowEmptyString()][string]$Fallback,
        [Parameter(Mandatory = $true)][string]$Language
    )
    if ($null -eq $Map) { return $Fallback }
    $base = $Language.Split("-")[0]
    foreach ($key in @($Language, $base, "zh", "en")) {
        $value = Get-InstallerCatalogProp $Map $key
        if (-not [string]::IsNullOrWhiteSpace([string]$value)) {
            return [string]$value
        }
    }
    foreach ($prop in $Map.PSObject.Properties) {
        if (-not [string]::IsNullOrWhiteSpace([string]$prop.Value)) {
            return [string]$prop.Value
        }
    }
    return $Fallback
}

function Escape-InstallerCatalogField {
    param([string]$Value)
    if ($null -eq $Value) { return "" }
    return $Value.Replace("%", "%25").Replace("|", "%7C").Replace("`r", " ").Replace("`n", " ")
}

function Parse-InstallerCatalogVersionPair {
    param([string]$Version)
    if ([string]::IsNullOrWhiteSpace($Version)) { return @(0, 0) }
    $parts = $Version.Split(".")
    $major = 0
    $minor = 0
    if ($parts.Length -ge 1) { [void][int]::TryParse($parts[0], [ref]$major) }
    if ($parts.Length -ge 2) { [void][int]::TryParse($parts[1], [ref]$minor) }
    return @($major, $minor)
}

function Test-InstallerCatalogCompatible {
    param(
        [string]$Required,
        [Parameter(Mandatory = $true)][string]$CoreApiVersion
    )
    if ([string]::IsNullOrWhiteSpace($Required)) { return $true }
    $core = Parse-InstallerCatalogVersionPair $CoreApiVersion
    $requiredPair = Parse-InstallerCatalogVersionPair $Required
    return ($core[0] -eq $requiredPair[0]) -and ($core[1] -ge $requiredPair[1])
}

function Select-InstallerCatalogPackage {
    param($Entry)
    $packages = @(Get-InstallerCatalogProp $Entry "packages")
    if ($packages.Count -eq 0) { return $null }
    $market = Get-InstallerCatalogProp $Entry "market"
    $latest = Get-InstallerCatalogProp $market "latestVersion"
    if (-not [string]::IsNullOrWhiteSpace([string]$latest)) {
        foreach ($pkg in $packages) {
            if ((Get-InstallerCatalogProp $pkg "version") -eq $latest) {
                return $pkg
            }
        }
    }
    return $packages[0]
}

function Test-InstallerCatalogInstallablePackage {
    param(
        $Package,
        [Parameter(Mandatory = $true)][string]$CoreApiVersion
    )
    if ($null -eq $Package) { return $false }
    if ([string]::IsNullOrWhiteSpace([string](Get-InstallerCatalogProp $Package "version"))) { return $false }
    if ([string]::IsNullOrWhiteSpace([string](Get-InstallerCatalogProp $Package "packageUrl"))) { return $false }
    if ([string]::IsNullOrWhiteSpace([string](Get-InstallerCatalogProp $Package "sha256"))) { return $false }
    $size = [int64](Get-InstallerCatalogProp $Package "expectedSizeBytes")
    if ($size -le 0) { return $false }
    if ($null -eq (Get-InstallerCatalogProp $Package "signature")) { return $false }
    return Test-InstallerCatalogCompatible ([string](Get-InstallerCatalogProp $Package "requiredCoreApi")) $CoreApiVersion
}

function New-InstallerCatalogProjectionRows {
    param(
        [Parameter(Mandatory = $true)][string]$ManifestPath,
        [Parameter(Mandatory = $true)][string]$Language,
        [Parameter(Mandatory = $true)][string]$CoreApiVersion
    )
    $manifest = Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $rows = New-Object System.Collections.Generic.List[object]
    foreach ($entry in @(Get-InstallerCatalogProp $manifest "entries")) {
        $pluginId = [string](Get-InstallerCatalogProp $entry "pluginId")
        if ([string]::IsNullOrWhiteSpace($pluginId)) { continue }
        $market = Get-InstallerCatalogProp $entry "market"
        if ([bool](Get-InstallerCatalogProp $market "officialRequired")) { continue }
        $pkg = Select-InstallerCatalogPackage $entry
        if (-not (Test-InstallerCatalogInstallablePackage $pkg $CoreApiVersion)) { continue }
        $rows.Add([pscustomobject]@{
            PluginId = $pluginId
            Version = [string](Get-InstallerCatalogProp $pkg "version")
            DisplayName = Get-InstallerCatalogTextValue (Get-InstallerCatalogProp $market "displayName") $pluginId $Language
            Summary = Get-InstallerCatalogTextValue (Get-InstallerCatalogProp $market "summary") "" $Language
            Size = [string](Get-InstallerCatalogProp $pkg "expectedSizeBytes")
            Category = [string](Get-InstallerCatalogProp $market "category")
        })
    }
    return $rows.ToArray()
}

function Write-InstallerPluginCatalogProjection {
    param(
        [Parameter(Mandatory = $true)][string]$ManifestPath,
        [Parameter(Mandatory = $true)][string]$OutputPath,
        [Parameter(Mandatory = $true)][string]$Language,
        [Parameter(Mandatory = $true)][string]$CoreApiVersion
    )
    $projectionUtf8NoBom = New-Object System.Text.UTF8Encoding($false)
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("CATALOG|PACKAGED|installer-plugin-catalog.json")
    foreach ($row in @(New-InstallerCatalogProjectionRows -ManifestPath $ManifestPath `
                -Language $Language -CoreApiVersion $CoreApiVersion)) {
        $line = "ITEM|{0}|{1}|{2}|{3}|{4}|{5}" -f `
            (Escape-InstallerCatalogField $row.PluginId), (Escape-InstallerCatalogField $row.Version), `
            (Escape-InstallerCatalogField $row.DisplayName), (Escape-InstallerCatalogField $row.Summary), `
            (Escape-InstallerCatalogField $row.Size), (Escape-InstallerCatalogField $row.Category)
        $lines.Add($line)
    }
    [System.IO.File]::WriteAllText($OutputPath, (($lines -join "`n") + "`n"), $projectionUtf8NoBom)
}

function Escape-InstallerCatalogIssString {
    param([AllowNull()][string]$Value)
    if ($null -eq $Value) { return "''" }
    $escaped = $Value.Replace("'", "''").Replace("`r", " ").Replace("`n", " ")
    return "'$escaped'"
}

function Add-InstallerCatalogIncludeRows {
    param(
        [Parameter(Mandatory = $true)][System.Collections.Generic.List[string]]$Lines,
        $Rows
    )
    foreach ($row in @($Rows)) {
        $Lines.Add(("    AddPluginCatalogItem({0}, {1}, {2}, {3});" -f `
                    (Escape-InstallerCatalogIssString $row.PluginId), `
                    (Escape-InstallerCatalogIssString $row.Version), `
                    (Escape-InstallerCatalogIssString $row.DisplayName), `
                    (Escape-InstallerCatalogIssString $row.Summary)))
    }
}

function Write-InstallerPluginCatalogInclude {
    param(
        [Parameter(Mandatory = $true)][string]$ManifestPath,
        [Parameter(Mandatory = $true)][string]$OutputPath,
        [Parameter(Mandatory = $true)][string]$CoreApiVersion
    )
    $projectionUtf8NoBom = New-Object System.Text.UTF8Encoding($false)
    $enRows = @(New-InstallerCatalogProjectionRows -ManifestPath $ManifestPath `
            -Language "en" -CoreApiVersion $CoreApiVersion)
    $zhRows = @(New-InstallerCatalogProjectionRows -ManifestPath $ManifestPath `
            -Language "zh-CN" -CoreApiVersion $CoreApiVersion)
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("// Generated by scripts/package-local.ps1. Do not edit.")
    $lines.Add("procedure LoadCompiledInstallerPluginCatalogItems;")
    $lines.Add("begin")
    $lines.Add("  if ActiveLanguage = 'zhcn' then")
    $lines.Add("  begin")
    Add-InstallerCatalogIncludeRows -Lines $lines -Rows $zhRows
    $lines.Add("  end")
    $lines.Add("  else")
    $lines.Add("  begin")
    Add-InstallerCatalogIncludeRows -Lines $lines -Rows $enRows
    $lines.Add("  end;")
    $lines.Add("end;")
    [System.IO.File]::WriteAllText($OutputPath, (($lines -join "`n") + "`n"), $projectionUtf8NoBom)
}

function Stage-InstallerPluginCatalogSnapshot {
    param(
        [Parameter(Mandatory = $true)][string]$AppDir,
        [Parameter(Mandatory = $true)][string]$SignatureToolJar
    )
    $catalogDir = Join-Path $AppDir $InstallerCatalogDirName
    Ensure-Directory $catalogDir
    $manifestTarget = Join-Path $catalogDir "manifest.json"
    $signatureTarget = Join-Path $catalogDir "manifest.json.sig"
    $projectionEnTarget = Join-Path $catalogDir "catalog.en.txt"
    $projectionZhTarget = Join-Path $catalogDir "catalog.zh-CN.txt"

    $downloadDir = Join-Path $BuildRoot "installer-catalog-download"
    Remove-PathIfExists $downloadDir
    Ensure-Directory $downloadDir
    $manifestTemp = Join-Path $downloadDir "manifest.json"
    $signatureTemp = Join-Path $downloadDir "manifest.json.sig"
    $manifestUrl = ConvertTo-RawPluginCatalogUrl $OfficialPluginCatalogUrl
    $signatureUrl = Get-PluginCatalogSignatureUrl $OfficialPluginCatalogUrl

    Write-Host ("    Fetching signed installer plugin catalog: {0}" -f $OfficialPluginCatalogUrl)
    Invoke-WebRequest -Uri $manifestUrl -OutFile $manifestTemp -UseBasicParsing -TimeoutSec 30
    Invoke-WebRequest -Uri $signatureUrl -OutFile $signatureTemp -UseBasicParsing -TimeoutSec 30
    Assert-InstallerPluginCatalogSignature -SignatureToolJar $SignatureToolJar `
        -ManifestPath $manifestTemp -SignaturePath $signatureTemp

    $parsed = Get-Content -LiteralPath $manifestTemp -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($null -eq $parsed -or $null -eq $parsed.entries) {
        throw "Installer plugin catalog manifest does not contain an entries array."
    }

    Copy-Item $manifestTemp $manifestTarget -Force
    Copy-Item $signatureTemp $signatureTarget -Force
    Write-InstallerPluginCatalogProjection -ManifestPath $manifestTemp -OutputPath $projectionEnTarget `
        -Language "en" -CoreApiVersion $InstallerPluginApiVersion
    Write-InstallerPluginCatalogProjection -ManifestPath $manifestTemp -OutputPath $projectionZhTarget `
        -Language "zh-CN" -CoreApiVersion $InstallerPluginApiVersion
    Write-InstallerPluginCatalogInclude -ManifestPath $manifestTemp -OutputPath $InstallerCatalogIncludePath `
        -CoreApiVersion $InstallerPluginApiVersion
    Write-Host ("    OK: signed installer plugin catalog staged under {0}." -f $InstallerCatalogDirName) -ForegroundColor Green
}

function Get-InstallerVersion {
    param([string]$VersionText)

    $match = [regex]::Match($VersionText, "\d+(?:\.\d+){0,2}")
    if (-not $match.Success) {
        throw "Installer version must contain up to three numeric components. Received: $VersionText"
    }

    return $match.Value
}

function Ensure-FfmpegPayload {
    $hasPayload =
        (Test-Path $FfmpegExe) -and
        (Test-Path $FfprobeExe) -and
        (Test-Path $FfmpegLicense)

    if ($hasPayload -and -not $RedownloadFfmpeg) {
        Write-Step "Reusing existing FFmpeg payload from build/ffmpeg"
        return
    }

    Assert-Command "curl.exe"

    Write-Step "Downloading FFmpeg payload"
    Ensure-Directory $FfmpegDir
    Remove-PathIfExists $FfmpegUnpackDir
    Ensure-Directory $FfmpegUnpackDir

    $zipPath = Join-Path $FfmpegDir "ffmpeg.zip"
    Invoke-External "curl.exe" @("-fL", $FfmpegZipUrl, "-o", $zipPath)

    Expand-Archive -Path $zipPath -DestinationPath $FfmpegUnpackDir -Force

    $payloadRoot = Get-ChildItem $FfmpegUnpackDir -Directory |
        Where-Object { $_.Name -like "ffmpeg-*-win64-lgpl" } |
        Select-Object -First 1

    if (-not $payloadRoot) {
        throw "Could not locate unpacked FFmpeg payload."
    }

    Copy-Item (Join-Path $payloadRoot.FullName "bin\ffmpeg.exe") $FfmpegExe -Force
    Copy-Item (Join-Path $payloadRoot.FullName "bin\ffprobe.exe") $FfprobeExe -Force
    @"
FFmpeg is licensed under the LGPL v2.1.
Source code: https://ffmpeg.org
Build: BtbN FFmpeg Builds (https://github.com/BtbN/FFmpeg-Builds)
LGPL License: https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
"@ | Set-Content -Path $FfmpegLicense -Encoding UTF8
}

Push-Location $ProjectRoot
try {
    Write-Step "Checking local toolchain"
    $InstallerVersion = Get-InstallerVersion $Version
    $resolvedPrebuiltPluginsDir = ""
    if (-not $SkipPlugins) {
        $resolvedPrebuiltPluginsDir = Resolve-PrebuiltPluginsDir $PrebuiltPluginsDir
        $SignatureToolJar = Resolve-SignatureToolJar $ProjectRoot $SignatureToolJar
    }
    $mavenCmd = $null
    if (-not $PrebuiltJar) {
        $mavenCmd = Get-MavenCommand
    }
    Assert-Command "jlink"
    Assert-Command "jpackage"
    if (-not $SkipInstaller) {
        $innoCompilerSource = Get-InnoSetupCompiler
    }

    Write-Step "Cleaning local packaging directories"
    foreach ($path in @($InputDir, $RuntimeDir, $OnlineAppImageRoot, $OfflineAppImageRoot, $InnoToolchainDir, $OutDir, $WixDir, $InstallerCatalogIncludePath)) {
        Remove-PathIfExists $path
    }
    Ensure-Directory $InputDir
    Ensure-Directory $OutDir

    $stagedJar = Join-Path $InputDir "$AppName-$Version.jar"
    $resolvedPrebuiltJar = Resolve-PrebuiltJar $PrebuiltJar
    if ($resolvedPrebuiltJar) {
        Write-Step "Staging prebuilt application JAR"
        Copy-Item $resolvedPrebuiltJar $stagedJar -Force
    } else {
        if ($PrebuiltJar) {
            Write-Step "Prebuilt JAR invalid; falling back to Maven build"
        } else {
            Write-Step "Building application JAR"
        }
        if (-not $mavenCmd) {
            $mavenCmd = Get-MavenCommand
        }
        if ($RunTests) {
            Invoke-External $mavenCmd @("package", "-Dapp.release.version=$Version")
        } else {
            Invoke-External $mavenCmd @("package", "-DskipTests", "-Dapp.release.version=$Version")
        }

        $jar = Get-BuiltJar
        Copy-Item $jar.FullName $stagedJar -Force
    }

    Write-Step "Building trimmed runtime image"
    Invoke-External "jlink" @(
        "--add-modules", $JreModules,
        "--strip-debug",
        "--no-man-pages",
        "--no-header-files",
        "--compress=2",
        "--output", $RuntimeDir
    )

    Write-Step "Building app-image"
    Invoke-External "jpackage" @(
        "--type", "app-image",
        "--name", $AppName,
        "--app-version", $InstallerVersion,
        "--vendor", $AppVendor,
        "--input", $InputDir,
        "--main-jar", "$AppName-$Version.jar",
        "--main-class", $MainClass,
        "--runtime-image", $RuntimeDir,
        "--icon", "pixivdownload-app/src/main/resources/static/favicon.ico",
        "--java-options", "-Dfile.encoding=UTF-8",
        "--java-options", "-Dstdout.encoding=UTF-8",
        "--java-options", "-Dstderr.encoding=UTF-8",
        "--dest", $OnlineAppImageRoot
    )

    Write-Step "Patching launcher to request administrator rights"
    & $SetExeExecutionLevelScript -Path (Join-Path $OnlineAppDir "$AppName.exe") -Level "requireAdministrator"

    # Stage the required external plugins into the (online) app-image plugins/ folder before packaging.
    # The online portable and installer are the default downloader package: core shell + required
    # download-workbench. The offline app-image later adds optional plugins to become full-offline.
    if (-not $SkipPlugins) {
        Write-Step "Staging official required external plugins into app-image plugins/"
        $requiredPlugins = @(Get-OfficialRequiredPlugins)
        $requiredCount = Stage-OfficialPlugins -AppDir $OnlineAppDir -Plugins $requiredPlugins `
            -PrebuiltPluginsDir $resolvedPrebuiltPluginsDir `
            -ProjectRoot $ProjectRoot -OfficialKeyId $OfficialKeyId -PrivateKeyFile $PrivateKeyFile `
            -SignatureToolJar $SignatureToolJar
        Write-Host ("    {0} official required plugin(s) staged under plugins/ (default downloader)." -f $requiredCount) -ForegroundColor Green

        Write-Step "Staging signed installer plugin catalog snapshot"
        Stage-InstallerPluginCatalogSnapshot -AppDir $OnlineAppDir -SignatureToolJar $SignatureToolJar
    } else {
        Write-Step "Skipping plugin staging (-SkipPlugins): core shell only; required plugin missing triggers recovery"
    }

    if (-not $SkipPortable) {
        Write-Step "Packaging online portable zip"
        Compress-Archive -Path $OnlineAppDir -DestinationPath $OnlineZipPath -Force
    }

    if (-not $SkipOfflinePortable) {
        Ensure-FfmpegPayload

        Write-Step "Building offline app-image"
        Ensure-Directory $OfflineAppImageRoot
        Copy-Item $OnlineAppDir $OfflineAppImageRoot -Recurse -Force
        if (-not $SkipPlugins) {
            Write-Step "Staging official required + optional external plugins into offline app-image plugins/"
            $fullOfflinePlugins = @(Get-OfficialDistributionPlugins -IncludeOptional)
            $fullOfflineCount = Stage-OfficialPlugins -AppDir $OfflineAppDir -Plugins $fullOfflinePlugins `
                -PrebuiltPluginsDir $resolvedPrebuiltPluginsDir `
                -ProjectRoot $ProjectRoot -OfficialKeyId $OfficialKeyId -PrivateKeyFile $PrivateKeyFile `
                -SignatureToolJar $SignatureToolJar
            Write-Host ("    {0} official plugin(s) staged under plugins/ (full-offline)." -f $fullOfflineCount) -ForegroundColor Green
        }
        $offlineFfmpegDir = Join-Path $OfflineAppDir "tools\ffmpeg"
        $offlineFfmpegLicenseDir = Join-Path $offlineFfmpegDir "licenses"
        Ensure-Directory $offlineFfmpegLicenseDir
        Copy-Item $FfmpegExe $offlineFfmpegDir -Force
        Copy-Item $FfprobeExe $offlineFfmpegDir -Force
        Copy-Item $FfmpegLicense $offlineFfmpegLicenseDir -Force

        Write-Step "Packaging offline portable zip"
        Compress-Archive -Path $OfflineAppDir -DestinationPath $OfflineZipPath -Force
    }

    if (-not $SkipInstaller) {
        Write-Step "Preparing Inno Setup admin loader"
        $innoCompiler = & $PrepareInnoAdminLoaderScript -CompilerPath $innoCompilerSource -OutputDirectory $InnoToolchainDir

        Write-Step "Building Windows setup"
        $installerPluginCatalogEnabled = if ($SkipPlugins) { "0" } else { "1" }
        Invoke-External $innoCompiler @(
            "/DAppVersion=$Version",
            "/DInstallerVersion=$InstallerVersion",
            "/DAppImageDir=$OnlineAppDir",
            "/DOutputDir=$OutDir",
            "/DInstallerPluginCatalogEnabled=$installerPluginCatalogEnabled",
            "/DSignatureToolJar=$SignatureToolJar",
            $InnoScript
        )
    }

    Write-Step "Done"
    Assert-NoPrivateKeyMaterial $OutDir
    Assert-NoPrivateKeyMaterial $OnlineAppDir
    if (Test-Path -LiteralPath $OfflineAppDir) { Assert-NoPrivateKeyMaterial $OfflineAppDir }
    if (-not $SkipPortable) {
        Write-Host "Online portable: $OnlineZipPath"
    }
    if (-not $SkipOfflinePortable) {
        Write-Host "Offline portable: $OfflineZipPath"
    }
    if (-not $SkipInstaller) {
        Write-Host "Windows setup : $SetupPath"
    }
    Write-Host "App dir       : $OnlineAppDir"
    if (-not $SkipPlugins) {
        Write-Host "Plugins       : required plugins in default package; optional plugins only in full-offline portable"
    } else {
        Write-Host "Plugins       : none bundled (-SkipPlugins; core shell recovery package)"
    }
    if ($MsiCultures -or $MsiVariants) {
        Write-Host "Note: MSI options are retained for compatibility and are ignored by the Inno Setup flow."
    }
} finally {
    Pop-Location
}
