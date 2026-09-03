[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Path,
    [ValidateSet("asInvoker", "highestAvailable", "requireAdministrator")]
    [string]$Level = "requireAdministrator"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $Path)) {
    throw "Executable not found: $Path"
}

$targetFile = Get-Item $Path
if ($targetFile.IsReadOnly) {
    $targetFile.IsReadOnly = $false
}

function Get-MtCommand {
    $command = Get-Command "mt.exe" -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $candidateRoots = @(
        "C:\Program Files (x86)\Windows Kits\10\bin",
        "C:\Program Files\Windows Kits\10\bin"
    )

    foreach ($root in $candidateRoots) {
        if (-not (Test-Path $root)) {
            continue
        }

        $candidate = Get-ChildItem $root -Recurse -Filter "mt.exe" -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -like "*\x64\mt.exe" } |
            Sort-Object FullName -Descending |
            Select-Object -First 1
        if ($candidate) {
            return $candidate.FullName
        }
    }

    throw "Could not find mt.exe. Install the Windows SDK Manifest Tool."
}

$mtCommand = Get-MtCommand
$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ([System.Guid]::NewGuid().ToString("N"))
$manifestPath = Join-Path $tempDir "launcher.manifest"

try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null

    & $mtCommand "-nologo" "-inputresource:$Path;#1" "-out:$manifestPath"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to extract manifest from $Path"
    }

    $xml = New-Object System.Xml.XmlDocument
    $xml.PreserveWhitespace = $true
    $xml.Load($manifestPath)

    $namespaceManager = New-Object System.Xml.XmlNamespaceManager($xml.NameTable)
    $namespaceManager.AddNamespace("asmv1", "urn:schemas-microsoft-com:asm.v1")
    $namespaceManager.AddNamespace("asmv3", "urn:schemas-microsoft-com:asm.v3")

    $executionLevelNode = $xml.SelectSingleNode("//asmv3:requestedExecutionLevel", $namespaceManager)
    if (-not $executionLevelNode) {
        throw "The executable manifest does not contain a requestedExecutionLevel node."
    }

    $null = $executionLevelNode.SetAttribute("level", $Level)
    $null = $executionLevelNode.SetAttribute("uiAccess", "false")

    $settings = New-Object System.Xml.XmlWriterSettings
    $settings.Indent = $true
    $settings.IndentChars = "  "
    $settings.Encoding = New-Object System.Text.UTF8Encoding($false)

    $writer = [System.Xml.XmlWriter]::Create($manifestPath, $settings)
    try {
        $xml.Save($writer)
    } finally {
        $writer.Dispose()
    }

    & $mtCommand "-nologo" "-manifest" $manifestPath "-outputresource:$Path;#1"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to embed updated manifest into $Path"
    }
} finally {
    if (Test-Path $tempDir) {
        Remove-Item -Recurse -Force $tempDir
    }
}
