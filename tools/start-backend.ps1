$ErrorActionPreference = 'Continue'
$env:JAVA_HOME = 'G:\tools\jdk21\jdk-21.0.13.1+2'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Set-Location 'G:\Merqo\backend'
$out = 'G:\Merqo\logs\phase2-server.log'
$err = 'G:\Merqo\logs\phase2-server.err.log'
$process = Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" `
    -ArgumentList '-jar', 'target\merqo-backend-0.0.1-SNAPSHOT.jar', '--spring.profiles.active=test', '--server.port=18080' `
    -RedirectStandardOutput $out -RedirectStandardError $err -WindowStyle Hidden -PassThru
Write-Output ('PID=' + $process.Id)
