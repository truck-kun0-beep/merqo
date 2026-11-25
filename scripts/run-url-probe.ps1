$jar = "$env:USERPROFILE\.m2\repository\org\postgresql\postgresql\42.7.14\postgresql-42.7.14.jar"
$java = 'G:\tools\jdk21\jdk-21.0.13.1+2\bin\java.exe'
& $java -cp "$jar;G:\Merqo\logs" G:\Merqo\logs\UrlProbe2.java 2>&1 |
    Where-Object { $_ -notmatch 'WARNING' -and $_ -notmatch '^\w{3} \d{1,2}, \d{4}' } |
    ForEach-Object { Write-Output $_ }
