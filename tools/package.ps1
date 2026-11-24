$ErrorActionPreference = 'Continue'
$env:JAVA_HOME = 'G:\tools\jdk21\jdk-21.0.13.1+2'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Set-Location 'G:\Merqo\backend'
$log = 'G:\Merqo\logs\phase2-package.log'
& .\mvnw.cmd -B -o clean package '-DskipTests' *> $log
Write-Output ('EXIT=' + $LASTEXITCODE)
Select-String -Path $log -Pattern 'BUILD|ERROR' | Select-Object -Last 15 | ForEach-Object { Write-Output $_.Line }
