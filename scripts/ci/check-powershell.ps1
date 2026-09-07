[CmdletBinding()]
param([string[]]$Paths)

$ErrorActionPreference = 'Stop'
if (-not $Paths) {
    $Paths = @(git ls-files -- '*.ps1' '*.psm1')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate PowerShell sources.' }
}
if (-not $Paths) { throw 'No PowerShell sources found.' }
$failures = @()
foreach ($path in $Paths) {
    $resolved = (Resolve-Path -LiteralPath $path).Path
    $tokens = $null
    $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($resolved, [ref]$tokens, [ref]$errors)
    foreach ($parseError in $errors) {
        $failures += "${path}:$($parseError.Extent.StartLineNumber): $($parseError.Message)"
    }
}
if ($failures.Count -gt 0) { throw ($failures -join [Environment]::NewLine) }
Write-Host "Parsed $($Paths.Count) PowerShell sources with PowerShell $($PSVersionTable.PSVersion)."
