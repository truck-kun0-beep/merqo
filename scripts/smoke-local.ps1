param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$SpringProfile = "dev",
    [string]$DatabaseUrl = "",
    [switch]$UseRunningServer
)

$ErrorActionPreference = "Stop"
$base = $BaseUrl.TrimEnd('/')
$port = ([uri]$base).Port
$rootDir = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $rootDir "backend\target\merqo-backend-0.0.1-SNAPSHOT.jar"
$proc = $null
$outLog = [System.IO.Path]::GetTempFileName()
$errLog = [System.IO.Path]::GetTempFileName()
$passed = 0

function Assert-Step($label, [bool]$ok, $detail = "") {
    if (-not $ok) {
        throw "[FAIL] $label $detail"
    }
    $script:passed++
    Write-Host ("[PASS] {0} {1}" -f $label, $detail) -ForegroundColor Green
}

function Invoke-ApiJson {
    param(
        [string]$Method,
        [string]$Url,
        [Microsoft.PowerShell.Commands.WebRequestSession]$Session = $null,
        [hashtable]$Headers = @{},
        [string]$Body = $null
    )
    $reqHeaders = @{}
    foreach ($k in $Headers.Keys) { $reqHeaders[$k] = $Headers[$k] }
    $params = @{
        Uri             = $Url
        Method          = $Method
        Headers         = $reqHeaders
        UseBasicParsing = $true
    }
    if ($Session) {
        $params.WebSession = $Session
        $csrfCookie = $Session.Cookies.GetCookies([uri]$base) | Where-Object { $_.Name -eq "XSRF-TOKEN" } | Select-Object -First 1
        if ($csrfCookie -and -not $reqHeaders.ContainsKey("X-XSRF-TOKEN")) {
            $reqHeaders["X-XSRF-TOKEN"] = $csrfCookie.Value
        }
    }
    if ($Body) {
        $params.ContentType = "application/json"
        $params.Body = $Body
    }
    try {
        $resp = Invoke-WebRequest @params
        $json = if ($resp.Content) { $resp.Content | ConvertFrom-Json } else { $null }
        return [pscustomobject]@{ Status = [int]$resp.StatusCode; Body = $json; Raw = $resp.Content }
    } catch {
        $r = $_.Exception.Response
        if (-not $r) { throw }
        $reader = New-Object System.IO.StreamReader($r.GetResponseStream())
        $raw = $reader.ReadToEnd()
        $json = try { $raw | ConvertFrom-Json } catch { $null }
        return [pscustomobject]@{ Status = [int]$r.StatusCode; Body = $json; Raw = $raw }
    }
}

function Sign-IntegrationHeaders([string]$KeyId, [string]$Secret, [string]$Body) {
    $ts = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
    $hmac = New-Object System.Security.Cryptography.HMACSHA256
    $hmac.Key = [System.Text.Encoding]::UTF8.GetBytes($Secret)
    $hash = $hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes("$ts.$Body"))
    $sig = ([System.BitConverter]::ToString($hash)).Replace("-", "").ToLowerInvariant()
    return @{
        "X-Merqo-Key"       = $KeyId
        "X-Merqo-Timestamp" = $ts
        "X-Merqo-Signature" = $sig
    }
}

try {
    if (-not $UseRunningServer) {
        if (-not (Test-Path $jarPath)) {
            throw "Packaged JAR not found at $jarPath. Run scripts\run-tests.bat first."
        }
        $javaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java" }
        $env:SPRING_PROFILES_ACTIVE = $SpringProfile
        $env:SERVER_PORT = "$port"
        $env:DEMO_MODE = "true"
        $env:WEBSITE_MODE = "mock"
        $env:PAYMENT_MODE = "mock"
        if ($DatabaseUrl) {
            $env:DATABASE_URL = $DatabaseUrl
        }
        $proc = Start-Process -FilePath $javaBin -ArgumentList @("-jar", $jarPath) -WorkingDirectory $rootDir -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru -NoNewWindow

        $ready = $false
        for ($i = 0; $i -lt 60; $i++) {
            Start-Sleep -Seconds 1
            if ($proc.HasExited) {
                $outText = (Get-Content $outLog -Tail 30 -ErrorAction SilentlyContinue) -join "`n"
                $errText = (Get-Content $errLog -Tail 30 -ErrorAction SilentlyContinue) -join "`n"
                throw "Backend exited prematurely ($($proc.ExitCode)):`n$outText`n$errText"
            }
            try {
                $ping = Invoke-WebRequest -Uri "$base/api/v1/health/ping" -UseBasicParsing -TimeoutSec 2
                if ($ping.StatusCode -eq 200) {
                    $ready = $true
                    break
                }
            } catch {
                # Still booting
            }
        }
        Assert-Step "Local backend health ping" $ready "($base/api/v1/health/ping)"
    } else {
        $ping = Invoke-ApiJson -Method "Get" -Url "$base/api/v1/health/ping"
        Assert-Step "Running backend health ping" ($ping.Status -eq 200)
    }

    # 1. Owner login
    $ownerSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $null = Invoke-ApiJson -Method "Get" -Url "$base/api/v1/auth/status" -Session $ownerSession
    $ownerLogin = Invoke-ApiJson -Method "Post" -Url "$base/api/v1/demo/login" -Session $ownerSession -Body '{"accountKey":"owner"}'
    Assert-Step "Owner demo login" ($ownerLogin.Status -eq 200 -and $ownerLogin.Body.accountType -eq "OWNER")

    # 2. Demo simulator info & Integration key creation (secret shown once)
    $simInfo = Invoke-ApiJson -Method "Get" -Url "$base/api/v1/demo/simulator-info" -Session $ownerSession
    Assert-Step "Simulator info" ($simInfo.Status -eq 200 -and $simInfo.Body.demoClient.keyId -eq "mk_demo_webshop")

    $smokeName = "Smoke Store " + ([guid]::NewGuid().ToString().Substring(0, 6))
    $createdKey = Invoke-ApiJson -Method "Post" -Url "$base/api/v1/integration/clients" -Session $ownerSession -Body (@{ name = $smokeName } | ConvertTo-Json -Compress)
    Assert-Step "Create integration key" ($createdKey.Status -eq 201 -and $createdKey.Body.secret.StartsWith("ms_"))
    $keyId = $createdKey.Body.client.keyId
    $secret = $createdKey.Body.secret

    $listKeys = Invoke-ApiJson -Method "Get" -Url "$base/api/v1/integration/clients" -Session $ownerSession
    Assert-Step "Client list redacts secrets" ($listKeys.Status -eq 200 -and -not $listKeys.Raw.Contains($secret))

    # 3. Signed stock check
    $stockHeaders = Sign-IntegrationHeaders $keyId $secret ""
    $stockResp = Invoke-ApiJson -Method "Get" -Url "$base/integration/v1/stock?sku=TE-100-BLK" -Headers $stockHeaders
    Assert-Step "Signed integration stock query" ($stockResp.Status -eq 200 -and $stockResp.Body.items[0].found -eq $true)
    $availBefore = [int]$stockResp.Body.items[0].available

    # 4. Signed order webhook + idempotent replay
    $extId = "SMK-" + ([guid]::NewGuid().ToString().Substring(0, 8))
    $orderPayload = @{
        externalOrderId = $extId
        customerName    = "Smoke Test Buyer"
        phone           = "+880 1711-223344"
        address         = "Road 7, Dhanmondi, Dhaka"
        district        = "Dhaka"
        lines           = @(@{ sku = "TE-100-BLK"; quantity = 1; unitPrice = "750.00" })
        shippingFee     = "60.00"
        discountTotal   = "0.00"
        total           = "810.00"
        paymentMode     = "COD"
        advanceAmount   = "0.00"
        codAmount       = "810.00"
    } | ConvertTo-Json -Depth 5 -Compress

    $h1 = Sign-IntegrationHeaders $keyId $secret $orderPayload
    $post1 = Invoke-ApiJson -Method "Post" -Url "$base/integration/v1/orders" -Headers $h1 -Body $orderPayload
    Assert-Step "Signed website order intake (201)" ($post1.Status -eq 201 -and $post1.Body.status -eq "PENDING_CONFIRMATION")
    $orderId = $post1.Body.orderId

    $h2 = Sign-IntegrationHeaders $keyId $secret $orderPayload
    $post2 = Invoke-ApiJson -Method "Post" -Url "$base/integration/v1/orders" -Headers $h2 -Body $orderPayload
    Assert-Step "Idempotent replay returns 200" ($post2.Status -eq 200 -and $post2.Body.idempotentReplay -eq $true -and $post2.Body.orderId -eq $orderId)

    # 5. Staff call, confirm, availability check & owner cancel
    $ordersSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $null = Invoke-ApiJson -Method "Get" -Url "$base/api/v1/auth/status" -Session $ordersSession
    $ordersLogin = Invoke-ApiJson -Method "Post" -Url "$base/api/v1/demo/login" -Session $ordersSession -Body '{"accountKey":"orders"}'
    Assert-Step "Orders staff login" ($ordersLogin.Status -eq 200)

    $callResp = Invoke-ApiJson -Method "Post" -Url "$base/api/v1/orders/$orderId/calls" -Session $ordersSession -Body '{"result":"ANSWERED","note":"Confirmed address"}'
    Assert-Step "Staff log confirmation call" ($callResp.Status -eq 200 -and $callResp.Body.callLogs.Count -eq 1)

    $confirmResp = Invoke-ApiJson -Method "Post" -Url "$base/api/v1/orders/$orderId/confirm" -Session $ordersSession -Body '{}'
    Assert-Step "Staff confirm reserves stock" ($confirmResp.Status -eq 200 -and $confirmResp.Body.status -eq "CONFIRMED")

    $stockAfterHeaders = Sign-IntegrationHeaders $keyId $secret ""
    $stockAfter = Invoke-ApiJson -Method "Get" -Url "$base/integration/v1/stock?sku=TE-100-BLK" -Headers $stockAfterHeaders
    Assert-Step "Available stock dropped by 1 after confirm" ([int]$stockAfter.Body.items[0].available -eq ($availBefore - 1))

    # 6. Manual order & customer risk lookup
    $histResp = Invoke-ApiJson -Method "Get" -Url "$base/api/v1/customers/history?phone=01911100003" -Session $ordersSession
    Assert-Step "Customer HIGH refusal risk badge" ($histResp.Status -eq 200 -and $histResp.Body.riskTier -eq "HIGH")

    $manualPayload = @{
        customerName  = "Manual Smoke Buyer"
        phone         = "01712345678"
        address       = "Banani Road 11"
        district      = "Dhaka"
        lines         = @(@{ sku = "TE-100-BLK"; quantity = 1 })
        shippingFee   = "60.00"
        discountTotal = "0.00"
        paymentMode   = "COD"
        confirmNow    = $false
    } | ConvertTo-Json -Depth 4 -Compress
    $manualResp = Invoke-ApiJson -Method "Post" -Url "$base/api/v1/orders/manual" -Session $ordersSession -Body $manualPayload
    Assert-Step "Create manual order (201)" ($manualResp.Status -eq 201 -and $manualResp.Body.channel -eq "MANUAL")

    # 7. Cancel confirmed order as owner & verify release
    $cancelResp = Invoke-ApiJson -Method "Post" -Url "$base/api/v1/orders/$orderId/cancel" -Session $ownerSession -Body '{"reason":"CUSTOMER_REQUESTED"}'
    Assert-Step "Cancel confirmed order releases reservation" ($cancelResp.Status -eq 200 -and $cancelResp.Body.releasedReservation -eq $true)

    # 8. Test zero-dependency Node website simulator against running server
    $nodeOut = node (Join-Path $rootDir "tools\website-simulator\send-order.mjs") --url $base --sku TE-100-BLK --price 750.00 --replay
    Assert-Step "Node website simulator + replay" ($LASTEXITCODE -eq 0 -and ($nodeOut -join "`n").Contains("PENDING_CONFIRMATION"))

    # 9. Disable test client
    $disResp = Invoke-ApiJson -Method "Post" -Url "$base/api/v1/integration/clients/$($createdKey.Body.client.id)/disable" -Session $ownerSession
    Assert-Step "Disable smoke integration key" ($disResp.Status -eq 200 -and $disResp.Body.active -eq $false)

    # 10. Phase 5: Simulate provider payment, record advance, check with mock provider, cancel, refund lifecycle
    $prepaidPayload = @{
        customerName  = "Prepaid Smoke Buyer"
        phone         = "01712345679"
        address       = "Uttara Sector 10"
        district      = "Dhaka"
        lines         = @(@{ sku = "TE-100-BLK"; quantity = 1 })
        shippingFee   = "60.00"
        discountTotal = "0.00"
        paymentMode   = "FULL_PREPAID"
        advanceAmount = "810.00"
        confirmNow    = $true
    } | ConvertTo-Json -Depth 4 -Compress
    $prepaidOrder = Invoke-ApiJson -Method "Post" -Url "$base/api/v1/orders/manual" -Session $ownerSession -Body $prepaidPayload
    Assert-Step "Create prepaid manual order for payment smoke" ($prepaidOrder.Status -eq 201 -and $prepaidOrder.Body.readyForShipment -eq $false) "($($prepaidOrder.Status))"
    $prepaidId = $prepaidOrder.Body.id
    $prepaidTotal = [string]$prepaidOrder.Body.total

    $simTrx = "SMKBK" + ([guid]::NewGuid().ToString("N").Substring(0, 6).ToUpperInvariant())
    $simPayBody = @{
        method     = "BKASH"
        reference  = $simTrx
        amount     = $prepaidTotal
        senderHint = "01700000001"
        variant    = "MATCH"
    } | ConvertTo-Json -Compress
    $simPay = Invoke-ApiJson -Method "Post" -Url "$base/api/v1/demo/simulate-payment" -Session $ownerSession -Body $simPayBody
    Assert-Step "Seed mock provider transaction" ($simPay.Status -eq 201) "($($simPay.Status))"

    $recPayBody = @{
        method    = "BKASH"
        amount    = $prepaidTotal
        reference = $simTrx
        payerNote = "Smoke prepaid transfer"
    } | ConvertTo-Json -Compress
    $recPay = Invoke-ApiJson -Method "Post" -Url "$base/api/v1/orders/$prepaidId/payments" -Session $ownerSession -Body $recPayBody
    Assert-Step "Record order payment (PENDING_VERIFICATION)" ($recPay.Status -eq 201 -and $recPay.Body.status -eq "PENDING_VERIFICATION") "($($recPay.Status))"
    $payId = $recPay.Body.id

    $chkPay = Invoke-ApiJson -Method "Post" -Url "$base/api/v1/payments/$payId/check" -Session $ownerSession -Body '{}'
    Assert-Step "Mock provider verifies payment" ($chkPay.Status -eq 200 -and $chkPay.Body.outcome -eq "CONFIRMED" -and $chkPay.Body.payment.status -eq "VERIFIED") "($($chkPay.Status))"

    $afterPrepayOrder = Invoke-ApiJson -Method "Get" -Url "$base/api/v1/orders/$prepaidId" -Session $ownerSession
    Assert-Step "Confirmed prepaid order becomes readyForShipment" ($afterPrepayOrder.Status -eq 200 -and $afterPrepayOrder.Body.readyForShipment -eq $true)

    $cancelPrepaid = Invoke-ApiJson -Method "Post" -Url "$base/api/v1/orders/$prepaidId/cancel" -Session $ownerSession -Body '{"reason":"CUSTOMER_REQUESTED"}'
    Assert-Step "Cancel verified prepaid order" ($cancelPrepaid.Status -eq 200)

    $reqRefund = Invoke-ApiJson -Method "Post" -Url "$base/api/v1/orders/$prepaidId/refunds" -Session $ownerSession -Body (@{ amount = $prepaidTotal; reason = "ORDER_CANCELLED"; note = "Smoke cancel refund" } | ConvertTo-Json -Compress)
    Assert-Step "Request refund" ($reqRefund.Status -eq 201 -and $reqRefund.Body.status -eq "REQUESTED") "($($reqRefund.Status))"
    $refundId = $reqRefund.Body.id

    $appRefund = Invoke-ApiJson -Method "Post" -Url "$base/api/v1/refunds/$refundId/approve" -Session $ownerSession -Body '{"note":"Owner approved smoke refund"}'
    Assert-Step "Approve refund" ($appRefund.Status -eq 200 -and $appRefund.Body.status -eq "APPROVED")

    $payRefund = Invoke-ApiJson -Method "Post" -Url "$base/api/v1/refunds/$refundId/pay" -Session $ownerSession -Body '{"payoutMethod":"CASH","note":"Cash payout smoke"}'
    Assert-Step "Pay refund (PAID)" ($payRefund.Status -eq 200 -and $payRefund.Body.status -eq "PAID")

    # 11. Phase 5: COD collection + exact settlement + Payments history & summary
    $codOrderPayload = @{
        customerName  = "COD Smoke Buyer"
        phone         = "01812345680"
        address       = "Mirpur DOHS"
        district      = "Dhaka"
        lines         = @(@{ sku = "TE-100-BLK"; quantity = 1 })
        shippingFee   = "60.00"
        discountTotal = "0.00"
        paymentMode   = "COD"
        confirmNow    = $true
    } | ConvertTo-Json -Depth 4 -Compress
    $codOrder = Invoke-ApiJson -Method "Post" -Url "$base/api/v1/orders/manual" -Session $ownerSession -Body $codOrderPayload
    Assert-Step "Create confirmed COD order for settlement smoke" ($codOrder.Status -eq 201)
    $codOrderId = $codOrder.Body.id
    $codGross = [decimal]$codOrder.Body.codAmount
    $codFee = [decimal]20.00
    $codNet = ($codGross - $codFee).ToString("0.00", [System.Globalization.CultureInfo]::InvariantCulture)
    $codGrossStr = $codGross.ToString("0.00", [System.Globalization.CultureInfo]::InvariantCulture)

    $codColBody = @{
        courier     = "STEADFAST"
        amount      = $codGrossStr
        collectedOn = (Get-Date -Format "yyyy-MM-dd")
        note        = "Smoke COD collection"
    } | ConvertTo-Json -Compress
    $codCol = Invoke-ApiJson -Method "Post" -Url "$base/api/v1/orders/$codOrderId/cod-collections" -Session $ownerSession -Body $codColBody
    Assert-Step "Record COD collection" ($codCol.Status -eq 201) "($($codCol.Status))"

    $setRef = "STF-SMK-" + ([guid]::NewGuid().ToString("N").Substring(0, 6).ToUpperInvariant())
    $settleBody = @{
        courier        = "STEADFAST"
        reference      = $setRef
        receivedOn     = (Get-Date -Format "yyyy-MM-dd")
        receiveMethod  = "BANK"
        amountReceived = $codNet
        lines          = @(@{ orderId = $codOrderId; codCollectedAmount = $codGrossStr; fee = "20.00" })
    } | ConvertTo-Json -Depth 4 -Compress
    $settleResp = Invoke-ApiJson -Method "Post" -Url "$base/api/v1/cod/settlements" -Session $ownerSession -Body $settleBody
    Assert-Step "Create exact courier settlement (NONE)" ($settleResp.Status -eq 201 -and $settleResp.Body.differenceKind -eq "NONE")

    $payHist = Invoke-ApiJson -Method "Get" -Url "$base/api/v1/payments/history?page=0&size=10" -Session $ownerSession
    $paySum = Invoke-ApiJson -Method "Get" -Url "$base/api/v1/payments/summary" -Session $ownerSession
    Assert-Step "Payments history & summary align" ($payHist.Status -eq 200 -and $paySum.Status -eq 200 -and $payHist.Body.totalItems -eq $paySum.Body.totalCount)

    Write-Host ""
    Write-Host ("All {0} local smoke checks passed." -f $passed) -ForegroundColor Cyan
} finally {
    if ($proc -and -not $proc.HasExited) {
        Stop-Process -Id $proc.Id -Force
        Write-Host "Stopped local backend process (PID $($proc.Id))."
    }
    Remove-Item $outLog, $errLog -Force -ErrorAction SilentlyContinue
}
