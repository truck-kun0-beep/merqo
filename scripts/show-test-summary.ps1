# Prints the test summary of a Maven log.
# Usage: scripts\show-test-summary.ps1 logs\step1-suite.log
param(
    [Parameter(Mandatory = $true)]
    [string] $LogPath
)

$lines = Get-Content $LogPath

Write-Output '=== The build result ==='
foreach ($line in $lines) {
    if ($line -match '^\[INFO\] BUILD' -or $line -match '^\[ERROR\] BUILD') {
        Write-Output $line
    }
}

Write-Output ''
Write-Output '=== The totals ==='
foreach ($line in $lines) {
    if ($line -match '^\[INFO\] Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+$') {
        Write-Output $line
    }
    if ($line -match '^\[ERROR\] Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+$') {
        Write-Output $line
    }
}

Write-Output ''
Write-Output '=== The failures ==='
$found = $false
foreach ($line in $lines) {
    if ($line -match '^\[ERROR\]   ' -or $line -match '^\[ERROR\] com\.merqo') {
        Write-Output $line
        $found = $true
    }
}
if (-not $found) {
    Write-Output '  none'
}
