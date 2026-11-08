[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Path,
    [ValidateSet("asInvoker", "highestAvailable", "requireAdministrator")]
    [string]$Level = "requireAdministrator"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $Path)) {
    throw "Executable not found: $Path"
}

$targetFile = Get-Item -LiteralPath $Path
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
        if (-not (Test-Path -LiteralPath $root)) {
            continue
        }

        $candidate = Get-ChildItem -LiteralPath $root -Recurse -Filter "mt.exe" -ErrorAction SilentlyContinue |
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
if (-not ('PixivDownload.ManifestResource' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
namespace PixivDownload {
    public static class ManifestResource {
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr LoadLibraryEx(string path, IntPtr file, uint flags);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr FindResource(IntPtr module, IntPtr name, IntPtr type);
        [DllImport("kernel32.dll")]
        private static extern bool FreeLibrary(IntPtr module);
        public static bool Exists(string path) {
            IntPtr module = LoadLibraryEx(path, IntPtr.Zero, 0x22);
            if (module == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
            try {
                if (FindResource(module, new IntPtr(1), new IntPtr(24)) != IntPtr.Zero) return true;
                int error = Marshal.GetLastWin32Error();
                if (error == 1812 || error == 1813 || error == 1814) return false;
                throw new Win32Exception(error);
            } finally { FreeLibrary(module); }
        }
    }
}
'@
}
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$tempDir = Join-Path $tempBase ([System.Guid]::NewGuid().ToString("N"))
$manifestPath = Join-Path $tempDir "launcher.manifest"

try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null

    $xml = New-Object System.Xml.XmlDocument
    $xml.PreserveWhitespace = $true
    if ([PixivDownload.ManifestResource]::Exists($targetFile.FullName)) {
        & $mtCommand "-nologo" "-inputresource:$Path;#1" "-out:$manifestPath"
        if ($LASTEXITCODE -ne 0) { throw "Failed to extract manifest from $Path" }
        $xml.Load($manifestPath)
    } else {
        $xml.LoadXml(@'
<?xml version="1.0" encoding="UTF-8"?>
<assembly xmlns="urn:schemas-microsoft-com:asm.v1" manifestVersion="1.0">
  <trustInfo xmlns="urn:schemas-microsoft-com:asm.v3">
    <security><requestedPrivileges>
      <requestedExecutionLevel level="asInvoker" uiAccess="false" />
    </requestedPrivileges></security>
  </trustInfo>
</assembly>
'@)
    }

    $namespaceManager = New-Object System.Xml.XmlNamespaceManager($xml.NameTable)
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
    if (Test-Path -LiteralPath $tempDir) {
        $resolved = [IO.Path]::GetFullPath($tempDir)
        if ((Split-Path -Parent $resolved).TrimEnd('\', '/') -ne $tempBase) { throw 'Unsafe manifest cleanup.' }
        Remove-Item -Recurse -Force -LiteralPath $resolved
    }
}
