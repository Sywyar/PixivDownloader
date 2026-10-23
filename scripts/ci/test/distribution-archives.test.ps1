$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$fixture = Join-Path $tempBase ('pixiv-archives-test-' + [guid]::NewGuid().ToString('N'))
$utf8 = [Text.UTF8Encoding]::new($false)

function Assert-Equal($Actual, $Expected) {
    if (($Actual -join '|') -cne ($Expected -join '|')) { throw "Expected [$Expected], got [$Actual]" }
}

function Write-FixtureFile([string]$Path, [string]$Text) {
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path)) | Out-Null
    [IO.File]::WriteAllText($Path, $Text, $utf8)
}

function Assert-Archive([string]$Path, [string]$Directory, [string]$Prefix = '') {
    $files = @(Get-ChildItem -LiteralPath $Directory -Recurse -File -Force)
    $expected = @($files | ForEach-Object { $Prefix + $_.FullName.Substring($Directory.Length + 1).Replace('\', '/') } | Sort-Object)
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        Assert-Equal @($archive.Entries | Where-Object { $_.Name } | ForEach-Object { $_.FullName.Replace('\', '/') } | Sort-Object) $expected
        foreach ($file in $files) {
            $name = $Prefix + $file.FullName.Substring($Directory.Length + 1).Replace('\', '/')
            $entry = @($archive.Entries | Where-Object { $_.FullName.Replace('\', '/') -ceq $name })[0]
            $stream = $entry.Open()
            $sha = [Security.Cryptography.SHA256]::Create()
            try { Assert-Equal ([BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '')) (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash }
            finally { $sha.Dispose(); $stream.Dispose() }
        }
    } finally { $archive.Dispose() }
}

try {
    foreach ($suffix in @('plain', '[source]')) {
        & {
            $root = Join-Path $fixture "java-$suffix"
            $scripts = Join-Path $root 'scripts'
            [IO.Directory]::CreateDirectory($scripts) | Out-Null
            foreach ($name in @('package-java-distributions.ps1', 'plugin-distribution-common.ps1')) {
                Copy-Item -LiteralPath (Join-Path $repo "scripts/$name") -Destination $scripts
            }
            . (Join-Path $scripts 'plugin-distribution-common.ps1')
            $expectedSets = @{
                'java-standard'=@(Get-OfficialDefaultInstalledPlugins | ForEach-Object { $_.Id } | Sort-Object)
                'full-offline'=@(Get-OfficialDistributionPlugins -IncludeOptional | ForEach-Object { $_.Id } | Sort-Object)
            }
            $inputJar = Join-Path $root 'inputs/core.jar'
            $pluginInputs = Join-Path $root 'inputs/plugins'
            $signatureTool = Join-Path $root 'inputs/signature.jar'
            Write-FixtureFile $inputJar 'exact core bytes'
            Write-FixtureFile $signatureTool 'signature boundary fixture'
            [IO.Directory]::CreateDirectory($pluginInputs) | Out-Null
            $producer = @'
param($Version, $PrebuiltJar, $PrebuiltPluginsDir, $SignatureToolJar, $OutputDir, [switch]$DefaultDownloader)
if ($PrebuiltJar -cne $inputJar -or $PrebuiltPluginsDir -cne $pluginInputs -or $SignatureToolJar -cne $signatureTool) { throw 'Packaging changed its input identity.' }
$selected = @(Get-OfficialDistributionPlugins -IncludeOptional:(!$DefaultDownloader))
[IO.Directory]::CreateDirectory((Join-Path $OutputDir 'plugins/provenance')) | Out-Null
Copy-Item -LiteralPath $PrebuiltJar -Destination (Join-Path $OutputDir "PixivDownload-$Version.jar")
foreach ($name in @('run.bat', 'run.sh', 'SHA256SUMS')) { Write-FixtureFile (Join-Path $OutputDir $name) $name }
$entries = @($selected | ForEach-Object {
    $file = "plugins/$($_.Id).jar"
    $artifact = Join-Path $OutputDir $file
    Write-FixtureFile $artifact $_.Id
    $hash = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash
    Write-FixtureFile "$artifact.sha256" $hash
    Write-FixtureFile "$artifact.sig" 'signature boundary fixture'
    Write-FixtureFile (Join-Path $OutputDir "plugins/provenance/$($_.Id).jar.pixiv-plugin-provenance") "status=VERIFIED`nsource=MARKET_CATALOG`n"
    @{id=$_.Id; file=$file; sha256=$hash}
})
if (-not $DefaultDownloader) {
    $first = Join-Path $OutputDir $entries[0].file
    switch ($fault) {
        'core' { Write-FixtureFile (Join-Path $OutputDir "PixivDownload-$Version.jar") 'wrong core' }
        'launcher' { Remove-Item -LiteralPath (Join-Path $OutputDir 'run.sh') }
        'aggregate' { Write-FixtureFile (Join-Path $OutputDir 'SHA256SUMS') '' }
        'set' { $entries = @($entries | Select-Object -Skip 1) }
        'duplicate' { $entries += $entries[0] }
        'extra' { Write-FixtureFile (Join-Path $OutputDir 'plugins/extra.jar') 'extra' }
        'checksum' { Write-FixtureFile "$first.sha256" ('0' * 64) }
        'artifact' { Write-FixtureFile $first 'changed bytes' }
        'signature' { Remove-Item -LiteralPath "$first.sig" }
        'provenance' { Write-FixtureFile (Join-Path $OutputDir "plugins/provenance/$([IO.Path]::GetFileName($first)).pixiv-plugin-provenance") "status=UNSIGNED_ALLOWED`nsource=LOCAL_UPLOAD`n" }
        'unsigned' { Write-FixtureFile (Join-Path $OutputDir 'plugins/LOCAL-UNSIGNED-BUILD.txt') 'local' }
    }
}
Write-FixtureFile (Join-Path $OutputDir 'plugins-manifest.json') (ConvertTo-Json -InputObject $entries -Depth 5)
'@
            Write-FixtureFile (Join-Path $scripts 'assemble-plugin-distribution.ps1') $producer
            $output = Join-Path $root 'build/distributions'
            $parameters = @{Version='1.2.3'; PrebuiltJar=$inputJar; PrebuiltPluginsDir=$pluginInputs; SignatureToolJar=$signatureTool; OutputDir=$output}
            $errors = @{
                core='core jar SHA-256 differs'; launcher='missing launch script'; aggregate='SHA256SUMS missing or empty';
                set='manifest plugin id set'; duplicate='duplicate plugin ids'; extra='staged plugin artifacts do not match';
                checksum='.sha256 sidecar differs'; artifact='actual artifact SHA-256 differs'; signature='Missing .sig sidecar';
                provenance='provenance status'; unsigned='LOCAL-UNSIGNED-BUILD.txt must not exist'
            }
            foreach ($fault in @('none', 'core', 'launcher', 'aggregate', 'set', 'duplicate', 'extra', 'checksum', 'artifact', 'signature', 'provenance', 'unsigned', 'none')) {
                $failed = $false
                try { & (Join-Path $scripts 'package-java-distributions.ps1') @parameters }
                catch {
                    if ($fault -eq 'none' -or -not $_.Exception.Message.Contains($errors[$fault])) { throw }
                    $failed = $true
                }
                Assert-Equal $failed ($fault -ne 'none')
                if ($failed) { Assert-Equal @(Get-ChildItem -LiteralPath $output -Filter '*.zip').Count 0; continue }
                Assert-Archive (Join-Path $output 'PixivDownload-1.2.3-java.zip') (Join-Path $output 'java-standard')
                Assert-Archive (Join-Path $output 'PixivDownload-1.2.3-full-offline.zip') (Join-Path $output 'full-offline')
                foreach ($layout in @('java-standard', 'full-offline')) {
                    $actual = [IO.File]::ReadAllText((Join-Path $output "$layout/plugins-manifest.json"), $utf8) | ConvertFrom-Json
                    Assert-Equal @($actual | ForEach-Object { $_.id } | Sort-Object) $expectedSets[$layout]
                }
            }
            Write-Host "PASS: Java ZIP layouts, exact bytes and pre-compression rejection ($suffix)."
        }
        & {
            $root = Join-Path $fixture "portable-$suffix"
            $scripts = Join-Path $root 'scripts'
            [IO.Directory]::CreateDirectory($scripts) | Out-Null
            foreach ($name in @('package-local.ps1', 'plugin-distribution-common.ps1', 'ffmpeg-release-integrity.ps1', 'package-local-plugin-staging.ps1', 'package-local-installer-catalog.ps1')) {
                Copy-Item -LiteralPath (Join-Path $repo "scripts/$name") -Destination $scripts
            }
            $metadata = 'pixivdownload-sdk-info/src/main/resources/META-INF/pixivdownload-sdk.properties'
            Write-FixtureFile (Join-Path $root $metadata) ([IO.File]::ReadAllText((Join-Path $repo $metadata), $utf8))
            Write-FixtureFile (Join-Path $scripts 'set-windows-exe-requested-execution-level.ps1') 'param($Path, $Level)'
            $inputJar = Join-Path $root 'inputs/core.jar'
            [IO.Directory]::CreateDirectory((Split-Path $inputJar)) | Out-Null
            $archive = [IO.Compression.ZipFile]::Open($inputJar, [IO.Compression.ZipArchiveMode]::Create)
            try {
                $writer = [IO.StreamWriter]::new($archive.CreateEntry('META-INF/pixivdownload-proguard.properties').Open(), $utf8)
                try { $writer.Write("formatVersion=1`nshrink=true`noptimize=true`nobfuscate=false`nproguard.version=fixture`n") }
                finally { $writer.Dispose() }
            } finally { $archive.Dispose() }
            $signatureTool = Join-Path $root 'inputs/signature.jar'
            Write-FixtureFile $signatureTool 'signature boundary fixture'
            $ffmpeg = Join-Path $root 'build/ffmpeg'
            $payload = Join-Path $root 'inputs/ffmpeg-windows-x64'
            foreach ($name in @('bin/ffmpeg.exe', 'bin/ffprobe.exe', 'licenses/ffmpeg-LGPLv2.1.txt', 'licenses/libwebp-COPYING.txt', 'licenses/libwebp-PATENTS.txt')) {
                Write-FixtureFile (Join-Path $payload $name) $name
            }
            [IO.Directory]::CreateDirectory($ffmpeg) | Out-Null
            $zip = Join-Path $ffmpeg 'ffmpeg.zip'
            [IO.Compression.ZipFile]::CreateFromDirectory($payload, $zip, [IO.Compression.CompressionLevel]::Optimal, $true)
            $manifest = @{schemaVersion=1; assets=@{'ffmpeg-windows-x64.zip'=@{expectedSizeBytes=(Get-Item -LiteralPath $zip).Length; sha256=(Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash}}}
            Write-FixtureFile (Join-Path $ffmpeg 'ffmpeg-release.json') ($manifest | ConvertTo-Json -Depth 5)
            Write-FixtureFile (Join-Path $ffmpeg 'ffmpeg-release.json.sig') 'signature boundary fixture'
            function java { $global:LASTEXITCODE = 0 }
            function curl.exe { throw 'Trusted cache must avoid network requests.' }
            function jlink { $global:LASTEXITCODE = 0 }
            function jpackage {
                $image = Join-Path $args[[Array]::IndexOf($args, '--dest') + 1] 'PixivDownload'
                Write-FixtureFile (Join-Path $image 'PixivDownload.exe') 'launcher fixture'
                Write-FixtureFile (Join-Path $image 'app/core.jar') 'core fixture'
                Write-FixtureFile (Join-Path $image 'runtime/payload [nested].txt') 'runtime fixture'
                $global:LASTEXITCODE = 0
            }
            $originalLocation = (Get-Location).Path
            foreach ($attempt in 1..2) {
                & (Join-Path $scripts 'package-local.ps1') -Version 1.2.3 -PrebuiltJar $inputJar -SignatureToolJar $signatureTool -SkipPlugins -SkipInstaller
                Assert-Equal (Get-Location).Path $originalLocation
                $online = Join-Path $root 'build/app-image-online/PixivDownload'
                $offline = Join-Path $root 'build/app-image-full/PixivDownload'
                Assert-Archive (Join-Path $root 'build/out/PixivDownload-1.2.3-win-x64-online-portable.zip') $online 'PixivDownload/'
                Assert-Archive (Join-Path $root 'build/out/PixivDownload-1.2.3-win-x64-portable.zip') $offline 'PixivDownload/'
                Assert-Equal ([IO.File]::ReadAllText((Join-Path $offline 'tools/ffmpeg/ffmpeg.exe'), $utf8)) 'bin/ffmpeg.exe'
                Assert-Equal ([IO.File]::ReadAllText((Join-Path $offline 'tools/ffmpeg/ffprobe.exe'), $utf8)) 'bin/ffprobe.exe'
                Assert-Equal @(Get-ChildItem -LiteralPath (Join-Path $offline 'tools/ffmpeg/licenses') -File).Count 3
            }
            function curl.exe {
                $destination = $args[[Array]::IndexOf($args, '-o') + 1]
                Copy-Item -LiteralPath $destination.Substring(0, $destination.Length - '.download'.Length) -Destination $destination
                $global:LASTEXITCODE = 0
            }
            $sentinel = Join-Path $ffmpeg 'unpack/preserved.txt'
            Write-FixtureFile $sentinel 'previous extraction'
            foreach ($fault in @('size', 'hash', 'signature')) {
                $manifest.assets['ffmpeg-windows-x64.zip'].expectedSizeBytes = (Get-Item -LiteralPath $zip).Length + [int]($fault -eq 'size')
                $manifest.assets['ffmpeg-windows-x64.zip'].sha256 = if ($fault -eq 'hash') { '0' * 64 } else { (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash }
                Write-FixtureFile (Join-Path $ffmpeg 'ffmpeg-release.json') ($manifest | ConvertTo-Json -Depth 5)
                function java { $global:LASTEXITCODE = [int]($fault -eq 'signature') }
                $failed = $false
                $expectedError = @{size='length mismatch'; hash='SHA-256 mismatch'; signature='signature verification failed'}[$fault]
                try { & (Join-Path $scripts 'package-local.ps1') -Version 1.2.3 -PrebuiltJar $inputJar -SignatureToolJar $signatureTool -SkipPlugins -SkipInstaller }
                catch { if (-not $_.Exception.Message.Contains($expectedError)) { throw }; $failed = $true }
                Assert-Equal $failed $true
                Assert-Equal ([IO.File]::ReadAllText($sentinel, $utf8)) 'previous extraction'
                Assert-Equal (Test-Path -LiteralPath (Join-Path $root 'build/out/PixivDownload-1.2.3-win-x64-portable.zip')) $false
                Assert-Equal (Get-Location).Path $originalLocation
            }
            function java { $global:LASTEXITCODE = 0 }
            function curl.exe { throw 'Trusted cache must avoid network requests.' }
            foreach ($entries in @(@('../escaped.txt'), @('duplicate.txt', 'duplicate.txt'))) {
                [IO.File]::Delete($zip)
                $archive = [IO.Compression.ZipFile]::Open($zip, [IO.Compression.ZipArchiveMode]::Create)
                try { foreach ($entry in $entries) { [void]$archive.CreateEntry($entry) } }
                finally { $archive.Dispose() }
                $manifest.assets['ffmpeg-windows-x64.zip'].expectedSizeBytes = (Get-Item -LiteralPath $zip).Length
                $manifest.assets['ffmpeg-windows-x64.zip'].sha256 = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash
                Write-FixtureFile (Join-Path $ffmpeg 'ffmpeg-release.json') ($manifest | ConvertTo-Json -Depth 5)
                $failed = $false
                try { & (Join-Path $scripts 'package-local.ps1') -Version 1.2.3 -PrebuiltJar $inputJar -SignatureToolJar $signatureTool -SkipPlugins -SkipInstaller }
                catch { if ($_.Exception.InnerException -isnot [IO.IOException]) { throw }; $failed = $true }
                Assert-Equal $failed $true
                Assert-Equal (Test-Path -LiteralPath (Join-Path $ffmpeg 'escaped.txt')) $false
                Assert-Equal (Test-Path -LiteralPath (Join-Path $root 'build/out/PixivDownload-1.2.3-win-x64-portable.zip')) $false
                Assert-Equal (Get-Location).Path $originalLocation
            }
            Write-Host "PASS: portable ZIP root, cached FFmpeg files and repeat packaging ($suffix)."
        }
    }
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    if ((Split-Path -Parent $resolved).TrimEnd('\', '/') -ne $tempBase -or (Split-Path -Leaf $resolved) -notlike 'pixiv-archives-test-*') { throw 'Unsafe fixture cleanup.' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
$global:LASTEXITCODE = 0
