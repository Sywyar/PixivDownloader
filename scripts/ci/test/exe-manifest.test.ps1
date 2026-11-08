$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$patcher = Join-Path $repo 'scripts/set-windows-exe-requested-execution-level.ps1'
$compiler = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$fixture = Join-Path $tempBase ('pixiv-manifest-test-' + [Guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($fixture) | Out-Null
    $source = Join-Path $fixture 'Program.cs'
    $executable = Join-Path $fixture 'launcher [test].exe'
    [IO.File]::WriteAllText($source, 'class Program { static void Main() {} }', [Text.UTF8Encoding]::new($false))
    & $compiler /nologo /target:winexe /nowin32manifest "/out:$executable" $source
    if ($LASTEXITCODE -ne 0) { throw 'Manifest fixture compilation failed.' }

    & $patcher -Path $executable -Level requireAdministrator
    $manifestTool = Get-Command mt.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
    if (-not $manifestTool) {
        $manifestTool = Get-ChildItem -LiteralPath 'C:/Program Files (x86)/Windows Kits/10/bin' -Recurse -Filter mt.exe |
            Where-Object { $_.FullName -like '*\x64\mt.exe' } | Sort-Object FullName -Descending |
            Select-Object -First 1 -ExpandProperty FullName
    }
    if (-not $manifestTool) { throw 'Manifest Tool not found for native verification.' }
    $manifest = Join-Path $fixture 'read.manifest'
    foreach ($level in @('requireAdministrator', 'asInvoker', 'highestAvailable')) {
        & $patcher -Path $executable -Level $level
        & $manifestTool -nologo "-inputresource:$executable;#1" "-out:$manifest"
        if ($LASTEXITCODE -ne 0) { throw 'Patched manifest cannot be extracted.' }
        [xml]$document = [IO.File]::ReadAllText($manifest)
        $node = $document.SelectSingleNode("//*[local-name()='requestedExecutionLevel']")
        if ($node.level -ne $level -or $node.uiAccess -ne 'false') { throw 'Incorrect execution level.' }
    }

    $description = $document.CreateElement('description', 'urn:schemas-microsoft-com:asm.v1')
    $description.InnerText = 'Preserve unrelated manifest content'
    $null = $document.DocumentElement.AppendChild($description)
    $document.Save($manifest)
    & $manifestTool -nologo -manifest $manifest "-outputresource:$executable;#1"
    if ($LASTEXITCODE -ne 0) { throw 'Existing manifest fixture could not be embedded.' }
    & $patcher -Path $executable -Level requireAdministrator
    & $manifestTool -nologo "-inputresource:$executable;#1" "-out:$manifest"
    if ($LASTEXITCODE -ne 0) { throw 'Updated existing manifest cannot be extracted.' }
    [xml]$document = [IO.File]::ReadAllText($manifest)
    if ($document.SelectSingleNode("//*[local-name()='description']").InnerText -ne $description.InnerText) {
        throw 'Unrelated manifest content changed.'
    }

    $invalid = Join-Path $fixture 'invalid.exe'
    [IO.File]::WriteAllText($invalid, 'not a PE file', [Text.UTF8Encoding]::new($false))
    $before = (Get-FileHash -LiteralPath $invalid).Hash
    $rejected = $false
    try { & $patcher -Path $invalid } catch { $rejected = $true }
    if (-not $rejected -or (Get-FileHash -LiteralPath $invalid).Hash -ne $before) {
        throw 'Invalid executable was accepted or modified.'
    }
    Write-Host 'PASS: missing and existing manifests, execution levels, content preservation and invalid PE rejection.'
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    if ((Split-Path -Parent $resolved).TrimEnd('\', '/') -ne $tempBase -or
        (Split-Path -Leaf $resolved) -notlike 'pixiv-manifest-test-*') { throw 'Unsafe manifest fixture cleanup.' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
$global:LASTEXITCODE = 0
