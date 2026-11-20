$ErrorActionPreference = 'Continue'
$root = 'G:\Merqo'
Set-Location $root

if (-not (Test-Path 'logs')) { New-Item -ItemType Directory -Path 'logs' | Out-Null }

$jar = 'backend\target\merqo-backend-0.0.1-SNAPSHOT.jar'
$log = 'logs\startup-measurement.log'

# Make sure nothing is already listening on 8080.
$existing = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if ($existing) {
  foreach ($conn in $existing) { Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue }
  Start-Sleep -Seconds 2
}

$java = 'G:\tools\jdk21\jdk-21.0.13.1+2\bin\java.exe'
$jvmArgs = @(
  '-XX:MaxRAMPercentage=70',
  '-XX:+UseSerialGC',
  '-Xss512k',
  '-XX:+ExitOnOutOfMemoryError'
)

$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
$process = Start-Process -FilePath $java -ArgumentList ($jvmArgs + @('-jar', $jar, '--spring.profiles.active=prod')) `
  -RedirectStandardOutput $log -RedirectStandardError 'logs\startup-measurement.err.log' -PassThru -NoNewWindow

$ready = $false
$readyMs = 0
while ($stopwatch.Elapsed.TotalSeconds -lt 120) {
  Start-Sleep -Milliseconds 500
  try {
    $response = Invoke-WebRequest -Uri 'http://localhost:8080/api/v1/health/ping' -TimeoutSec 5 -UseBasicParsing
    if ($response.StatusCode -eq 200) {
      $ready = $true
      $readyMs = $stopwatch.ElapsedMilliseconds
      break
    }
  } catch { }
}

$stopwatch.Stop()

# Let the JVM settle, then measure the footprint.
Start-Sleep -Seconds 3
$process.Refresh()
$workingSetMB = [math]::Round($process.WorkingSet64 / 1MB, 1)
$privateMB = [math]::Round($process.PrivateMemorySize64 / 1MB, 1)

$lines = @()
$lines += '=== Merqo backend startup measurement ==='
$lines += ("Measured on:      " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
$lines += ("JVM:               " + (& 'G:\tools\jdk21\jdk-21.0.13.1+2\bin\java.exe' -version 2>&1 | Select-Object -First 1))
$lines += ("JVM flags:         " + ($jvmArgs -join ' '))
$lines += ("Jar:               " + $jar)
$lines += ("Cold start to the first successful ping: {0} ms ({1:N1} s)" -f $readyMs, ($readyMs / 1000))
$lines += ("Working set:       {0} MB" -f $workingSetMB)
$lines += ("Private memory:    {0} MB" -f $privateMB)
$lines += ''

$lines += '--- Startup log lines ---'
if (Test-Path $log) {
  $lines += (Select-String -Path $log -Pattern 'Started MerqoApplication|Tomcat started|Successfully applied|Initializing|Starting MerqoApplication' | ForEach-Object { $_.Line.Trim() })
}

$lines += ''
$lines += '--- /api/v1/health/ping answer ---'
try {
  $ping = Invoke-WebRequest -Uri 'http://localhost:8080/api/v1/health/ping' -TimeoutSec 10 -UseBasicParsing
  $lines += $ping.Content
  $lines += ("Response time: {0} ms" -f $ping.BaseResponse.ResponseTime)
} catch {
  $lines += ("Could not read the ping: " + $_.Exception.Message)
}

$lines | Set-Content -Path 'logs\startup-measurement.txt' -Encoding utf8

Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1

$lines | ForEach-Object { Write-Output $_ }
