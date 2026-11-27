# Runs one backend test class and writes the output to the log folder.
# Usage: scripts\run-backend-test.ps1 -Test CsrfAfterLoginIntegrationTest
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Test,

    [string] $LogName = 'backend-test'
)

$ErrorActionPreference = 'Stop'

$env:JAVA_HOME = 'G:\tools\jdk21\jdk-21.0.13.1+2'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

$repo = 'G:\Merqo'
$log = Join-Path $repo ("logs\" + $LogName + '.log')

Set-Location $repo
& (Join-Path $repo 'backend\mvnw.cmd') -f (Join-Path $repo 'backend\pom.xml') -B test `
    "-Dtest=$Test" '-DfailIfNoSpecifiedTests=false' 2>&1 | Out-File $log -Encoding utf8

Write-Output ("exit=" + $LASTEXITCODE)
Write-Output ("log=" + $log)
