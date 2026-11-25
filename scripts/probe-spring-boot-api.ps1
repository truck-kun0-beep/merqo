$java = 'G:\tools\jdk21\jdk-21.0.13.1+2\bin\jar.exe'
$boot = "$env:USERPROFILE\.m2\repository\org\springframework\boot\spring-boot\4.1.3\spring-boot-4.1.3.jar"

Write-Output '=== spring-boot: EnvironmentPostProcessor ==='
& $java tf $boot | Select-String -Pattern "EnvironmentPostProcessor" | ForEach-Object { Write-Output ("  " + $_) }

Write-Output ''
Write-Output '=== spring-boot: the prepared event ==='
& $java tf $boot | Select-String -Pattern "ApplicationEnvironmentPreparedEvent" | ForEach-Object { Write-Output ("  " + $_) }
