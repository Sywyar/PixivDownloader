[CmdletBinding(DefaultParameterSetName = "Install")]
param(
    [Parameter(Mandatory = $true, ParameterSetName = "Reconcile")][switch]$ReconcileBundledDefaults,
    [Parameter(Mandatory = $true, ParameterSetName = "Install")]
    [Parameter(Mandatory = $true, ParameterSetName = "Reconcile")][string]$ManifestFile,
    [Parameter(Mandatory = $true, ParameterSetName = "Install")][string]$PluginIds,
    [Parameter(Mandatory = $true, ParameterSetName = "Install")]
    [Parameter(Mandatory = $true, ParameterSetName = "Reconcile")][string]$InstallDir,
    [Parameter(Mandatory = $true, ParameterSetName = "Install")][string]$ProgressFile,
    [Parameter(Mandatory = $true, ParameterSetName = "Install")][string]$SignatureToolJar,
    [Parameter(Mandatory = $true, ParameterSetName = "Install")][string]$JavaPath,
    [Parameter(ParameterSetName = "Install")][string]$ProxyUrl,
    [Parameter(ParameterSetName = "Install")][string]$CoreApiVersion = "1.0.0"
)

$ErrorActionPreference = "Stop"
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Write-State([string]$Value) {
    $dir = [System.IO.Path]::GetDirectoryName($ProgressFile)
    if (-not [string]::IsNullOrWhiteSpace($dir)) {
        [System.IO.Directory]::CreateDirectory($dir) | Out-Null
    }
    $bytes = $Utf8NoBom.GetBytes($Value + "`n")
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        try {
            $stream = [System.IO.File]::Open($ProgressFile, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::ReadWrite)
            try {
                $stream.Write($bytes, 0, $bytes.Length)
                $stream.SetLength($bytes.Length)
            } finally {
                $stream.Dispose()
            }
            return
        } catch {
            if ($attempt -eq 39) { throw }
            Start-Sleep -Milliseconds 50
        }
    }
}

function Escape-StateField([string]$Value) {
    if ($null -eq $Value) { return "" }
    return $Value.Replace("%", "%25").Replace("|", "%7C").Replace("`r", " ").Replace("`n", " ")
}

function Format-Error([System.Management.Automation.ErrorRecord]$ErrorRecord) {
    if ($null -eq $ErrorRecord) {
        return "Unknown optional plugin installation error."
    }
    $errorValue = $ErrorRecord.Exception
    if ($null -eq $errorValue) {
        return [string]$ErrorRecord
    }
    $message = $errorValue.GetType().FullName + ": " + $errorValue.Message
    if (($null -ne $ErrorRecord.InvocationInfo) -and ($ErrorRecord.InvocationInfo.ScriptLineNumber -gt 0)) {
        $message += " (line " + $ErrorRecord.InvocationInfo.ScriptLineNumber + ")"
    }
    return $message
}

function Get-Prop($Object, [string]$Name) {
    if ($null -eq $Object) { return $null }
    $prop = $Object.PSObject.Properties[$Name]
    if ($null -eq $prop) { return $null }
    return $prop.Value
}

function Parse-VersionPair([string]$Version) {
    if ([string]::IsNullOrWhiteSpace($Version) -or $Version.Trim() -eq "*") { return @(0, 0) }
    $parts = $Version.Split(".")
    $major = 0
    $minor = 0
    if ($parts.Length -ge 1) { [void][int]::TryParse($parts[0], [ref]$major) }
    if ($parts.Length -ge 2) { [void][int]::TryParse($parts[1], [ref]$minor) }
    return @($major, $minor)
}

function Test-Compatible([string]$Required) {
    if ([string]::IsNullOrWhiteSpace($Required) -or $Required.Trim() -eq "*") { return $true }
    $core = Parse-VersionPair $CoreApiVersion
    $requiredPair = Parse-VersionPair $Required
    return ($core[0] -eq $requiredPair[0]) -and ($core[1] -ge $requiredPair[1])
}

function Test-VersionSatisfied([string]$Requirement, [string]$Actual) {
    if ([string]::IsNullOrWhiteSpace($Requirement) -or $Requirement.Trim() -eq "*") { return $true }
    $req = Parse-VersionPair $Requirement
    $act = Parse-VersionPair $Actual
    return ($act[0] -eq $req[0]) -and ($act[1] -ge $req[1])
}

function Parse-Dependencies($Package) {
    $result = New-Object System.Collections.Generic.List[object]
    foreach ($raw in @(Get-Prop $Package "dependencies")) {
        if ([string]::IsNullOrWhiteSpace([string]$raw)) { continue }
        foreach ($token in ([string]$raw).Split(",")) {
            $dependency = $token.Trim()
            if ($dependency -eq "") { continue }
            $pluginId = $dependency
            $versionSupport = "*"
            $at = $dependency.IndexOf("@")
            if ($at -ge 0) {
                $pluginId = $dependency.Substring(0, $at)
                if ($dependency.Length -gt $at + 1) {
                    $versionSupport = $dependency.Substring($at + 1)
                }
            }
            $optional = $false
            if ($pluginId.EndsWith("?")) {
                $optional = $true
                $pluginId = $pluginId.Substring(0, $pluginId.Length - 1)
            }
            $result.Add([pscustomobject]@{
                PluginId = $pluginId.Trim()
                VersionSupport = $versionSupport.Trim()
                Optional = $optional
            })
        }
    }
    return $result.ToArray()
}

function Find-Entry($Manifest, [string]$PluginId) {
    foreach ($entry in @(Get-Prop $Manifest "entries")) {
        if ([string](Get-Prop $entry "pluginId") -eq $PluginId) {
            return $entry
        }
    }
    return $null
}

function Select-Package($Entry, [string]$Requirement) {
    $packages = @(Get-Prop $Entry "packages")
    if ($packages.Count -eq 0) { return $null }
    $market = Get-Prop $Entry "market"
    $latest = [string](Get-Prop $market "latestVersion")
    if (-not [string]::IsNullOrWhiteSpace($latest)) {
        foreach ($pkg in $packages) {
            if (((Get-Prop $pkg "version") -eq $latest) -and (Test-VersionSatisfied $Requirement $latest)) {
                return $pkg
            }
        }
    }
    foreach ($pkg in $packages) {
        $version = [string](Get-Prop $pkg "version")
        if (Test-VersionSatisfied $Requirement $version) {
            return $pkg
        }
    }
    return $null
}

function Assert-InstallablePackage($Entry, $Package) {
    $pluginId = [string](Get-Prop $Entry "pluginId")
    if ($null -eq $Package) { throw "No installable package for $pluginId." }
    if ([string]::IsNullOrWhiteSpace([string](Get-Prop $Package "version"))) { throw "Missing version for $pluginId." }
    if ([string]::IsNullOrWhiteSpace([string](Get-Prop $Package "packageUrl"))) { throw "Missing packageUrl for $pluginId." }
    if ([string]::IsNullOrWhiteSpace([string](Get-Prop $Package "sha256"))) { throw "Missing sha256 for $pluginId." }
    if ([int64](Get-Prop $Package "expectedSizeBytes") -le 0) { throw "Missing expectedSizeBytes for $pluginId." }
    if ($null -eq (Get-Prop $Package "signature")) { throw "Missing signature for $pluginId." }
    if (-not (Test-Compatible ([string](Get-Prop $Package "requiredCoreApi")))) {
        throw "$pluginId requires Plugin API " + [string](Get-Prop $Package "requiredCoreApi")
    }
}

function Add-Plan($Manifest, [string]$PluginId, [string]$Requirement, $Plan, $Visiting, $Visited) {
    if ($Visited.ContainsKey($PluginId)) { return }
    if ($Visiting.ContainsKey($PluginId)) { throw "Dependency cycle includes $PluginId." }
    $entry = Find-Entry $Manifest $PluginId
    if ($null -eq $entry) { throw "Dependency not found in catalog: $PluginId." }
    $pkg = Select-Package $entry $Requirement
    Assert-InstallablePackage $entry $pkg
    $Visiting[$PluginId] = $true
    foreach ($dependency in (Parse-Dependencies $pkg)) {
        if ($dependency.Optional) { continue }
        Add-Plan $Manifest $dependency.PluginId $dependency.VersionSupport $Plan $Visiting $Visited
    }
    $Visiting.Remove($PluginId)
    $Visited[$PluginId] = $true
    $Plan.Add([pscustomobject]@{
        Entry = $entry
        Package = $pkg
        PluginId = $PluginId
        Version = [string](Get-Prop $pkg "version")
    })
}

function Get-PackageExtension([string]$Url) {
    $path = ([System.Uri]$Url).AbsolutePath.ToLowerInvariant()
    if ($path.EndsWith(".jar")) { return ".jar" }
    if ($path.EndsWith(".zip")) { return ".zip" }
    throw "Package URL must end with .jar or .zip: $Url"
}

function Import-ZipFileAssembly {
    if (([System.Management.Automation.PSTypeName]"System.IO.Compression.ZipFile").Type) {
        return
    }
    foreach ($assemblyName in @("System.IO.Compression.FileSystem", "System.IO.Compression.ZipFile", "System.IO.Compression")) {
        try {
            Add-Type -AssemblyName $assemblyName -ErrorAction Stop
            if (([System.Management.Automation.PSTypeName]"System.IO.Compression.ZipFile").Type) {
                return
            }
        } catch {
            # Try the next assembly name.
        }
    }
    throw "Unable to load System.IO.Compression.ZipFile assembly."
}

function Read-PluginDescriptorFromPackage([string]$Path) {
    Import-ZipFileAssembly
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entry = $archive.GetEntry("plugin.properties")
        if ($null -eq $entry) {
            return [pscustomobject]@{ PluginId = ""; Version = "" }
        }
        $reader = New-Object System.IO.StreamReader($entry.Open(), [System.Text.Encoding]::UTF8)
        try {
            $text = $reader.ReadToEnd()
        } finally {
            $reader.Dispose()
        }
    } finally {
        $archive.Dispose()
    }
    $pluginId = ""
    $version = ""
    foreach ($line in ($text -split "`r?`n")) {
        $trimmed = $line.Trim()
        if ($trimmed -eq "" -or $trimmed.StartsWith("#")) { continue }
        $idx = $trimmed.IndexOf("=")
        if ($idx -lt 1) { continue }
        $key = $trimmed.Substring(0, $idx).Trim()
        $value = $trimmed.Substring($idx + 1).Trim()
        if ($key -eq "plugin.id") { $pluginId = $value }
        if ($key -eq "plugin.version") { $version = $value }
    }
    return [pscustomobject]@{ PluginId = $pluginId; Version = $version }
}

function Read-PluginIdFromPackage([string]$Path) {
    return (Read-PluginDescriptorFromPackage $Path).PluginId
}

function Remove-ArtifactWithCompanions([string]$ArtifactPath) {
    $artifact = Get-Item -LiteralPath $ArtifactPath -ErrorAction Stop
    $provenance = Join-Path (Join-Path $artifact.Directory.FullName "provenance") ($artifact.Name + ".pixiv-plugin-provenance")
    foreach ($path in @(
        $artifact.FullName,
        "$($artifact.FullName).sha256",
        "$($artifact.FullName).sig",
        "$($artifact.FullName).sig.json",
        "$($artifact.FullName).pixiv-plugin-provenance",
        $provenance
    )) {
        if (Test-Path -LiteralPath $path) {
            Remove-Item -LiteralPath $path -Force -ErrorAction Stop
        }
    }
}

function Reconcile-BundledDefaultPlugins([string]$BundledManifestFile, [string]$ApplicationDir) {
    if (-not (Test-Path -LiteralPath $BundledManifestFile -PathType Leaf)) {
        throw "Bundled plugin manifest not found: $BundledManifestFile"
    }
    $pluginsDir = Join-Path $ApplicationDir "plugins"
    if (-not (Test-Path -LiteralPath $pluginsDir -PathType Container)) {
        throw "Bundled plugin directory not found: $pluginsDir"
    }
    # Windows PowerShell 5.1 can preserve a JSON array as one pipeline object when it is wrapped
    # directly in @(...). Assign first, then normalize, so every manifest entry is iterated.
    $parsedManifest = Get-Content -LiteralPath $BundledManifestFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $entries = @($parsedManifest)
    if ($entries.Count -eq 0) {
        throw "Bundled plugin manifest is empty."
    }
    $seenIds = @{}
    foreach ($entry in $entries) {
        $pluginId = [string](Get-Prop $entry "id")
        $version = [string](Get-Prop $entry "version")
        $file = [string](Get-Prop $entry "file")
        if ([string]::IsNullOrWhiteSpace($pluginId) -or
            [string]::IsNullOrWhiteSpace($version) -or
            [string]::IsNullOrWhiteSpace($file)) {
            throw "Bundled plugin manifest entry is missing id, version, or file."
        }
        if ($seenIds.ContainsKey($pluginId)) {
            throw "Bundled plugin manifest contains duplicate id: $pluginId"
        }
        $seenIds[$pluginId] = $true
        $fileName = [System.IO.Path]::GetFileName($file)
        $extension = [System.IO.Path]::GetExtension($fileName).ToLowerInvariant()
        if ([System.IO.Path]::IsPathRooted($file) -or $fileName -ne $file -or
            ($extension -ne ".jar" -and $extension -ne ".zip")) {
            throw "Unsafe bundled plugin file name for ${pluginId}: $file"
        }
        $stableArtifact = Join-Path $pluginsDir $fileName
        if (-not (Test-Path -LiteralPath $stableArtifact -PathType Leaf)) {
            throw "Bundled plugin artifact not found for ${pluginId}: $stableArtifact"
        }
        $descriptor = Read-PluginDescriptorFromPackage $stableArtifact
        if ($descriptor.PluginId -ne $pluginId -or $descriptor.Version -ne $version) {
            throw "Bundled plugin descriptor does not match manifest for $pluginId."
        }
        Remove-SupersededInstalledPlugins $pluginsDir $pluginId $stableArtifact
    }
}

function Remove-SupersededInstalledPlugins([string]$PluginsDir, [string]$PluginId, [string]$KeepPath) {
    if (-not (Test-Path -LiteralPath $PluginsDir -PathType Container)) { return }
    $keepFullPath = ""
    if (-not [string]::IsNullOrWhiteSpace($KeepPath) -and (Test-Path -LiteralPath $KeepPath -PathType Leaf)) {
        $keepFullPath = (Get-Item -LiteralPath $KeepPath).FullName
    }
    foreach ($artifact in Get-ChildItem -LiteralPath $PluginsDir -File -ErrorAction SilentlyContinue) {
        $name = $artifact.Name.ToLowerInvariant()
        if (-not ($name.EndsWith(".jar") -or $name.EndsWith(".zip"))) { continue }
        if ($keepFullPath -ne "" -and $artifact.FullName -eq $keepFullPath) { continue }
        try {
            $existingId = Read-PluginIdFromPackage $artifact.FullName
        } catch {
            # Unreadable packages are left untouched; the runtime will report them separately.
            continue
        }
        if ($existingId -eq $PluginId) {
            Remove-ArtifactWithCompanions $artifact.FullName
        }
    }
}

function Download-Package($Item, [string]$Target) {
    $url = [string](Get-Prop $Item.Package "packageUrl")
    $expectedSize = [int64](Get-Prop $Item.Package "expectedSizeBytes")
    [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12
    $request = [System.Net.HttpWebRequest][System.Net.WebRequest]::Create($url)
    $request.Method = "GET"
    $request.UserAgent = "PixivDownload/setup-plugin-install"
    $request.AllowAutoRedirect = $true
    $request.Timeout = 600000
    $request.ReadWriteTimeout = 600000
    if ([string]::IsNullOrWhiteSpace($ProxyUrl)) {
        $systemProxy = [System.Net.WebRequest]::GetSystemWebProxy()
        if ($null -ne $systemProxy) {
            $systemProxy.Credentials = [System.Net.CredentialCache]::DefaultNetworkCredentials
            $request.Proxy = $systemProxy
        }
    } else {
        $proxy = New-Object System.Net.WebProxy($ProxyUrl, $true)
        $proxy.Credentials = [System.Net.CredentialCache]::DefaultNetworkCredentials
        $request.Proxy = $proxy
    }
    $response = $request.GetResponse()
    try {
        $stream = $response.GetResponseStream()
        $file = [System.IO.File]::Open($Target, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        try {
            $buffer = New-Object byte[] 65536
            [int64]$downloaded = 0
            [int64]$total = $response.ContentLength
            while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                $file.Write($buffer, 0, $read)
                $downloaded += $read
                if ($downloaded -gt $expectedSize) { throw "Downloaded package exceeds expected size." }
                $denominator = if ($total -gt 0) { $total } else { $expectedSize }
                $percent = if ($denominator -gt 0) { [int][Math]::Floor(($downloaded / [double]$denominator) * 100) } else { 0 }
                if ($percent -gt 100) { $percent = 100 }
                Write-State ("PROGRESS|{0}|{1}|{2}" -f $downloaded, $denominator, $percent)
            }
        } finally {
            $file.Dispose()
            $stream.Dispose()
        }
    } finally {
        $response.Close()
    }
}

function Write-SignatureFile($Package, [string]$Path) {
    $signature = Get-Prop $Package "signature"
    $json = $signature | ConvertTo-Json -Compress -Depth 4
    [System.IO.File]::WriteAllText($Path, $json + "`n", (New-Object System.Text.UTF8Encoding($false)))
}

function Verify-Package($Item, [string]$ArtifactPath, [string]$SignaturePath) {
    if (-not (Test-Path -LiteralPath $JavaPath -PathType Leaf)) { throw "Bundled Java runtime not found: $JavaPath" }
    if (-not (Test-Path -LiteralPath $SignatureToolJar -PathType Leaf)) { throw "Signature tool not found: $SignatureToolJar" }
    & $JavaPath "-cp" $SignatureToolJar "top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool" `
        "verify-artifact" `
        "--artifact" $ArtifactPath `
        "--signature" $SignaturePath `
        "--plugin-id" $Item.PluginId `
        "--version" $Item.Version `
        "--expected-size" ([string](Get-Prop $Item.Package "expectedSizeBytes")) `
        "--sha256" ([string](Get-Prop $Item.Package "sha256")) `
        "--policy" "official" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Artifact signature verification failed for $($Item.PluginId)." }
}

function Get-Sha256Hex([string]$Path) {
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Write-Provenance($Item, [string]$ArtifactPath, [string]$SignaturePath) {
    $artifact = Get-Item -LiteralPath $ArtifactPath
    $expectedSizeBytes = [int64](Get-Prop $Item.Package "expectedSizeBytes")
    $expectedSha256 = [string](Get-Prop $Item.Package "sha256")
    $artifactSizeBytes = [int64]$artifact.Length
    $artifactSha256 = Get-Sha256Hex $ArtifactPath
    if ($artifactSizeBytes -ne $expectedSizeBytes) {
        throw "Installed artifact size mismatch for $($Item.PluginId)."
    }
    if (-not [string]::Equals($artifactSha256, $expectedSha256, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Installed artifact SHA-256 mismatch for $($Item.PluginId)."
    }
    $signature = Get-Prop $Item.Package "signature"
    $provenanceDir = Join-Path $artifact.Directory.FullName "provenance"
    [System.IO.Directory]::CreateDirectory($provenanceDir) | Out-Null
    $sidecar = Join-Path $provenanceDir ($artifact.Name + ".pixiv-plugin-provenance")
    $verifiedAt = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
    $lines = @(
        "formatVersion=1",
        "source=MARKET_CATALOG",
        "repositoryId=official",
        "officialRepository=true",
        "expectedSizeBytes=$expectedSizeBytes",
        "expectedSha256=$expectedSha256",
        "artifactSizeBytes=$artifactSizeBytes",
        "artifactSha256=$artifactSha256",
        "signature.formatVersion=$([string](Get-Prop $signature "formatVersion"))",
        "signature.algorithm=$([string](Get-Prop $signature "algorithm"))",
        "signature.keyId=$([string](Get-Prop $signature "keyId"))",
        "signature.value=$([string](Get-Prop $signature "value"))",
        "status=VERIFIED",
        "keyId=$([string](Get-Prop $signature "keyId"))",
        "publisher=PixivDownloader",
        "trustLabel=PixivDownloader official plugin root",
        "verifiedAt=$verifiedAt",
        "diagnosticCode=VERIFIED"
    )
    [System.IO.File]::WriteAllText($sidecar, (($lines -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))
}

function Enable-Plugin([string]$PluginId) {
    $configDir = Join-Path $InstallDir "config"
    [System.IO.Directory]::CreateDirectory($configDir) | Out-Null
    $configPath = Join-Path $configDir "config.yaml"
    $key = "plugins.$PluginId.enabled"
    $line = "${key}: true"
    $lines = New-Object System.Collections.Generic.List[string]
    $updated = $false
    if (Test-Path -LiteralPath $configPath -PathType Leaf) {
        foreach ($existing in [System.IO.File]::ReadAllLines($configPath, [System.Text.Encoding]::UTF8)) {
            if ($existing -match ("^\s*" + [System.Text.RegularExpressions.Regex]::Escape($key) + "\s*:")) {
                $lines.Add($line)
                $updated = $true
            } else {
                $lines.Add($existing)
            }
        }
    }
    if (-not $updated) {
        $lines.Add($line)
    }
    [System.IO.File]::WriteAllText($configPath, (($lines -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))
}

if ($ReconcileBundledDefaults) {
    try {
        Reconcile-BundledDefaultPlugins $ManifestFile $InstallDir
        exit 0
    } catch {
        Write-Error (Format-Error $_)
        exit 1
    }
}

try {
    $manifest = Get-Content -LiteralPath $ManifestFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $selected = @($PluginIds.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" })
    $plan = New-Object System.Collections.Generic.List[object]
    $visited = @{}
    foreach ($pluginId in $selected) {
        Add-Plan $manifest $pluginId "*" $plan @{} $visited
    }
    $pluginsDir = Join-Path $InstallDir "plugins"
    [System.IO.Directory]::CreateDirectory($pluginsDir) | Out-Null
    $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("pixivdownload-setup-plugins-" + [System.Guid]::NewGuid().ToString("N"))
    [System.IO.Directory]::CreateDirectory($tempDir) | Out-Null
    try {
        $total = $plan.Count
        $index = 0
        foreach ($item in $plan) {
            $index++
            Write-State ("PLUGIN|{0}|{1}|{2}" -f $item.PluginId, $index, $total)
            $ext = Get-PackageExtension ([string](Get-Prop $item.Package "packageUrl"))
            $download = Join-Path $tempDir ($item.PluginId + "-" + $item.Version + $ext)
            $sigTemp = "$download.sig"
            Write-SignatureFile $item.Package $sigTemp
            Download-Package $item $download
            Verify-Package $item $download $sigTemp
            $target = Join-Path $pluginsDir ($item.PluginId + "-" + $item.Version + $ext)
            Copy-Item -LiteralPath $download -Destination $target -Force
            Copy-Item -LiteralPath $sigTemp -Destination "$target.sig" -Force
            $shaLine = ([string](Get-Prop $item.Package "sha256")) + "  " + [System.IO.Path]::GetFileName($target) + "`n"
            [System.IO.File]::WriteAllText("$target.sha256", $shaLine, (New-Object System.Text.UTF8Encoding($false)))
            Write-Provenance $item $target "$target.sig"
            Remove-SupersededInstalledPlugins $pluginsDir $item.PluginId $target
            Enable-Plugin $item.PluginId
        }
    } finally {
        Remove-Item -LiteralPath $tempDir -Recurse -Force -ErrorAction SilentlyContinue
    }
    Write-State ("DONE|{0}" -f $plan.Count)
    exit 0
} catch {
    Write-State ("ERROR|" + (Escape-StateField (Format-Error $_)))
    exit 1
}
