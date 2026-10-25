[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$RuntimeHome)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$runtimeJava = Join-Path $RuntimeHome 'bin/java.exe'
if (-not (Test-Path -LiteralPath $runtimeJava -PathType Leaf)) { throw "Missing bundled Java: $runtimeJava" }
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$work = Join-Path $tempBase ('pixiv-runtime-trust-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($work) | Out-Null
try {
    & javac --release 17 -encoding UTF-8 -d $work `
        (Join-Path $PSScriptRoot 'RemoveDistrustedCa.java') `
        (Join-Path $PSScriptRoot 'RuntimeTrustTest.java')
    if ($LASTEXITCODE -ne 0) { throw 'Runtime trust probe compilation failed.' }
    $fixtures = Join-Path $PSScriptRoot 'fixtures'
    & $runtimeJava '-Dfile.encoding=UTF-8' '-Dcom.sun.security.enableAIAcaIssuers=false' `
        -cp $work RuntimeTrustTest $fixtures $work
    if ($LASTEXITCODE -ne 0) { throw 'Runtime trust verification failed.' }
} finally {
    $resolved = [IO.Path]::GetFullPath($work)
    if ((Split-Path -Parent $resolved).TrimEnd('\', '/') -ne $tempBase -or
        (Split-Path -Leaf $resolved) -notlike 'pixiv-runtime-trust-*') { throw 'Unsafe probe cleanup.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
