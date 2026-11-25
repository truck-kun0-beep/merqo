# Parses a PowerShell script and reports the syntax errors, if any.
# Usage: scripts\check-ps1-syntax.ps1 scripts\check-live.ps1
param(
    [Parameter(Mandatory = $true)]
    [string[]] $Path
)

$failed = $false

foreach ($file in $Path) {
    $errors = $null
    $tokens = $null
    [System.Management.Automation.Language.Parser]::ParseFile(
        (Resolve-Path $file).Path,
        [ref]$tokens,
        [ref]$errors) | Out-Null

    if ($errors.Count -eq 0) {
        Write-Output ("PARSE OK  " + $file)
    } else {
        $failed = $true
        Write-Output ("PARSE FAIL " + $file)
        foreach ($error in $errors) {
            Write-Output ("  line " + $error.Extent.StartLineNumber + ": " + $error.Message)
        }
    }
}

if ($failed) {
    exit 1
}
exit 0
