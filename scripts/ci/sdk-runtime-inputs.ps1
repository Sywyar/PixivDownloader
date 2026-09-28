# 按统一分发集合和产物守卫保存当前候选的原始字节。
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [Parameter(Mandatory = $true)][string]$HostJar
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '../plugin-distribution-common.ps1')
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $OutputDirectory) { throw 'SDK input output directory must be new.' }
Assert-BootJarBoundary $HostJar
$inputs = @(foreach ($plugin in @(Get-OfficialDistributionPlugins -IncludeOptional)) {
    $artifact = Find-ModulePluginArtifact $plugin $root
    $descriptor = Assert-OfficialPluginArtifact $artifact $plugin
    Assert-ProguardProcessedArtifact $artifact
    [pscustomobject]@{ plugin=$plugin; artifact=$artifact; descriptor=$descriptor }
})
$plugins = Join-Path $OutputDirectory 'plugins'
[IO.Directory]::CreateDirectory($plugins) | Out-Null
Copy-Item -LiteralPath $HostJar -Destination (Join-Path $OutputDirectory 'host.jar')
$required = @(Get-OfficialRequiredPlugins | ForEach-Object { $_.Id })
$manifest = @(foreach ($inputArtifact in $inputs) {
    $plugin = $inputArtifact.plugin
    $descriptor = $inputArtifact.descriptor
    $name = "$($plugin.Module)-$($descriptor['plugin.version']).jar"
    $target = Join-Path $plugins $name
    Copy-Item -LiteralPath $inputArtifact.artifact -Destination $target
    [ordered]@{
        id=$plugin.Id; version=$descriptor['plugin.version']; requires=$descriptor['plugin.requires']
        required=($required -contains $plugin.Id); file="plugins/$name"
        size=(Get-Item -LiteralPath $target).Length; sha256=(Get-Sha256Hex $target)
    }
})
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'plugins-manifest.json'),
    (ConvertTo-Json -InputObject $manifest -Depth 5), [Text.UTF8Encoding]::new($false))
