function Write-State([string]$Value) {
    $directory = [System.IO.Path]::GetDirectoryName($ProgressFile)
    if (-not [string]::IsNullOrWhiteSpace($directory)) {
        [System.IO.Directory]::CreateDirectory($directory) | Out-Null
    }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value + "`n")
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        try {
            $stream = [System.IO.File]::Open($ProgressFile, [System.IO.FileMode]::Create,
                [System.IO.FileAccess]::Write, [System.IO.FileShare]::ReadWrite)
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
