$ErrorActionPreference = 'Continue'
Set-Location 'G:\Merqo'

# 1. Start the backend.
$existing = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if ($existing) { foreach ($c in $existing) { Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue } }
Start-Sleep -Seconds 2

$java = 'G:\tools\jdk21\jdk-21.0.13.1+2\bin\java.exe'
$backend = Start-Process -FilePath $java `
  -ArgumentList @('-jar', 'backend\target\merqo-backend-0.0.1-SNAPSHOT.jar', '--spring.profiles.active=dev') `
  -RedirectStandardOutput 'logs\e2e-backend.log' -RedirectStandardError 'logs\e2e-backend.err.log' -PassThru -NoNewWindow

# 2. Wait for the backend.
$backendReady = $false
for ($i = 0; $i -lt 60; $i++) {
  Start-Sleep -Milliseconds 500
  try {
    if ((Invoke-WebRequest -Uri 'http://localhost:8080/api/v1/health/ping' -TimeoutSec 3 -UseBasicParsing).StatusCode -eq 200) { $backendReady = $true; break }
  } catch { }
}
if (-not $backendReady) { Write-Output 'BACKEND DID NOT START'; Stop-Process -Id $backend.Id -Force; exit 1 }
Write-Output 'backend is up'

# 3. Start the Vite dev server.
$existing = Get-NetTCPConnection -LocalPort 5173 -State Listen -ErrorAction SilentlyContinue
if ($existing) { foreach ($c in $existing) { Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue } }
Start-Sleep -Seconds 2

$frontend = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', 'npm run dev' -WorkingDirectory 'G:\Merqo\frontend' `
  -RedirectStandardOutput 'logs\e2e-frontend.log' -RedirectStandardError 'logs\e2e-frontend.err.log' -PassThru -NoNewWindow

$frontendReady = $false
for ($i = 0; $i -lt 60; $i++) {
  Start-Sleep -Milliseconds 500
  try {
    if ((Invoke-WebRequest -Uri 'http://localhost:5173/' -TimeoutSec 3 -UseBasicParsing).StatusCode -eq 200) { $frontendReady = $true; break }
  } catch { }
}
if (-not $frontendReady) { Write-Output 'FRONTEND DID NOT START'; Stop-Process -Id $backend.Id -Force; Stop-Process -Id $frontend.Id -Force; exit 1 }
Write-Output 'frontend is up'

# 4. The proxy test: the same-origin /api path must reach the backend.
$lines = @()
$lines += '=== Same-origin proxy check ==='

$ping = Invoke-WebRequest -Uri 'http://localhost:5173/api/v1/health/ping' -TimeoutSec 15 -UseBasicParsing
$lines += ("GET http://localhost:5173/api/v1/health/ping -> " + $ping.StatusCode)
$lines += ("body: " + $ping.Content)
$lines += ("X-Correlation-Id header: " + $ping.Headers['X-Correlation-Id'])

$health = Invoke-WebRequest -Uri 'http://localhost:5173/api/v1/health' -TimeoutSec 15 -UseBasicParsing
$lines += ("GET http://localhost:5173/api/v1/health -> " + $health.StatusCode)
$lines += ("body: " + $health.Content)

$index = Invoke-WebRequest -Uri 'http://localhost:5173/' -TimeoutSec 15 -UseBasicParsing
$lines += ("GET http://localhost:5173/ -> " + $index.StatusCode)
$lines += ("the index page contains the root div: " + ($index.Content -match 'id="root"'))

$openapi = Invoke-WebRequest -Uri 'http://localhost:5173/v3/api-docs' -TimeoutSec 20 -UseBasicParsing
$lines += ("GET http://localhost:5173/v3/api-docs -> " + $openapi.StatusCode)

$lines | Set-Content -Path 'logs\e2e-proxy-check.txt' -Encoding utf8
$lines | ForEach-Object { Write-Output $_ }

Stop-Process -Id $frontend.Id -Force -ErrorAction SilentlyContinue
Stop-Process -Id $backend.Id -Force -ErrorAction SilentlyContinue
Get-Process node -ErrorAction SilentlyContinue | Where-Object { $_.StartTime -gt (Get-Date).AddMinutes(-5) } | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2
Write-Output 'stopped'
