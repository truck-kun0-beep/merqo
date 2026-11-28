param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$KeyId = "mk_demo_webshop",
    [string]$Secret = "ms_demo_webshop_local_only_secret_2026",
    [string]$Sku = "TSHIRT-BLK-M",
    [int]$Quantity = 1,
    [decimal]$UnitPrice = 550.00,
    [decimal]$ShippingFee = 60.00,
    [ValidateSet("COD", "FULL_PREPAID", "ADVANCE_COD")]
    [string]$PaymentMode = "COD",
    [decimal]$AdvanceAmount = 0.00,
    [string]$ClaimedMethod = "",
    [string]$ClaimedReference = "",
    [string]$ExternalOrderId = ("SIM-" + [guid]::NewGuid().ToString().Substring(0, 8)),
    [string]$CustomerName = "Simulated Buyer",
    [string]$Phone = "01711223344",
    [string]$Address = "House 14, Road 5, Dhanmondi, Dhaka",
    [string]$District = "Dhaka",
    [switch]$Replay
)

$ErrorActionPreference = "Stop"
$base = $BaseUrl.TrimEnd('/')
$subtotal = $UnitPrice * $Quantity
$total = $subtotal + $ShippingFee

if ($PaymentMode -eq "FULL_PREPAID") {
    $advance = $total
    $cod = [decimal]0.00
} elseif ($PaymentMode -eq "ADVANCE_COD") {
    $advance = if ($AdvanceAmount -gt 0) { $AdvanceAmount } else { [math]::Round($total / 2, 2) }
    $cod = $total - $advance
} else {
    $advance = [decimal]0.00
    $cod = $total
}

$payloadObj = [ordered]@{
    externalOrderId = $ExternalOrderId
    customerName    = $CustomerName
    phone           = $Phone
    address         = $Address
    district        = $District
    lines           = @(
        [ordered]@{
            sku       = $Sku
            quantity  = $Quantity
            unitPrice = $UnitPrice.ToString("0.00", [System.Globalization.CultureInfo]::InvariantCulture)
        }
    )
    shippingFee     = $ShippingFee.ToString("0.00", [System.Globalization.CultureInfo]::InvariantCulture)
    discountTotal   = "0.00"
    total           = $total.ToString("0.00", [System.Globalization.CultureInfo]::InvariantCulture)
    paymentMode     = $PaymentMode
    advanceAmount   = $advance.ToString("0.00", [System.Globalization.CultureInfo]::InvariantCulture)
    codAmount       = $cod.ToString("0.00", [System.Globalization.CultureInfo]::InvariantCulture)
}
if ($ClaimedMethod) { $payloadObj.claimedAdvanceMethod = $ClaimedMethod }
if ($ClaimedReference) { $payloadObj.claimedAdvanceReference = $ClaimedReference }

$rawBody = ($payloadObj | ConvertTo-Json -Depth 5 -Compress)

function Send-SignedOrder {
    $ts = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
    $signingInput = "$ts.$rawBody"
    $hmac = New-Object System.Security.Cryptography.HMACSHA256
    $hmac.Key = [System.Text.Encoding]::UTF8.GetBytes($Secret)
    $hashBytes = $hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($signingInput))
    $sig = ([System.BitConverter]::ToString($hashBytes)).Replace("-", "").ToLowerInvariant()

    $headers = @{
        "Content-Type"      = "application/json"
        "X-Merqo-Key"       = $KeyId
        "X-Merqo-Timestamp" = $ts
        "X-Merqo-Signature" = $sig
    }

    try {
        $resp = Invoke-WebRequest -Uri "$base/integration/v1/orders" -Method Post -Headers $headers -Body $rawBody -UseBasicParsing
        Write-Host ("POST /integration/v1/orders -> {0}" -f $resp.StatusCode)
        Write-Output $resp.Content
    } catch {
        $httpResp = $_.Exception.Response
        if ($httpResp) {
            $reader = New-Object System.IO.StreamReader($httpResp.GetResponseStream())
            $errBody = $reader.ReadToEnd()
            Write-Host ("POST /integration/v1/orders -> {0}" -f [int]$httpResp.StatusCode)
            Write-Output $errBody
        } else {
            throw
        }
    }
}

Send-SignedOrder
if ($Replay) {
    Write-Host "--- Sending idempotent replay ---"
    Send-SignedOrder
}
