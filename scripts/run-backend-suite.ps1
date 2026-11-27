# Runs the whole backend test suite and writes the output to the log folder.
#
# The Maven wrapper writes its progress to stderr, and PowerShell turns a line
# on stderr into an error. The error action is therefore relaxed for the call,
# so the log holds the whole run.
[CmdletBinding()]
param(
    [string] $LogName = 'backend-suite'
)

$env:JAVA_HOME = 'G:\tools\jdk21\jdk-21.0.13.1+2'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

$repo = 'G:\Merqo'
$log = Join-Path $repo ("logs\" + $LogName + '.log')

Set-Location $repo

$ErrorActionPreference = 'Continue'
& (Join-Path $repo 'backend\mvnw.cmd') -f (Join-Path $repo 'backend\pom.xml') -B test 2>&1 |
    Out-File $log -Encoding utf8
$code = $LASTEXITCODE
$ErrorActionPreference = 'Stop'

Write-Output ("exit=" + $code)
Write-Output ("log=" + $log)
