$ErrorActionPreference = 'Continue'
Set-Location 'G:\Merqo'

# Simulate the 512 MB container of the free Render instance by capping the
# memory the JVM may use, exactly as a container memory limit would.
$jar = 'backend\target\merqo-backend-0.0.1-SNAPSHOT.jar'
$log = 'logs\startup-512mb.log'

$existing = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if ($existing) {
  foreach ($conn in $existing) { Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue }
  Start-Sleep -Seconds 2
}

$java = 'G:\tools\jdk21\jdk-21.0.13.1+2\bin\java.exe'
$jvmArgs = @(
  '-Xmx360m',
  '-XX:MaxRAM=512m',
  '-XX:MaxRAMPercentage=70',
  '-XX:+UseSerialGC',
  '-Xss512k',
  '-XX:+ExitOnOutOfMemoryError'
)

$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
$process = Start-Process -FilePath $java -ArgumentList ($jvmArgs + @('-jar', $jar, '--spring.profiles.active=prod')) `
  -RedirectStandardOutput $log -RedirectStandardError 'logs\startup-512mb.err.log' -PassThru -NoNewWindow

$ready = $false
$readyMs = 0
while ($stopwatch.Elapsed.TotalSeconds -lt 120) {
  Start-Sleep -Milliseconds 500
  try {
    $response = Invoke-WebRequest -Uri 'http://localhost:8080/api/v1/health/ping' -TimeoutSec 5 -UseBasicParsing
    if ($response.StatusCode -eq 200) { $ready = $true; $readyMs = $stopwatch.ElapsedMilliseconds; break }
  } catch { }
}
$stopwatch.Stop()

Start-Sleep -Seconds 3
$process.Refresh()
$workingSetMB = [math]::Round($process.WorkingSet64 / 1MB, 1)
$privateMB = [math]::Round($process.PrivateMemorySize64 / 1MB, 1)

# Hammer it a little to see whether the heap holds under load.
$latencies = @()
for ($i = 0; $i -lt 30; $i++) {
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  try { Invoke-WebRequest -Uri 'http://localhost:8080/api/v1/health/ping' -TimeoutSec 10 -UseBasicParsing | Out-Null } catch { }
  $sw.Stop()
  $latencies += $sw.ElapsedMilliseconds
}
Start-Sleep -Seconds 2
$process.Refresh()
$workingSetAfterMB = [math]::Round($process.WorkingSet64 / 1MB, 1)

$lines = @()
$lines += '=== Merqo backend measured as a 512 MB container would see it ==='
$lines += ("Measured on:   " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
$lines += ("JVM flags:     " + ($jvmArgs -join ' '))
$lines += ("Cold start to the first successful ping: {0} ms ({1:N1} s)" -f $readyMs, ($readyMs / 1000))
$lines += ("Working set after startup:  {0} MB" -f $workingSetMB)
$lines += ("Private memory after startup: {0} MB" -f $privateMB)
$lines += ("Working set after 30 pings:  {0} MB" -f $workingSetAfterMB)
$lines += ("Ping latency: min {0} ms, average {1} ms, max {2} ms" -f ($latencies | Measure-Object -Minimum).Minimum, [math]::Round(($latencies | Measure-Object -Average).Average, 1), ($latencies | Measure-Object -Maximum).Maximum)
$lines += ''
$lines += '--- Startup log lines ---'
$lines += (Select-String -Path $log -Pattern 'Started MerqoApplication|Tomcat started|Successfully applied' | ForEach-Object { $_.Line.Trim() })

$lines | Set-Content -Path 'logs\startup-512mb.txt' -Encoding utf8

Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1

$lines | ForEach-Object { Write-Output $_ }
